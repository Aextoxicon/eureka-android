package com.aextoxicon.eureka_android

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aextoxicon.eureka_android.network.DrcomAuthenticator
import com.aextoxicon.eureka_android.service.NetworkMonitorService
import com.aextoxicon.eureka_android.storage.PreferencesManager
import com.aextoxicon.eureka_android.ui.theme.EurekaandroidTheme
import com.aextoxicon.eureka_android.utils.LogManager
import com.aextoxicon.eureka_android.utils.VersionChecker

class MainActivity : ComponentActivity() {

    // --- 权限请求 Launcher ---
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            LogManager.logWarning("未获得所有必要权限，可能影响后台服务运行")
        }
    }

    // --- Compose 可观察状态 ---
    private val _showBatteryOptDialog = mutableStateOf(false)
    private val _serviceStatus = mutableStateOf("准备就绪")
    private val _normalCount = mutableStateOf(0)
    private val _reconnectCount = mutableStateOf(0)
    private val _failCount = mutableStateOf(0)

    // --- 版本更新信息（用于确认弹窗） ---
    private var pendingUpdateUrl: String? = null
    private var pendingUpdateVersion: String? = null
    private val _showUpdateDialog = mutableStateOf(false)

    // --- 退出确认弹窗 ---
    private val _showExitDialog = mutableStateOf(false)

    // --- 服务状态广播接收器 ---
    private val serviceStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == NetworkMonitorService.ACTION_STATUS_UPDATE) {
                _serviceStatus.value = intent.getStringExtra("status") ?: "运行中"
                _normalCount.value = intent.getIntExtra("normal", 0)
                _reconnectCount.value = intent.getIntExtra("reconnect", 0)
                _failCount.value = intent.getIntExtra("fail", 0)
            } else if (intent?.action == NetworkMonitorService.ACTION_EXIT) {
                // 收到通知按钮的退出指令
                LogManager.log("MainActivity - 收到退出指令")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                    finishAffinity()
                } else {
                    finish()
                }
            }
        }
    }

    // ========================================================================
    // 生命周期
    // ========================================================================

    @SuppressLint("UnsafeProtectedBroadcastReceiver", "UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        PreferencesManager.init(applicationContext)
        LogManager.init(applicationContext)
        LogManager.setListener { }

        // 注册服务状态广播（内部通信，不对外暴露）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // API 33+
            registerReceiver(
                serviceStatusReceiver,
                IntentFilter(NetworkMonitorService.ACTION_STATUS_UPDATE),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(serviceStatusReceiver, IntentFilter(NetworkMonitorService.ACTION_STATUS_UPDATE))
        }

        requestPermissions()

        // 自动启动服务（如果已配置）
        if (PreferencesManager.isConfigured()) {
            startService()
            _serviceStatus.value = "启动中..."
        }

        setContent {
            EurekaandroidTheme {
                MainScreen(
                    showBatteryOptDialog = _showBatteryOptDialog.value,
                    onDismissBatteryOpt = { _showBatteryOptDialog.value = false },
                    onOpenBatterySettings = {
                        _showBatteryOptDialog.value = false
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    },
                    serviceStatus = _serviceStatus.value,
                    normalCount = _normalCount.value,
                    reconnectCount = _reconnectCount.value,
                    failCount = _failCount.value,
                    showExitDialog = _showExitDialog.value,
                    onShowExitDialog = { _showExitDialog.value = true },
                    onDismissExit = { _showExitDialog.value = false },
                    onConfirmExit = {
                        _showExitDialog.value = false
                        stopAndExit()
                    },
                    showUpdateDialog = _showUpdateDialog.value,
                    updateVersion = pendingUpdateVersion,
                    onDismissUpdate = {
                        _showUpdateDialog.value = false
                        pendingUpdateUrl = null
                        pendingUpdateVersion = null
                    },
                    onConfirmUpdate = {
                        _showUpdateDialog.value = false
                        pendingUpdateUrl?.let { url ->
                            VersionChecker.openDownloadUrl(this@MainActivity, url)
                        }
                        pendingUpdateUrl = null
                        pendingUpdateVersion = null
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkBatteryOptimization()
        // 检查服务是否已停止（如果状态是运行中但服务已死，更新状态）
        val isRunning = isServiceRunning()
        if (!isRunning && _serviceStatus.value in listOf("运行中", "启动中...", "网络正常", "重连成功", "重连失败")) {
            _serviceStatus.value = "服务已停止"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(serviceStatusReceiver)
    }

    // ========================================================================
    // 权限
    // ========================================================================

    @SuppressLint("InlinedApi")
    private fun requestPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        if (permissions.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    // ========================================================================
    // 电池优化
    // ========================================================================

    @SuppressLint("NewApi", "BatteryLife")
    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            val packageName = applicationContext.packageName
            val isIgnoring = powerManager.isIgnoringBatteryOptimizations(packageName)

            if (!isIgnoring) {
                _showBatteryOptDialog.value = true
            }
        }
    }

    // ========================================================================
    // 服务控制
    // ========================================================================

    fun startService() {
        if (PreferencesManager.isConfigured()) {
            val intent = Intent(this, NetworkMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            _serviceStatus.value = "启动中..."
            LogManager.log("服务已启动")
        } else {
            LogManager.logWarning("配置缺失，无法启动服务")
        }
    }

    fun stopService() {
        val intent = Intent(this, NetworkMonitorService::class.java)
        stopService(intent)
        _serviceStatus.value = "服务已停止"
        LogManager.log("服务已停止")
    }

    fun stopAndExit() {
        stopService()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            finishAffinity()
        } else {
            finish()
        }
    }

    private fun isServiceRunning(): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
        return manager?.getRunningServices(Integer.MAX_VALUE)
            ?.any { it.service.className == NetworkMonitorService::class.java.name }
            ?: false
    }

    // ========================================================================
    // 登录
    // ========================================================================

    fun performLogin() {
        val username = PreferencesManager.getUsername()
        val password = PreferencesManager.getPassword()

        if (username.isEmpty() || password.isEmpty()) {
            LogManager.logWarning("配置缺失，无法登录")
            return
        }

        Thread {
            val result = DrcomAuthenticator.login(username, password)
            runOnUiThread {
                if (result) {
                    LogManager.log("登录成功")
                } else {
                    LogManager.logWarning("登录失败")
                }
            }
        }.start()
    }

    // ========================================================================
    // 版本更新
    // ========================================================================

    fun checkForUpdates() {
        VersionChecker.checkForUpdates(this) { result ->
            result.onSuccess { versionInfo ->
                if (versionInfo != null) {
                    pendingUpdateUrl = versionInfo.downloadUrl
                    pendingUpdateVersion = versionInfo.version
                    _showUpdateDialog.value = true
                } else {
                    LogManager.log("当前已是最新版本")
                }
            }.onFailure { error ->
                LogManager.logError("版本检查失败: ${error.message}")
            }
        }
    }

    // ========================================================================
    // 导出日志
    // ========================================================================

    fun exportLogs() {
        val logFile = java.io.File(filesDir, "eureka_logs.txt")
        if (!logFile.exists() || logFile.length() == 0L) {
            LogManager.logWarning("没有可导出的日志")
            return
        }

        val uri: Uri = androidx.core.content.FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            logFile
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivity(Intent.createChooser(shareIntent, "分享日志文件"))
    }

}

// ========================================================================
// Compose UI
// ========================================================================

@Composable
fun MainScreen(
    showBatteryOptDialog: Boolean,
    onDismissBatteryOpt: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    serviceStatus: String,
    normalCount: Int,
    reconnectCount: Int,
    failCount: Int,
    showExitDialog: Boolean,
    onShowExitDialog: () -> Unit,
    onDismissExit: () -> Unit,
    onConfirmExit: () -> Unit,
    showUpdateDialog: Boolean,
    updateVersion: String?,
    onDismissUpdate: () -> Unit,
    onConfirmUpdate: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    var username by remember { mutableStateOf(PreferencesManager.getUsername()) }
    var showConfigDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf(LogManager.getLogs()) }

    LaunchedEffect(Unit) {
        LogManager.setListener {
            logs = LogManager.getLogs()
        }
    }

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ---- 标题 ----
            Text(
                text = "Eureka",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ---- 配置状态 ----
            Text(
                text = if (PreferencesManager.isConfigured()) "已配置: $username" else "未配置",
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ---- 服务状态 ----
            Text(
                text = "运行状态: $serviceStatus",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = when {
                    serviceStatus == "网络正常" || serviceStatus == "重连成功" -> MaterialTheme.colorScheme.primary
                    serviceStatus.contains("失败") || serviceStatus.contains("错误") || serviceStatus.contains("停止") -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )

            Spacer(modifier = Modifier.height(4.dp))

            // ---- 计数器 ----
            Text(
                text = "网络正常: ${normalCount}次",
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 14.sp
            )
            Text(
                text = "重连成功: ${reconnectCount}次",
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 14.sp
            )
            Text(
                text = "重连失败: ${failCount}次",
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ---- 操作按钮 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { activity?.startService() },
                    modifier = Modifier.weight(1f),
                    enabled = PreferencesManager.isConfigured()
                ) {
                    Text("启动服务")
                }

                Button(
                    onClick = { activity?.stopService() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("停止服务")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { activity?.performLogin() },
                modifier = Modifier.fillMaxWidth(),
                enabled = PreferencesManager.isConfigured()
            ) {
                Text("立即登录")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showHelpDialog = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("帮助")
                }

                OutlinedButton(
                    onClick = { activity?.checkForUpdates() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("检查更新")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ---- 配置按钮 ----
            OutlinedButton(
                onClick = { showConfigDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("配置")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ---- 日志 ----
            Text(
                text = "日志",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    logs.reversed().forEach { log ->
                        Text(
                            text = log,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ---- 底部版权信息 ----
            Text(
                text = "by Aextoxicon & ds-v4-flash",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Text(
                text = "powered by Kotlin + Compose",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
    }

    // ====================================================================
    // 电池优化弹窗
    // ====================================================================
    if (showBatteryOptDialog) {
        AlertDialog(
            onDismissRequest = onDismissBatteryOpt,
            title = { Text("请关闭电池优化") },
            text = {
                Text(
                    "为确保后台服务正常运行，请前往:\n" +
                            "电池优化 → 找到本应用 → 选择不优化"
                )
            },
            confirmButton = {
                Button(onClick = onOpenBatterySettings) {
                    Text("去设置")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissBatteryOpt) {
                    Text("取消")
                }
            }
        )
    }

    // ====================================================================
    // 退出确认弹窗
    // ====================================================================
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = onDismissExit,
            title = { Text("停止服务") },
            text = { Text("确定要停止后台服务并退出应用吗？") },
            confirmButton = {
                Button(onClick = onConfirmExit) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissExit) {
                    Text("取消")
                }
            }
        )
    }

    // ====================================================================
    // 版本更新弹窗
    // ====================================================================
    if (showUpdateDialog) {
        AlertDialog(
            onDismissRequest = onDismissUpdate,
            title = { Text("检测到新版本") },
            text = {
                Text(
                    if (updateVersion != null) "新版本 $updateVersion 已发布，点击确认将打开系统默认浏览器下载。\n\n" +
                            "如果新版本直接安装失败，请卸载旧版本后再安装。" else "新版本已发布，点击确认下载。"
                )
            },
            confirmButton = {
                Button(onClick = onConfirmUpdate) {
                    Text("下载")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissUpdate) {
                    Text("取消")
                }
            }
        )
    }

    // ====================================================================
    // 配置弹窗
    // ====================================================================
    if (showConfigDialog) {
        ConfigDialog(
            onDismiss = { showConfigDialog = false },
            onSave = { user, pass, disableBackoff ->
                PreferencesManager.setUsername(user)
                PreferencesManager.setPassword(pass)
                PreferencesManager.setDisableBackoff(disableBackoff)
                username = user
                showConfigDialog = false
                activity?.stopService()
            }
        )
    }

    // ====================================================================
    // 帮助弹窗
    // ====================================================================
    if (showHelpDialog) {
        HelpDialog(
            onDismiss = { showHelpDialog = false },
            onExportLogs = {
                showHelpDialog = false
                activity?.exportLogs()
            }
        )
    }
}

// ========================================================================
// 配置对话框
// ========================================================================

@Composable
fun ConfigDialog(
    onDismiss: () -> Unit,
    onSave: (String, String, Boolean) -> Unit
) {
    var username by remember { mutableStateOf(PreferencesManager.getUsername()) }
    var password by remember { mutableStateOf(PreferencesManager.getPassword()) }
    var disableBackoff by remember { mutableStateOf(PreferencesManager.isDisableBackoff()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("配置后台服务") },
        text = {
            Column {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("用户名") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = disableBackoff,
                        onCheckedChange = { disableBackoff = it }
                    )
                    Text("停止退避请求（固定 1s 间隔）")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(username.trim(), password.trim(), disableBackoff)
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

// ========================================================================
// 帮助对话框
// ========================================================================

@Composable
fun HelpDialog(
    onDismiss: () -> Unit,
    onExportLogs: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("帮助") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "如果应用在后台运行时效果不符合预期，请检查以下几项：",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))

                Text("• 通知权限和电池优化（应用会显式提醒配置）")
                Text("• 前往手机管家开启后台任务权限")
                Text("• 检查是否有更新")
                Text("• 联系作者：QQ 3846962714（建议附带日志）")
            }
        },
        confirmButton = {
            Button(onClick = onExportLogs) {
                Text("导出日志")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
