package com.aextoxicon.eureka_android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.aextoxicon.eureka_android.R
import com.aextoxicon.eureka_android.network.DrcomAuthenticator
import com.aextoxicon.eureka_android.network.NetworkMonitor
import com.aextoxicon.eureka_android.storage.PreferencesManager
import com.aextoxicon.eureka_android.utils.LogManager

class NetworkMonitorService : Service() {
    private val CHANNEL_ID = "eureka_service_channel"
    private val NOTIFICATION_ID = 1

    private val handler = Handler(Looper.getMainLooper())
    private var currentInterval = 500L
    private val maxInterval = 10000L
    private val fastInterval = 500L

    private var normalCount = 0
    private var reconnectCount = 0
    private var failCount = 0
    private var stableCount = 0
    private var consecutiveErrors = 0
    private val maxConsecutiveErrors = 3

    private var isRunning = false

    private val taskRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return

            try {
                val username = PreferencesManager.getUsername()
                val password = PreferencesManager.getPassword()

                if (username.isEmpty() || password.isEmpty()) {
                    LogManager.logWarning("后台任务 - 配置缺失")
                    broadcastStatus("配置缺失")
                    return
                }

                LogManager.logDebug("后台任务 - 开始网络检测")
                val netOk = NetworkMonitor.isInternetAvailable()
                LogManager.logDebug("后台任务 - 网络检测完成: $netOk")

                var ok = false
                if (netOk) {
                    normalCount++
                    stableCount++
                    LogManager.log("后台任务 - 网络正常，连续稳定 $stableCount 次")

                    if (stableCount > 5 && currentInterval < maxInterval) {
                        currentInterval = (currentInterval * 1.1).toLong()
                        if (currentInterval > maxInterval) {
                            currentInterval = maxInterval
                        }
                        LogManager.log("后台任务 - 网络稳定，延长检测间隔至 ${currentInterval}ms")
                    }
                } else {
                    LogManager.log("后台任务 - 网络异常，开始登录")
                    if (currentInterval != fastInterval) {
                        currentInterval = fastInterval
                        stableCount = 0
                        LogManager.log("后台任务 - 恢复快速检测模式 (间隔: ${currentInterval}ms)")
                    }

                    val loginResult = DrcomAuthenticator.login(username, password)
                    if (loginResult) {
                        reconnectCount++
                        ok = true
                    } else {
                        failCount++
                        ok = false
                    }
                }

                consecutiveErrors = 0
                val status = when {
                    netOk -> "网络正常"
                    ok -> "重连成功"
                    else -> "重连失败"
                }
                broadcastStatus(status)

                scheduleNextCheck()
            } catch (e: Exception) {
                consecutiveErrors++
                LogManager.logError("后台任务发生错误 ($consecutiveErrors/$maxConsecutiveErrors): ${e.message}", e)

                if (consecutiveErrors >= maxConsecutiveErrors) {
                    LogManager.logError("后台任务连续错误过多，自动停止服务")
                    broadcastStatus("服务异常停止")
                    stopSelf()
                    return
                }

                broadcastStatus("任务错误")
                currentInterval = fastInterval
                scheduleNextCheck()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        LogManager.log("服务 - 创建")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogManager.log("服务 - 启动")

        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        isRunning = true
        // 发送初始状态
        broadcastStatus("运行中")
        scheduleNextCheck()

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        LogManager.log("服务 - 销毁")
        isRunning = false
        handler.removeCallbacks(taskRunnable)
        broadcastStatus("服务已停止")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Eureka Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "用于保持校园网连接的后台服务"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Eureka")
            .setContentText("校园网监控服务运行中")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun scheduleNextCheck() {
        handler.removeCallbacks(taskRunnable)
        handler.postDelayed(taskRunnable, currentInterval)
    }

    private fun broadcastStatus(status: String) {
        LogManager.log("服务状态: $status")
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra("status", status)
            putExtra("normal", normalCount)
            putExtra("reconnect", reconnectCount)
            putExtra("fail", failCount)
            `package` = applicationContext.packageName
        }
        sendBroadcast(intent)
    }

    companion object {
        const val ACTION_START = "com.aextoxicon.eureka_android.ACTION_START"
        const val ACTION_STOP = "com.aextoxicon.eureka_android.ACTION_STOP"
        const val ACTION_STATUS_UPDATE = "com.aextoxicon.eureka_android.STATUS_UPDATE"
    }
}
