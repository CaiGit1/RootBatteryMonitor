package com.caigit1.rootbattery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BatteryMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository = BatteryMonitorRepository()

    override fun onCreate() {
        super.onCreate()
        createChannel()
        try {
            startForeground(NOTIFICATION_ID, baseNotification("正在读取电池信息"))
        } catch (t: Throwable) {
            // 缺 FOREGROUND_SERVICE_DATA_SYNC 权限等情况下会抛 SecurityException。
            // 不要让它直接打崩进程：停掉自己并把原因写进 logcat。
            Log.e(TAG, "startForeground 失败，服务退出", t)
            stopSelf()
            return
        }

        serviceScope.launch {
            while (true) {
                val text = when (val result = repository.refreshOnce()) {
                    is MonitorResult.Success -> {
                        val level = result.snapshot.levelPercent?.let { "$it%" } ?: "--"
                        val temp = result.snapshot.temperatureText
                        "电量: $level  温度: $temp"
                    }

                    is MonitorResult.Error -> "读取异常: ${result.error.message}"
                }
                notify(text)
                delay(5000)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun notify(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, baseNotification(text))
    }

    private fun baseNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Root Battery Monitor")
            .setContentText(content)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Battery Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "BatteryMonitorService"
        private const val CHANNEL_ID = "root_battery_monitor"
        private const val NOTIFICATION_ID = 20261004
    }
}
