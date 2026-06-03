package com.aextoxicon.eureka_android.storage

import android.content.Context
import android.content.SharedPreferences
import com.aextoxicon.eureka_android.utils.LogManager

object PreferencesManager {
    private const val PREFS_NAME = "eureka_prefs"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"
    
    private lateinit var prefs: SharedPreferences
    
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        LogManager.log("PreferencesManager 初始化成功")
    }
    
    fun getUsername(): String {
        return prefs.getString(KEY_USERNAME, "") ?: ""
    }
    
    fun setUsername(username: String) {
        prefs.edit().putString(KEY_USERNAME, username).apply()
        LogManager.log("用户名已保存: $username")
    }
    
    fun getPassword(): String {
        return prefs.getString(KEY_PASSWORD, "") ?: ""
    }
    
    fun setPassword(password: String) {
        prefs.edit().putString(KEY_PASSWORD, password).apply()
        LogManager.log("密码已保存")
    }
    
    fun isConfigured(): Boolean {
        return getUsername().isNotEmpty()
    }
    
    fun clearCredentials() {
        prefs.edit().remove(KEY_USERNAME).remove(KEY_PASSWORD).apply()
        LogManager.log("凭据已清除")
    }
}
