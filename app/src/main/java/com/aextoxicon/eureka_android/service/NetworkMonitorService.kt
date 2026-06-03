package com.aextoxicon.eureka_android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.aextoxicon.eureka_android.MainActivity
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
        registerNotificationActionReceiver()
        NetworkMonitor.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogManager.log("服务 - 启动")

        // 处理通知按钮点击
        when (intent?.action) {
            ACTION_EXIT -> {
                LogManager.log("通知 - 退出应用")
                stopSelf()
                // 发送广播通知 MainActivity 退出
                sendBroadcast(Intent(ACTION_EXIT).apply { `package` = applicationContext.packageName })
                return START_NOT_STICKY
            }
            ACTION_FORCE_ACTIVATE -> {
                LogManager.log("通知 - 强制激活")
                val username = PreferencesManager.getUsername()
                val password = PreferencesManager.getPassword()
                if (username.isNotEmpty() && password.isNotEmpty()) {
                    Thread {
                        val result = DrcomAuthenticator.login(username, password)
                        handler.post {
                            broadcastStatus(if (result) "强制激活成功" else "强制激活失败")
                        }
                    }.start()
                }
                return START_STICKY
            }
        }

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
        unregisterReceiver(notificationActionReceiver)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ========================================================================
    // 通知按钮广播接收器
    // ========================================================================

    private val notificationActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_EXIT -> {
                    LogManager.log("广播 - 收到退出指令")
                    stopSelf()
                }
                ACTION_FORCE_ACTIVATE -> {
                    LogManager.log("广播 - 收到强制激活指令")
                    val username = PreferencesManager.getUsername()
                    val password = PreferencesManager.getPassword()
                    if (username.isNotEmpty() && password.isNotEmpty()) {
                        Thread {
                            val result = DrcomAuthenticator.login(username, password)
                            handler.post {
                                broadcastStatus(if (result) "强制激活成功" else "强制激活失败")
                            }
                        }.start()
                    }
                }
            }
        }
    }

    private fun registerNotificationActionReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_EXIT)
            addAction(ACTION_FORCE_ACTIVATE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(notificationActionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(notificationActionReceiver, filter)
        }
    }

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
        // 打开主应用的 PendingIntent
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 强制激活按钮的 PendingIntent
        val forceActivateIntent = Intent(this, NetworkMonitorService::class.java).apply {
            action = ACTION_FORCE_ACTIVATE
        }
        val forceActivatePendingIntent = PendingIntent.getService(
            this,
            1,
            forceActivateIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 退出应用按钮的 PendingIntent
        val exitIntent = Intent(this, NetworkMonitorService::class.java).apply {
            action = ACTION_EXIT
        }
        val exitPendingIntent = PendingIntent.getService(
            this,
            2,
            exitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Eureka")
            .setContentText("校园网监控服务运行中")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                R.drawable.ic_launcher_foreground,
                "强制激活",
                forceActivatePendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "退出",
                exitPendingIntent
            )
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
        const val ACTION_EXIT = "com.aextoxicon.eureka_android.ACTION_EXIT"
        const val ACTION_FORCE_ACTIVATE = "com.aextoxicon.eureka_android.ACTION_FORCE_ACTIVATE"
    }
}
