package com.example.tfgy999

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build

object DeviceUtils {
    fun getTemperature(context: Context): Float {
        return getBatteryTemperature(context)
    }

    fun getGpuTemperature(context: Context): Float {
        // 由于无法直接获取 GPU 温度，使用电池温度作为近似值
        return getBatteryTemperature(context)
    }

    private fun getBatteryTemperature(context: Context): Float {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temperature = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        return temperature / 10f // 返回摄氏度，BatteryManager 返回值是以 1/10°C 为单位的整数
    }
}
