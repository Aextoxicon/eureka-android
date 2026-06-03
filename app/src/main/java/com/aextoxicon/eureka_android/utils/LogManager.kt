package com.aextoxicon.eureka_android.utils

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogManager {
    private const val TAG = "Eureka"
    private const val MAX_LOGS = 100
    private val logs = mutableListOf<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    
    private var listener: (() -> Unit)? = null
    
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
        val timestamp = dateFormat.format(Date())
        val logMessage = "[$timestamp] $message"
        
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
    
    fun clearLogs() {
        logs.clear()
        listener?.invoke()
    }
    
    enum class LogLevel {
        DEBUG, INFO, WARNING, ERROR
    }
}
