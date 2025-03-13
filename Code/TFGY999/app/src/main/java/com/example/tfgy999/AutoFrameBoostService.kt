package com.example.tfgy999

import android.annotation.SuppressLint
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.*
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.*

class AutoFrameBoostService : Service() {
    private val TAG = "AutoFrameBoostService"
    private var isRunning = AtomicBoolean(false)
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var targetFrameRate = 60
    private var frameWidth = 0
    private var frameHeight = 0
    private var screenRefreshRate = 0f
    private var wakeLock: PowerManager.WakeLock? = null

    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var captureSurfaceTexture: SurfaceTexture? = null
    private var renderSurfaceTexture: SurfaceTexture? = null
    private var programHandle = 0
    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var texCoordBuffer: FloatBuffer
    private var captureTextureId = 0
    private var renderTextureId = 0

    private lateinit var renderThread: HandlerThread
    private lateinit var renderHandler: Handler
    private var frameInterpolator: FrameInterpolator? = null
    private lateinit var mainHandler: Handler
    private var choreographerCallback: Choreographer.FrameCallback? = null
    private val executor = ThreadPoolExecutor(
        Runtime.getRuntime().availableProcessors(),
        Runtime.getRuntime().availableProcessors(),
        60L, TimeUnit.SECONDS,
        LinkedBlockingQueue<Runnable>()
    ).apply {
        setRejectedExecutionHandler { r: Runnable, executor: ThreadPoolExecutor ->
            (r as PriorityRunnable).priority += 2
            executor.execute(r)
        }
    }

