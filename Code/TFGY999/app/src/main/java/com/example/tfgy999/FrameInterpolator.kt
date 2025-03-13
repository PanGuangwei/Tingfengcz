package com.example.tfgy999

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLES20
import android.os.Handler
import android.os.Process
import android.os.Build
import android.util.Log
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

class KalmanFilter {
    private var estimate: Float = 0f
    private var errorCovariance: Float = 1f
    private var processNoise: Float = 0.01f
    private val measurementNoise: Float = 0.1f

    fun update(measurement: Float): Float {
        val kalmanGain = errorCovariance / (errorCovariance + measurementNoise)
        estimate += kalmanGain * (measurement - estimate)
        errorCovariance = (1 - kalmanGain) * errorCovariance + processNoise
        return estimate
    }

    fun getEstimate(): Float = estimate

    fun setProcessNoise(noise: Float) {
        processNoise = noise
    }
}

class PriorityRunnable(var priority: Int, val runnable: Runnable) : Runnable, Comparable<PriorityRunnable> {
    override fun run() {
        runnable.run()
    }

    override fun compareTo(other: PriorityRunnable): Int = other.priority - this.priority
}

class FrameInterpolator(
    private var targetFrameRate: Int,
    private val handler: Handler,
    private val service: AutoFrameBoostService,
    private val method: String = "高级运动矢量插值（动态场景，低延迟，高精度）",
    private val context: Context = service.applicationContext
) {
    private val TAG = "FrameInterpolator"
    private var isRunning = AtomicBoolean(false)
    private var lastFrameTime = 0L
    private var frameCount = 0
    private var capturedFrameCount = 0
    var currentFps = 0f
    private var previousFrame: ByteBuffer? = null
    private var currentFrame: ByteBuffer? = null
    private var interpolatedFrame: ByteBuffer? = null
    private var motionVectorCache = ArrayDeque<ByteArray>(3)
    private var width = 0
    private var height = 0
    private var gyroCompensationX = 0f
    private var gyroCompensationY = 0f
    private var resolutionScale = 1.0f
    private var blockSize = 8
    private var searchRange = 8
    private var isCharging = false
    private var qualityLevel = 4
    private var frameIndex = 0
    private val kalmanFilterX = KalmanFilter()
    private val kalmanFilterY = KalmanFilter()

    private val executor = ThreadPoolExecutor(
        Runtime.getRuntime().availableProcessors(),
        Runtime.getRuntime().availableProcessors(),
        60L, TimeUnit.SECONDS,
        PriorityBlockingQueue<Runnable>(),
        { r: Runnable ->
            Thread(r).apply {
                // 修改：使用 Thread.MAX_PRIORITY 代替 Process.THREAD_PRIORITY_DISPLAY
                priority = Thread.MAX_PRIORITY
            }
        }
    ).apply {
        setRejectedExecutionHandler { r: Runnable, _: ThreadPoolExecutor ->
            (r as PriorityRunnable).priority += 2
            execute(r)
        }
    }

    init {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        sensorManager.registerListener(object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                event?.let {
                    val gx = it.values[0]
                    val gy = it.values[1]
                    val acceleration = sqrt(gx * gx + gy * gy)
                    val processNoise = if (acceleration > 1.0f) 0.05f else 0.01f
                    kalmanFilterX.setProcessNoise(processNoise)
                    kalmanFilterY.setProcessNoise(processNoise)
                    kalmanFilterX.update(gx)
                    kalmanFilterY.update(gy)
                    gyroCompensationX = kalmanFilterX.getEstimate() * 0.15f
                    gyroCompensationY = kalmanFilterY.getEstimate() * 0.15f
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }, gyroSensor, SensorManager.SENSOR_DELAY_GAME)

        adjustBlockSizeAndSearchRangeForSOC()
    }

    fun startInterpolation() {
        if (isRunning.compareAndSet(false, true)) {
            val frameIntervalNanos = (1_000_000_000 / targetFrameRate).toLong()
            var lastRenderTime = System.nanoTime()

            handler.post(object : Runnable {
                override fun run() {
                    if (!isRunning.get()) return
                    try {
                        val currentTime = System.nanoTime()
                        val elapsed = currentTime - lastRenderTime
                        val framesToRender = (elapsed / frameIntervalNanos).toInt().coerceAtLeast(1)
                        lastRenderTime = currentTime

                        adaptiveQualityControl()

                        for (i in 0 until framesToRender) {
                            val factor = i.toFloat() / framesToRender
                            renderFrameAsync(factor)
                            frameCount++
                        }

                        calculateFps(currentTime)
                        if (isRunning.get()) {
                            handler.postDelayed(this, (1000 / targetFrameRate).toLong())
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "插帧循环错误: ${e.message}", e)
                    }
                }
            })
        }
    }

    fun stopInterpolation() {
        isRunning.set(false)
        handler.removeCallbacksAndMessages(null)
        executor.shutdown()
    }

    @Synchronized
    fun processFrameBuffer(buffer: ByteBuffer, frameWidth: Int, frameHeight: Int) {
        if (width == 0 || height == 0) {
            width = (frameWidth * resolutionScale).toInt()
            height = (frameHeight * resolutionScale).toInt()
            interpolatedFrame = DirectBufferPool.acquire(width * height * 4)
            motionVectorCache.add(compressMotionVectors(Array(height / blockSize) { IntArray(width / blockSize * 2) }))
        }
        previousFrame?.let { DirectBufferPool.release(it) }
        previousFrame = currentFrame
        currentFrame = DirectBufferPool.acquire(width * height * 4).apply {
            rewind()
            put(buffer)
            rewind()
        }
        capturedFrameCount++
    }

    private fun renderFrameAsync(factor: Float) {
        if (shouldSkipInterpolation()) {
            interpolatedFrame?.put(currentFrame!!)
            interpolatedFrame?.rewind()
            handler.post { renderInterpolatedFrameAsync() }
            return
        }
        if (previousFrame == null || currentFrame == null || interpolatedFrame == null) return
        val priority = if (frameIndex % 5 == 0) 10 else 1
        frameIndex++
        executor.execute(PriorityRunnable(priority) {
            // 修改：移除 Process.setThreadPriority，使用线程池创建时的优先级
            val vectors = enhancedMotionEstimation(factor)
            applyMotionVectors(vectors, factor)
            handler.post { renderInterpolatedFrameAsync() }
        })
    }

    private fun shouldSkipInterpolation(): Boolean {
        val diff = calculateFrameDifference()
        return diff < 0.01f
    }

    private fun enhancedMotionEstimation(factor: Float): Array<IntArray> {
        previousFrame?.rewind()
        currentFrame?.rewind()
        interpolatedFrame?.rewind()

        adjustBlockSizeAndSearchRange()

        val prevMat = Mat(height, width, CvType.CV_8UC4)
        val currMat = Mat(height, width, CvType.CV_8UC4)
        previousFrame!!.rewind()
        currentFrame!!.rewind()
        prevMat.put(0, 0, ByteArray(prevMat.total().toInt() * 4).also { previousFrame!!.get(it) })
        currMat.put(0, 0, ByteArray(currMat.total().toInt() * 4).also { currentFrame!!.get(it) })

        val levels = 3
        val prevPyramid = createPyramid(prevMat, levels)
        val currPyramid = createPyramid(currMat, levels)

        val vectors = pyramidMotionEstimation(prevPyramid, currPyramid, levels)

        updateMotionVectorCache(vectors)
        return vectors
    }

    private fun createPyramid(frame: Mat, levels: Int): List<Mat> {
        val pyramid = mutableListOf<Mat>()
        var current = frame.clone()
        pyramid.add(current)
        for (i in 1 until levels) {
            val downsampled = Mat()
            Imgproc.pyrDown(current, downsampled)
            pyramid.add(downsampled)
            current = downsampled
        }
        return pyramid
    }

    private fun pyramidMotionEstimation(prevPyramid: List<Mat>, currPyramid: List<Mat>, levels: Int): Array<IntArray> {
        val vectors = Array(prevPyramid[0].rows() / blockSize) { IntArray(prevPyramid[0].cols() / blockSize * 2) }
        for (level in levels - 1 downTo 0) {
            val scale = 1 shl level
            val prev = prevPyramid[level]
            val curr = currPyramid[level]
            for (y in 0 until prev.rows() step blockSize) {
                for (x in 0 until prev.cols() step blockSize) {
                    val vectorIndex = (y / blockSize) * (prev.cols() / blockSize) + (x / blockSize)
                    var bestDx = if (level == levels - 1) 0 else vectors[y / blockSize][vectorIndex * 2] * 2
                    var bestDy = if (level == levels - 1) 0 else vectors[y / blockSize][vectorIndex * 2 + 1] * 2
                    var minDiff = calculateBlockDifference(prev, curr, x, y, bestDx, bestDy)
                    val step = if (level == 0) 1 else 2
                    for (dy in -step..step step step) {
                        for (dx in -step..step step step) {
                            val diff = calculateBlockDifference(prev, curr, x, y, bestDx + dx, bestDy + dy)
                            if (diff < minDiff) {
                                minDiff = diff
                                bestDx += dx
                                bestDy += dy
                            }
                        }
                    }
                    vectors[y / blockSize][vectorIndex * 2] = bestDx / scale
                    vectors[y / blockSize][vectorIndex * 2 + 1] = bestDy / scale
                }
            }
        }
        return vectors
    }

    private fun calculateBlockDifference(prev: Mat, curr: Mat, x: Int, y: Int, dx: Int, dy: Int): Int {
        if (Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
            val prevArray = ByteArray(prev.total().toInt() * 4)
            val currArray = ByteArray(curr.total().toInt() * 4)
            prev.get(0, 0, prevArray)
            curr.get(0, 0, currArray)
            return calculateBlockDifferenceNeon(prevArray, currArray, x, y, dx, dy, prev.cols(), prev.rows(), blockSize)
        } else {
            var diff = 0
            for (by in 0 until blockSize) {
                for (bx in 0 until blockSize) {
                    val px = (x + bx).coerceIn(0, prev.cols() - 1)
                    val py = (y + by).coerceIn(0, prev.rows() - 1)
                    val cx = (x + bx + dx).coerceIn(0, curr.cols() - 1)
                    val cy = (y + by + dy).coerceIn(0, curr.rows() - 1)
                    val prevPixel = prev.get(py, px)[0]
                    val currPixel = curr.get(cy, cx)[0]
                    diff += abs(prevPixel - currPixel).toInt()
                }
            }
            return diff
        }
    }

    private external fun calculateBlockDifferenceNeon(
        prev: ByteArray, curr: ByteArray, x: Int, y: Int, dx: Int, dy: Int, width: Int, height: Int, blockSize: Int
    ): Int

    private fun applyMotionVectors(vectors: Array<IntArray>, factor: Float) {
        interpolatedFrame?.rewind()
        for (y in 0 until height step blockSize) {
            for (x in 0 until width step blockSize) {
                val vectorIndex = (y / blockSize) * (width / blockSize) + (x / blockSize)
                val dx = vectors[y / blockSize][vectorIndex * 2]
                val dy = vectors[y / blockSize][vectorIndex * 2 + 1]
                val blendFactor = (abs(dx) + abs(dy)).toFloat() / (blockSize * 2).toFloat()

                for (by in 0 until blockSize) {
                    for (bx in 0 until blockSize) {
                        val px = (x + bx + (dx * factor + gyroCompensationX).toInt()).coerceIn(0, width - 1)
                        val py = (y + by + (dy * factor + gyroCompensationY).toInt()).coerceIn(0, height - 1)
                        val destPos = (y + by) * width + (x + bx)
                        previousFrame?.position((py * width + px) * 4)
                        interpolatedFrame?.position(destPos * 4)
                        val pixel = previousFrame?.getInt() ?: 0
                        val blendedPixel = (pixel.toLong() * (1 - blendFactor) + (currentFrame?.getInt() ?: 0).toLong() * blendFactor).toInt()
                        interpolatedFrame?.putInt(blendedPixel)
                    }
                }
            }
        }
    }

    private fun calculateFrameDifference(): Float {
        if (previousFrame == null || currentFrame == null) return 0f
        previousFrame?.rewind()
        currentFrame?.rewind()
        var diff = 0L
        val sampleSize = width * height / 16
        for (i in 0 until sampleSize) {
            val pos = i * 16 * 4
            previousFrame?.position(pos)
            currentFrame?.position(pos)
            diff += abs((previousFrame?.getInt() ?: 0) - (currentFrame?.getInt() ?: 0)).toLong()
        }
        return (diff / (sampleSize * 255f)).coerceAtMost(1f)
    }

    private fun renderInterpolatedFrameAsync() {
        executor.execute(PriorityRunnable(5) {
            interpolatedFrame?.rewind()
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, service.getRenderTextureId())
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, interpolatedFrame
            )
            handler.post {
                service.makeCurrent()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }
        })
    }

    private fun adaptiveQualityControl() {
        isCharging = service.isCharging()
        val currentFps = this.currentFps
        val targetFps = targetFrameRate.toFloat()
        qualityLevel = when {
            currentFps < targetFps * 0.8f -> 0
            currentFps < targetFps * 0.9f -> 1
            else -> 4
        }
        val fpsMultiplier = when (qualityLevel) {
            4 -> 2.0f
            1 -> 1.25f
            else -> 1.0f
        } * if (isCharging) 0.7f else 1.0f

        applyDynamicResolution(fpsMultiplier)
    }

    private fun applyDynamicResolution(fpsMultiplier: Float) {
        resolutionScale = when {
            fpsMultiplier > 1.5f -> 1.0f
            else -> 0.7f
        }.coerceIn(0.5f, 1.0f)
        val newWidth = (width * resolutionScale).toInt()
        val newHeight = (height * resolutionScale).toInt()
        if (newWidth != width || newHeight != height) {
            width = newWidth
            height = newHeight
            interpolatedFrame?.let { DirectBufferPool.release(it) }
            interpolatedFrame = DirectBufferPool.acquire(width * height * 4)
            motionVectorCache.clear()
            motionVectorCache.add(compressMotionVectors(Array(height / blockSize) { IntArray(width / blockSize * 2) }))
        }
        targetFrameRate = (targetFrameRate * fpsMultiplier).toInt().coerceAtMost(120)
    }

    private fun calculateFps(currentTime: Long) {
        if (lastFrameTime == 0L) {
            lastFrameTime = currentTime
            frameCount = 0
            capturedFrameCount = 0
            return
        }
        val elapsedTime = (currentTime - lastFrameTime) / 1_000_000_000f
        if (elapsedTime >= 0.5f) {
            val capturedFps = capturedFrameCount / elapsedTime
            val interpolatedFps = frameCount / elapsedTime
            currentFps = capturedFps + interpolatedFps
            frameCount = 0
            capturedFrameCount = 0
            lastFrameTime = currentTime
            service.updateFrameBoostPercentage(currentFps)
        }
    }

    private fun adjustBlockSizeAndSearchRangeForSOC() {
        when (service.getDevicePerformance()) {
            "高性能" -> {
                blockSize = 8
                searchRange = 16
                enableFP16Acceleration()
            }
            "中性能" -> {
                blockSize = 12
                searchRange = 12
            }
            "低性能" -> {
                blockSize = 16
                searchRange = 8
                enableQuantizedModel()
            }
        }
    }

    private fun enableFP16Acceleration() {
        Log.i(TAG, "启用FP16加速")
    }

    private fun enableQuantizedModel() {
        Log.i(TAG, "启用8-bit量化模型")
    }

    private fun adjustBlockSizeAndSearchRange() {
        val frameDiff = calculateFrameDifference()
        blockSize = when {
            frameDiff > 0.3f -> 16
            frameDiff > 0.15f -> 12
            else -> 8
        }.coerceAtLeast(4)
        searchRange = blockSize
        motionVectorCache.clear()
        motionVectorCache.add(compressMotionVectors(Array(height / blockSize) { IntArray(width / blockSize * 2) }))
    }

    private fun updateMotionVectorCache(vectors: Array<IntArray>) {
        if (motionVectorCache.size >= 3) motionVectorCache.removeFirst()
        motionVectorCache.add(compressMotionVectors(vectors))
    }

    private fun compressMotionVectors(vectors: Array<IntArray>): ByteArray {
        val byteBuffer = ByteBuffer.allocate(vectors.size * vectors[0].size * 4)
        for (y in vectors.indices) {
            for (x in vectors[y].indices) {
                byteBuffer.putInt(vectors[y][x])
            }
        }
        val deflater = Deflater()
        deflater.setInput(byteBuffer.array())
        deflater.finish()
        val compressed = ByteArray(byteBuffer.capacity())
        val size = deflater.deflate(compressed)
        return compressed.copyOf(size)
    }

    private fun decompressMotionVectors(compressed: ByteArray): Array<IntArray> {
        val inflater = Inflater()
        inflater.setInput(compressed)
        val decompressed = ByteArray(width * height * 4 / (blockSize * blockSize))
        val size = inflater.inflate(decompressed)
        val byteBuffer = ByteBuffer.wrap(decompressed, 0, size)
        val vectors = Array(height / blockSize) { IntArray(width / blockSize * 2) }
        for (y in vectors.indices) {
            for (x in vectors[y].indices) {
                vectors[y][x] = byteBuffer.getInt()
            }
        }
        return vectors
    }
}
