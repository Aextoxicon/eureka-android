package com.aextoxicon.eureka_android.utils

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogManager {
    private const val TAG = "Eureka"
    private const val MAX_LOGS = 100
    private const val MAX_FILE_SIZE = 1 * 1024 * 1024L // 1MB

    private val logs = mutableListOf<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val fullDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private var listener: (() -> Unit)? = null
    private var logFile: File? = null

    fun init(context: Context) {
        logFile = File(context.filesDir, "eureka_logs.txt")
        loadLogsFromFile()
    }

    fun setListener(listener: (() -> Unit)?) {
        this.listener = listener
    }

    fun getLogs(): List<String> = logs.toList()

    fun getLatestLog(): String {
        return if (logs.isEmpty()) "" else logs.last()
    }

    fun log(message: String) {
        logMessage(message, LogLevel.INFO)
    }

    fun logError(message: String, exception: Exception? = null) {
        logMessage("[ERROR] $message", LogLevel.ERROR)
        exception?.printStackTrace()
    }

    fun logWarning(message: String) {
        logMessage("[WARNING] $message", LogLevel.WARNING)
    }

    fun logDebug(message: String) {
        logMessage("[DEBUG] $message", LogLevel.DEBUG)
    }

    private fun logMessage(message: String, level: LogLevel) {
        val timestamp = fullDateFormat.format(Date())
        val logMessage = "[$timestamp] $message"

        // 检查文件大小，超过 1MB 则清空重写
        logFile?.let { file ->
            if (file.exists() && file.length() >= MAX_FILE_SIZE) {
                file.writeText("")
            }
        }

        // 写入文件
        logFile?.let { file ->
            try {
                FileWriter(file, true).use { writer ->
                    writer.appendLine(logMessage)
                }
            } catch (e: Exception) {
                Log.e(TAG, "写入日志文件失败: ${e.message}")
            }
        }

        // 内存缓存
        logs.add(logMessage)
        if (logs.size > MAX_LOGS) {
            logs.removeAt(0)
        }

        when (level) {
            LogLevel.ERROR -> Log.e(TAG, message)
            LogLevel.WARNING -> Log.w(TAG, message)
            LogLevel.DEBUG -> Log.d(TAG, message)
            else -> Log.i(TAG, message)
        }

        listener?.invoke()
    }

    private fun loadLogsFromFile() {
        logFile?.let { file ->
            if (file.exists()) {
                try {
                    BufferedReader(FileReader(file)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            logs.add(line!!)
                        }
                    }
                    // 只保留最后 MAX_LOGS 条
                    while (logs.size > MAX_LOGS) {
                        logs.removeAt(0)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "读取日志文件失败: ${e.message}")
                }
            }
        }
    }

    fun clearLogs() {
        logs.clear()
        logFile?.let { file ->
            try {
                file.writeText("")
            } catch (e: Exception) {
                Log.e(TAG, "清空日志文件失败: ${e.message}")
            }
        }
        listener?.invoke()
    }

    enum class LogLevel {
        DEBUG, INFO, WARNING, ERROR
    }
}