    private var configChangeReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("opencv_java4")
        mainHandler = Handler(mainLooper)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenRefreshRate = getScreenRefreshRate()
        renderThread = HandlerThread("FrameBoostRenderThread", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        renderHandler = Handler(renderThread.looper)
        Log.i(TAG, "服务已创建，屏幕刷新率: $screenRefreshRate")

        configChangeReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_CONFIGURATION_CHANGED) {
                    adjustResolution()
                }
            }
        }
        registerReceiver(configChangeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        })
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            createNotificationChannel()
            val notificationIntent = Intent(this, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
            val notificationBuilder = NotificationCompat.Builder(this, "AutoFrameBoostChannel")
                .setContentTitle("正在插帧补帧")
                .setContentText("目标帧率提升至 ${targetFrameRate}FPS")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pendingIntent)
                .setOngoing(true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notificationBuilder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(1, notificationBuilder.build())
            }

            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoFrameBoost:WakeLock").apply {
                acquire(80 * 60 * 1000L)
            }

            isRunning.set(true)
            val originalFrameRate = intent?.getIntExtra("originalFrameRate", 60) ?: 60
            targetFrameRate = (originalFrameRate * 4).coerceAtMost(screenRefreshRate.toInt())
            val method = intent?.getStringExtra("interpolationMethod") ?: "高级运动矢量插值（动态场景，低延迟，高精度）"
            Log.i(TAG, "启动帧率提升服务，目标帧率=$targetFrameRate，方法=$method")

            val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
            val data = intent?.getParcelableExtra<Intent>("data")
            if (resultCode == Activity.RESULT_OK && data != null) {
                mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
                if (mediaProjection == null) {
                    Log.e(TAG, "无法获取 MediaProjection")
                    stopSelf()
                    return START_NOT_STICKY
                }
                Log.i(TAG, "已获取 MediaProjection")
                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.i(TAG, "MediaProjection 已停止")
                        stopSelf()
                    }
                }, null)
                val displayMetrics = resources.displayMetrics
                frameWidth = displayMetrics.widthPixels
                frameHeight = displayMetrics.heightPixels
                val densityDpi = displayMetrics.densityDpi

                renderHandler.post {
                    try {
                        initOpenGL()
                        setupTextures()
                        virtualDisplay = mediaProjection?.createVirtualDisplay(
                            "FrameBoost",
                            frameWidth, frameHeight, densityDpi,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                            Surface(captureSurfaceTexture), null, renderHandler
                        ) ?: throw RuntimeException("无法创建 VirtualDisplay")
                        Log.i(TAG, "屏幕捕获已设置，分辨率: $frameWidth x $frameHeight")

                        frameInterpolator = FrameInterpolator(targetFrameRate, renderHandler, this, method)
                        frameInterpolator?.startInterpolation()
                        startFrameRendering()
                        Log.i(TAG, "帧率提升已初始化，目标 FPS: $targetFrameRate，方法: $method")
                    } catch (e: Exception) {
                        Log.e(TAG, "渲染线程初始化失败: ${e.message}", e)
                        stopSelf()
                    }
                }
            } else {
                Log.w(TAG, "无效的 resultCode 或 data，停止服务")
                stopSelf()
                return START_NOT_STICKY
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "服务启动失败，权限不足: ${e.message}", e)
            // 通知用户重新授权
            mainHandler.post {
                val intent = Intent("com.example.tfgy999.PERMISSION_DENIED").apply {
                    putExtra("message", "屏幕捕获权限不足，请重新授权")
                }
                sendBroadcast(intent)
            }
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "服务启动失败: ${e.message}", e)
            stopSelf()
        }
        return START_STICKY
    }

    private fun adjustResolution() {
        Log.i(TAG, "使用原始分辨率: $frameWidth x $frameHeight")
        setupTextures()
    }

    private fun setupTextures() {
        renderHandler.post {
            if (!isRunning.get()) return@post
            try {
                makeCurrent()
                if (captureTextureId == 0) captureTextureId = createTextureId()
                if (renderTextureId == 0) renderTextureId = createTextureId()
                if (captureSurfaceTexture == null) {
                    captureSurfaceTexture = SurfaceTexture(captureTextureId).apply {
                        setOnFrameAvailableListener({
                            renderHandler.post {
                                if (isRunning.get()) {
                                    try {
                                        makeCurrent()
                                        captureSurfaceTexture?.updateTexImage()
                                        val buffer = captureFrameWithEGLImage()
                                        frameInterpolator?.processFrameBuffer(buffer, frameWidth, frameHeight)
                                        DirectBufferPool.release(buffer)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "帧处理错误: ${e.message}", e)
                                    }
                                }
                            }
                        }, renderHandler)
                    }
                }
                if (renderSurfaceTexture == null) {
                    renderSurfaceTexture = SurfaceTexture(renderTextureId)
                }
                captureSurfaceTexture?.setDefaultBufferSize(frameWidth, frameHeight)
                renderSurfaceTexture?.setDefaultBufferSize(frameWidth, frameHeight)
            } catch (e: Exception) {
                Log.e(TAG, "设置纹理失败: ${e.message}", e)
            }
        }
    }

    private fun captureFrameWithEGLImage(): ByteBuffer {
        val buffer = DirectBufferPool.acquire(frameWidth * frameHeight * 4)
        for (i in 0 until frameHeight step 64) {
            val h = minOf(64, frameHeight - i)
            GLES20.glReadPixels(0, i, frameWidth, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer.position(frameWidth * i * 4))
        }
        buffer.rewind()
        return buffer
    }

    private fun initOpenGL() {
        val egl = EGLContext.getEGL() as EGL10
        eglDisplay = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL10.EGL_NO_DISPLAY) throw RuntimeException("无法获取 EGL 显示")
        if (!egl.eglInitialize(eglDisplay, intArrayOf(0, 1))) throw RuntimeException("EGL 初始化失败")

        val configAttributes = intArrayOf(
            EGL10.EGL_RED_SIZE, 8,
            EGL10.EGL_GREEN_SIZE, 8,
            EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_ALPHA_SIZE, 8,
            EGL10.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT
            EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
            EGL10.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!egl.eglChooseConfig(eglDisplay, configAttributes, configs, 1, numConfigs) || numConfigs[0] == 0) {
            throw RuntimeException("未找到匹配的 EGL 配置")
        }
        val config = configs[0]!!

        val pbufferAttribs = intArrayOf(
            EGL10.EGL_WIDTH, 1,
            EGL10.EGL_HEIGHT, 1,
            EGL10.EGL_NONE
        )
        val pbufferSurface = egl.eglCreatePbufferSurface(eglDisplay, config, pbufferAttribs)
        if (pbufferSurface == EGL10.EGL_NO_SURFACE) throw RuntimeException("无法创建 Pbuffer 表面")

        eglContext = egl.eglCreateContext(eglDisplay, config, EGL10.EGL_NO_CONTEXT, intArrayOf(0x3098, 2, EGL10.EGL_NONE))
        if (eglContext == EGL10.EGL_NO_CONTEXT) throw RuntimeException("无法创建 EGL 上下文")

        if (!egl.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent 失败")
        }

        captureTextureId = createTextureId()
        renderTextureId = createTextureId()
        captureSurfaceTexture = SurfaceTexture(captureTextureId)
        renderSurfaceTexture = SurfaceTexture(renderTextureId)

        eglSurface = egl.eglCreateWindowSurface(eglDisplay, config, renderSurfaceTexture, null)
        if (eglSurface == EGL10.EGL_NO_SURFACE) throw RuntimeException("无法创建 EGL 表面")

        makeCurrent()

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        programHandle = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) throw RuntimeException("程序链接失败")
        }

        val vertices = floatArrayOf(-1f, -1f, 0f, 1f, -1f, 0f, -1f, 1f, 0f, 1f, 1f, 0f)
        val texCoords = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(vertices)
            position(0)
        }
        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(texCoords)
            position(0)
        }
    }

    fun makeCurrent() {
        val egl = EGLContext.getEGL() as EGL10
        if (eglDisplay == null || eglSurface == null || eglContext == null) {
            Log.e(TAG, "EGL 资源未初始化，无法调用 eglMakeCurrent")
            return
        }
        if (!egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent 失败，错误码: ${egl.eglGetError()}")
        }
    }

    private val vertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES uTexture;
        void main() {
            gl_FragColor = texture2D(uTexture, vTexCoord);
        }
    """.trimIndent()

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, shaderCode)
            GLES20.glCompileShader(it)
            val status = IntArray(1)
            GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) throw RuntimeException("着色器编译失败")
        }
    }

    private fun startFrameRendering() {
        renderHandler.post {
            if (!isRunning.get()) return@post
            try {
                makeCurrent()
                GLES20.glUseProgram(programHandle)
                val positionHandle = GLES20.glGetAttribLocation(programHandle, "aPosition")
                val texCoordHandle = GLES20.glGetAttribLocation(programHandle, "aTexCoord")
                val textureHandle = GLES20.glGetUniformLocation(programHandle, "uTexture")
                GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
                GLES20.glEnableVertexAttribArray(positionHandle)
                GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
                GLES20.glEnableVertexAttribArray(texCoordHandle)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, renderTextureId)
                GLES20.glUniform1i(textureHandle, 0)
            } catch (e: Exception) {
                Log.e(TAG, "渲染初始化失败: ${e.message}", e)
            }
        }

        choreographerCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (isRunning.get()) {
                    renderHandler.post {
                        try {
                            makeCurrent()
                            renderFrame()
                        } catch (e: Exception) {
                            Log.e(TAG, "帧渲染循环错误: ${e.message}", e)
                        }
                    }
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
        }
        Choreographer.getInstance().postFrameCallback(choreographerCallback!!)
    }

    private fun renderFrame() {
        if (captureSurfaceTexture != null && captureSurfaceTexture!!.isAvailable()) {
            captureSurfaceTexture!!.updateTexImage()
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, captureTextureId)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            val egl = EGLContext.getEGL() as EGL10
            if (!egl.eglSwapBuffers(eglDisplay, eglSurface)) {
                Log.e(TAG, "eglSwapBuffers 失败，错误码: ${egl.eglGetError()}")
            }
        }
    }

    private fun createTextureId(): Int {
        val textureIds = IntArray(1)
        GLES20.glGenTextures(1, textureIds, 0)
        val texId = textureIds[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        return texId
    }

    fun getDevicePerformance(): String {
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val maxFreq = getMaxCpuFreq()
        return when {
            cpuCores >= 8 && maxFreq > 2_500_000 -> "高性能"
            cpuCores >= 6 && maxFreq > 1_800_000 -> "中性能"
            else -> "低性能"
        }
    }

    private fun getMaxCpuFreq(): Long {
        return try {
            val freq = java.io.File("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
            freq * 1000
        } catch (e: Exception) {
            2_000_000L
        }
    }

    fun isCharging(): Boolean {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let {
            registerReceiver(null, it)
        }
        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    fun adjustGPUFrequency(deltaMHz: Int) {
        Log.i(TAG, "调整GPU频率: $deltaMHz MHz (模拟实现，需适配SoC DVFS表)")
    }

    fun getRenderTextureId(): Int = renderTextureId

    fun updateFrameBoostPercentage(fps: Float) {
        if (!isRunning.get()) return
        mainHandler.post {
            try {
                val intent = Intent("com.example.tfgy999.FPS_UPDATE").apply {
                    putExtra("fps", fps)
                }
                sendBroadcast(intent)
            } catch (e: Exception) {
                Log.e(TAG, "发送 FPS 更新失败: ${e.message}", e)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "AutoFrameBoostChannel",
                "自动补帧服务",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { setSound(null, null) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    @SuppressLint("ServiceCast")
    private fun getScreenRefreshRate(): Float {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        return windowManager.defaultDisplay.refreshRate.coerceAtLeast(60f)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning.set(false)
        frameInterpolator?.stopInterpolation()
        if (choreographerCallback != null) {
            Choreographer.getInstance().removeFrameCallback(choreographerCallback)
            choreographerCallback = null
        }
        virtualDisplay?.release()
        mediaProjection?.stop()
        wakeLock?.release()
        executor.shutdown()
        DirectBufferPool.checkLeaks()

        renderHandler.post {
            try {
                if (eglDisplay != null && eglSurface != null && eglContext != null) {
                    val egl = EGLContext.getEGL() as EGL10
                    egl.eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
                    egl.eglDestroySurface(eglDisplay, eglSurface)
                    egl.eglDestroyContext(eglDisplay, eglContext)
                    egl.eglTerminate(eglDisplay)
                }
                GLES20.glDeleteTextures(1, intArrayOf(captureTextureId), 0)
                GLES20.glDeleteTextures(1, intArrayOf(renderTextureId), 0)
                GLES20.glDeleteProgram(programHandle)
            } catch (e: Exception) {
                Log.e(TAG, "资源释放失败: ${e.message}", e)
            } finally {
                captureSurfaceTexture?.release()
                renderSurfaceTexture?.release()
                captureSurfaceTexture = null
                renderSurfaceTexture = null
                eglDisplay = null
                eglSurface = null
                eglContext = null
            }
        }
        renderThread.quitSafely()

        configChangeReceiver?.let {
            unregisterReceiver(it)
            configChangeReceiver = null
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val stopNotification = NotificationCompat.Builder(this, "AutoFrameBoostChannel")
            .setContentTitle("插帧补帧已关闭")
            .setContentText("服务已停止或被系统终止")
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        notificationManager.notify(2, stopNotification)
    }

    private fun SurfaceTexture.isAvailable(): Boolean {
        return try {
            this.timestamp
            true
        } catch (e: IllegalStateException) {
            false
        }
    }
}
