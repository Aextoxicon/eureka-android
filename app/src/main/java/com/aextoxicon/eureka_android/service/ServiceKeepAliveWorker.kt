package com.aextoxicon.eureka_android.service

import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aextoxicon.eureka_android.storage.PreferencesManager
import com.aextoxicon.eureka_android.utils.LogManager
import java.util.concurrent.TimeUnit

class ServiceKeepAliveWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!PreferencesManager.isConfigured()) {
            return Result.success()
        }

        if (!NetworkMonitorService.isServiceRunning) {
            LogManager.logWarning("WorkManager - 检测到服务未运行，尝试重启")
            val intent = Intent(applicationContext, NetworkMonitorService::class.java)
            applicationContext.startForegroundService(intent)
        }

        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "eureka_keep_alive"

        fun start(context: Context) {
            val request = PeriodicWorkRequestBuilder<ServiceKeepAliveWorker>(
                15, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            LogManager.log("WorkManager - 保活任务已注册（15分钟周期）")
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            LogManager.log("WorkManager - 保活任务已取消")
        }
    }
}
