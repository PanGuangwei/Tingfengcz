package com.example.tfgy999

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.VideoView
import androidx.activity.ComponentActivity
import java.text.SimpleDateFormat
import java.util.*

class TestActivity : ComponentActivity() {
    private val TAG = "TestActivity"
    private val handler = Handler(Looper.getMainLooper())
    private var totalFrames = 0
    private var addedFrames = 0
    private var droppedFrames = 0
    private var originalFps = 0f
    private var lastFps = 0f
    private var videoDurationMs = 0L
    private var startTime = 0L
    private var isFrameBoostRunning = false
    private var interpolationMethod = "未开启补帧"
    private var fpsList = mutableListOf<Float>()
    private lateinit var fpsReceiver: BroadcastReceiver
    private var isReceiverRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val videoView = VideoView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(videoView)

        isFrameBoostRunning = isServiceRunning()
        interpolationMethod = intent.getStringExtra("interpolationMethod") ?: "未开启补帧"

        setupFpsReceiver()

        try {
            val videoPath = "android.resource://${packageName}/raw/test_video"
            Log.i(TAG, "正在加载视频路径: $videoPath")
            videoView.setVideoPath(videoPath)

            videoView.setOnPreparedListener { mp ->
                startTime = System.currentTimeMillis()
                mp.isLooping = false
                mp.start()

                originalFps = mp.videoFrameRate.takeIf { it > 0 } ?: 30f
                videoDurationMs = mp.duration.toLong()
                totalFrames = (originalFps * videoDurationMs / 1000).toInt()

                handler.postDelayed({ finishWithSummary() }, 30_000)
            }

            videoView.setOnErrorListener { _, what, extra ->
                finishWithSummary()
                true
            }

            videoView.setOnCompletionListener {
                finishWithSummary()
            }
        } catch (e: Exception) {
            finishWithSummary()
        }
    }

    private fun isServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Integer.MAX_VALUE)
            .any { it.service.className == AutoFrameBoostService::class.java.name }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun setupFpsReceiver() {
        fpsReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val currentFps = intent?.getFloatExtra("fps", 0f) ?: 0f
                fpsList.add(currentFps)
                if (currentFps != lastFps) {
                    if (isFrameBoostRunning) {
                        val added = (currentFps - originalFps).coerceAtLeast(0f).toInt()
                        val dropped = (originalFps - currentFps).coerceAtLeast(0f).toInt()
                        addedFrames += added
                        droppedFrames += dropped
                    } else {
                        val dropped = (originalFps - currentFps).coerceAtLeast(0f).toInt()
                        droppedFrames += dropped
                    }
                    lastFps = currentFps
                }
            }
        }
        val filter = IntentFilter("com.example.tfgy999.FPS_UPDATE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(fpsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(fpsReceiver, filter)
        }
        isReceiverRegistered = true
    }

    private fun finishWithSummary() {
        val durationSeconds = (System.currentTimeMillis() - startTime) / 1000f
        val averageFps = if (fpsList.isNotEmpty()) fpsList.average().toFloat() else originalFps
        val summary = buildString {
            append("测试完成！\n")
            append("视频时长: ${String.format("%.1f", durationSeconds)} 秒\n")
            append("原视频 FPS: ${String.format("%.1f", originalFps)}\n")
            append("平均 FPS: ${String.format("%.1f", averageFps)}\n")
            append("补帧模式: $interpolationMethod\n")
            append("总帧数: $totalFrames 帧\n")
            append("补帧数: $addedFrames 帧\n")
            append("丢帧数: $droppedFrames 帧\n")
            append("插帧数: ${if (isFrameBoostRunning) addedFrames else 0} 帧\n")
        }

        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("补帧测试总结")
                .setMessage(summary)
                .setPositiveButton("确定") { _, _ ->
                    cleanupAndReturn()
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun cleanupAndReturn() {
        handler.removeCallbacksAndMessages(null)
        if (isReceiverRegistered) {
            unregisterReceiver(fpsReceiver)
            isReceiverRegistered = false
        }
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) cleanupAndReturn()
    }
}

val android.media.MediaPlayer.videoFrameRate: Float
    get() = 30f // 请根据实际视频库调整