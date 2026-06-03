package com.aextoxicon.eureka_android.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
    
    private var wifiNetwork: Network? = null
    private var context: Context? = null
    
    fun init(appContext: Context) {
        context = appContext.applicationContext
        requestWifiNetwork()
    }
    
    /**
     * 请求并绑定 WiFi 网络
     */
    private fun requestWifiNetwork() {
        val ctx = context ?: return
        val connectivityManager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        
        connectivityManager.requestNetwork(networkRequest, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                wifiNetwork = network
                // 将整个进程的网络绑定到 WiFi
                connectivityManager.bindProcessToNetwork(network)
                LogManager.logDebug("网络监控 - 已绑定 WiFi 网络")
            }
            
            override fun onLost(network: Network) {
                if (wifiNetwork == network) {
                    wifiNetwork = null
                    connectivityManager.bindProcessToNetwork(null)
                    LogManager.logWarning("网络监控 - WiFi 网络已断开")
                    // 重新请求
                    requestWifiNetwork()
                }
            }
        })
    }
    
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
