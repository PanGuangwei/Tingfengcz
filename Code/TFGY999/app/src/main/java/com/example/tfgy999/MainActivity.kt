package com.example.tfgy999

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private val MEDIA_PROJECTION_REQUEST_CODE = 1001
    private var selectedFrameRate = 120
    private var selectedMethod = "高级运动矢量插值（动态场景，低延迟，高精度）"

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setContent {
            FrameBoostScreen()
        }

        // 注册广播接收器以监听 FPS 更新
        val intentFilter = IntentFilter("com.example.tfgy999.FPS_UPDATE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerReceiver(fpsUpdateReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(fpsUpdateReceiver, intentFilter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(fpsUpdateReceiver)
    }

    private val fpsUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getFloatExtra("fps", 0f)?.let { fps ->
                // 这里需要在 Composable 中更新 currentFps
            }
        }
    }

    @Composable
    fun FrameBoostScreen() {
        var isServiceRunning by remember { mutableStateOf(isServiceRunning()) }
        var frameRateExpanded by remember { mutableStateOf(false) }
        var methodExpanded by remember { mutableStateOf(false) }
        var statusText by remember { mutableStateOf(if (isServiceRunning) "服务运行中" else "服务未运行") }
        var currentFps by remember { mutableStateOf(0f) }
        var cpuTemp by remember { mutableStateOf(0f) }
        var gpuTemp by remember { mutableStateOf(0f) }
        val frameRateOptions = listOf(60, 90, 120, 144)
        val interpolationMethods = listOf(
            "高级运动矢量插值（动态场景，低延迟，高精度）",
            "光流辅助插值（适用于快速运动场景）",
            "简单帧混合（低功耗，低端设备适用）"
        )
        val scope = rememberCoroutineScope()

        // 定时更新温度
        LaunchedEffect(Unit) {
            while (true) {
                if (isServiceRunning) {
                    cpuTemp = DeviceUtils.getTemperature(this@MainActivity)
                    gpuTemp = DeviceUtils.getGpuTemperature(this@MainActivity)
                }
                kotlinx.coroutines.delay(1000) // 每秒更新一次
            }
        }

        // 更新 FPS 的广播接收器逻辑
        LaunchedEffect(Unit) {
            snapshotFlow { currentFps }.collect { /* 这里可以添加额外的 FPS 更新逻辑 */ }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "听风补帧插帧（支持全游戏）",
                fontSize = 24.sp,
                color = Color.Black
            )
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "状态: $statusText",
                fontSize = 16.sp,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "当前帧率: ${"%.1f".format(currentFps)} FPS",
                fontSize = 16.sp,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "CPU 温度: ${"%.1f".format(cpuTemp)} ℃ | GPU 温度: ${"%.1f".format(gpuTemp)} ℃",
                fontSize = 16.sp,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(16.dp))

            Box {
                Button(
                    onClick = { frameRateExpanded = true },
                    modifier = Modifier.fillMaxWidth(0.8f)
                ) {
                    Text("目标帧率: $selectedFrameRate FPS")
                }
                DropdownMenu(
                    expanded = frameRateExpanded,
                    onDismissRequest = { frameRateExpanded = false }
                ) {
                    frameRateOptions.forEach { rate ->
                        DropdownMenuItem(
                            text = { Text("$rate FPS") },
                            onClick = {
                                selectedFrameRate = rate
                                frameRateExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Box {
                Button(
                    onClick = { methodExpanded = true },
                    modifier = Modifier.fillMaxWidth(0.8f)
                ) {
                    Text("插帧方法: $selectedMethod")
                }
                DropdownMenu(
                    expanded = methodExpanded,
                    onDismissRequest = { methodExpanded = false }
                ) {
                    interpolationMethods.forEach { method ->
                        DropdownMenuItem(
                            text = { Text(method) },
                            onClick = {
                                selectedMethod = method
                                methodExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    if (!isServiceRunning) {
                        scope.launch {
                            startFrameBoostService(selectedFrameRate, selectedMethod)
                            isServiceRunning = true
                            statusText = "服务运行中: $selectedMethod"
                        }
                    } else {
                        stopFrameBoostService()
                        isServiceRunning = false
                        statusText = "服务已停止"
                        currentFps = 0f
                    }
                },
                modifier = Modifier.fillMaxWidth(0.8f)
            ) {
                Text(
                    text = if (isServiceRunning) "停止补帧" else "启动补帧",
                    fontSize = 18.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    val intent = Intent(this@MainActivity, TestActivity::class.java).apply {
                        putExtra("targetFrameRate", selectedFrameRate)
                        putExtra("interpolationMethod", selectedMethod)
                    }
                    startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(0.8f)
            ) {
                Text("测试补帧插帧效果", fontSize = 18.sp)
            }
        }
    }

    private fun isServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Integer.MAX_VALUE)
            .any { it.service.className == AutoFrameBoostService::class.java.name }
    }

    private fun requestScreenCapture() {
        startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(),
            MEDIA_PROJECTION_REQUEST_CODE
        )
    }

    private suspend fun startFrameBoostService(targetFrameRate: Int, method: String) {
        this.selectedFrameRate = targetFrameRate
        this.selectedMethod = method
        requestScreenCapture()
    }

    private fun stopFrameBoostService() {
        stopService(Intent(this, AutoFrameBoostService::class.java))
        Toast.makeText(this, "补帧服务已停止", Toast.LENGTH_SHORT).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == MEDIA_PROJECTION_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val serviceIntent = Intent(this, AutoFrameBoostService::class.java).apply {
                    putExtra("resultCode", resultCode)
                    putExtra("data", data)
                    putExtra("originalFrameRate", selectedFrameRate)
                    putExtra("interpolationMethod", selectedMethod)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                Toast.makeText(this, "补帧服务已启动", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "屏幕捕获权限被拒绝", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
