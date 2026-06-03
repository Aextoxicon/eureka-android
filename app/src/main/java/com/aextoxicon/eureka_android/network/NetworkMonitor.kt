package com.aextoxicon.eureka_android.network

import com.aextoxicon.eureka_android.storage.PreferencesManager
import com.aextoxicon.eureka_android.utils.LogManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object NetworkMonitor {
    private const val TEST_URL = "http://www.msftconnecttest.com/connecttest.txt"
    private const val EXPECTED_BODY = "Microsoft Connect Test"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(1, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()
    
    fun isInternetAvailable(): Boolean {
        try {
            LogManager.logDebug("后台认证 - 网络检测开始")
            
            val request = Request.Builder()
                .url(TEST_URL)
                .header("Cache-Control", "no-cache")
                .build()
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()?.trim() ?: ""
            
            val result = response.code == 200 && responseBody == EXPECTED_BODY
            
            LogManager.logDebug("后台认证 - 网络检测结果: $result (状态码: ${response.code})")
            return result
        } catch (e: Exception) {
            LogManager.logWarning("后台认证 - 网络检测异常: ${e.message}")
            
            val username = PreferencesManager.getUsername()
            val password = PreferencesManager.getPassword()
            DrcomAuthenticator.login(username, password)
            
            return false
        }
    }
}
