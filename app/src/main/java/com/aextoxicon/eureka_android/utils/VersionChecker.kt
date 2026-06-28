package com.aextoxicon.eureka_android.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object VersionChecker {
    private const val GITHUB_API_URL = "https://api.github.com/repos/Aextoxicon/eureka-android/releases/latest"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    
    data class VersionInfo(
        val version: String,
        val downloadUrl: String
    )
    
    fun checkForUpdates(context: Context, callback: (Result<VersionInfo?>) -> Unit) {
        LogManager.log("版本检查 - 开始抓取远程版本信息")
        
        try {
            val localVersion = getLocalVersion(context)
            LogManager.log("版本检查 - 本地版本: $localVersion")
            
            val request = Request.Builder()
                .url(GITHUB_API_URL)
                .header("User-Agent", "Eureka-android")
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.code != 200) {
                LogManager.logError("版本检查 - GitHub API请求失败: ${response.code}")
                callback(Result.failure(Exception("GitHub API请求失败: ${response.code}")))
                return
            }
            
            val responseBody = response.body?.string() ?: ""
            val jsonResponse = JSONObject(responseBody)
            
            // 打印原始 JSON 到日志，方便调试
            LogManager.log("版本检查 - API 原始返回: $responseBody")
            
            val tagName = jsonResponse.optString("tag_name", "")
            if (tagName.isEmpty()) {
                LogManager.logError("版本检查 - 远程版本标签为空")
                callback(Result.failure(Exception("远程版本标签为空")))
                return
            }
            
            val remoteVersion = tagName.replace(Regex("^v"), "")
            
            val assets = jsonResponse.getJSONArray("assets")
            var apkDownloadUrl: String? = null
            
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name") == "app-release.apk") {
                    val originalUrl = asset.getString("browser_download_url")
                    apkDownloadUrl = "https://gh-proxy.com/$originalUrl"
                    break
                }
            }
            
            if (apkDownloadUrl == null) {
                LogManager.logWarning("版本检查 - 未找到app-release.apk文件")
                callback(Result.failure(Exception("未找到可用的APK文件")))
                return
            }
            
            LogManager.log("版本检查 - 远程版本: $remoteVersion, 本地版本: $localVersion")
            
            val comparison = compareVersions(remoteVersion, localVersion)
            
            if (comparison <= 0) {
                LogManager.log("版本检查 - 当前已是最新版本")
                callback(Result.success(null))
            } else {
                LogManager.log("版本检查 - 有新版本可用: $remoteVersion")
                callback(Result.success(VersionInfo(remoteVersion, apkDownloadUrl)))
            }
        } catch (e: Exception) {
            LogManager.logError("版本检查 - 抓取远程版本异常: ${e.message}", e)
            callback(Result.failure(e))
        }
    }
    
    fun openDownloadUrl(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            LogManager.log("已在浏览器中打开下载链接")
        } catch (e: Exception) {
            LogManager.logError("打开下载链接失败: ${e.message}", e)
        }
    }
    
    private fun getLocalVersion(context: Context): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            LogManager.logError("获取本地版本号失败: ${e.message}", e)
            "1.0"
        }
    }

    private fun compareVersions(remote: String, local: String): Int {
        val remoteParts = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val localParts = local.split(".").map { it.toIntOrNull() ?: 0 }
        
        val maxLength = maxOf(remoteParts.size, localParts.size)
        
        for (i in 0 until maxLength) {
            val remotePart = remoteParts.getOrElse(i) { 0 }
            val localPart = localParts.getOrElse(i) { 0 }
            
            when {
                remotePart > localPart -> return 1
                remotePart < localPart -> return -1
            }
        }
        
        return 0
    }
}
