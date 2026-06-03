package com.aextoxicon.eureka_android.network

import com.aextoxicon.eureka_android.utils.LogManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object DrcomAuthenticator {
    private const val LOGIN_URL = "http://192.168.110.100/drcom/login"
    private const val ALTERNATIVE_URL = "http://192.168.31.58:50000/drcom/login"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()
    
    fun login(username: String, password: String): Boolean {
        LogManager.log("后台认证 - 尝试登录: $username")
        
        try {
            val url = "$LOGIN_URL?callback=dr1003&DDDDD=$username&upass=$password&0MKKey=123456&R1=0&R3=0&R6=0&para=00&v6ip=&v=3196"
            
            LogManager.logDebug("后台认证 - 请求 URL: $url")
            
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "curl/7.88.1")
                .header("Accept", "*/*")
                .header("Connection", "close")
                .build()
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            
            LogManager.logDebug("后台认证 - 响应状态: ${response.code}, 内容: $responseBody")
            
            val result = response.code == 200 && 
                (responseBody.contains("\"result\":1") || responseBody.contains("dr1003({\"result\":1}"))
            
            if (result) {
                LogManager.log("后台认证 - 登录成功")
            } else {
                LogManager.logWarning("后台认证 - 登录失败: HTTP ${response.code}, 响应: ${responseBody.take(200)}")
            }
            
            return result
        } catch (e: Exception) {
            val errorMsg = e.message ?: e.javaClass.simpleName
            LogManager.logError("后台认证 - 登录异常: $errorMsg", e)
            return false
        }
    }
}
