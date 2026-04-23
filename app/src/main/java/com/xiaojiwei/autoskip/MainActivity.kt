package com.xiaojiwei.autoskip

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.xiaojiwei.autoskip.ui.theme.AutoSkipTheme

data class AppItem(
    val packageName: String,
    val appName: String,
    val icon: Drawable
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AutoSkipTheme {
                AutoSkipApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoSkipApp() {
    val context = LocalContext.current
    val whitelistManager = remember { WhitelistManager(context) }
    var isServiceEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var showAppPicker by remember { mutableStateOf(false) }
    var whitelistPackages by remember { mutableStateOf(whitelistManager.getWhitelistPackages()) }
    var isAutoSkipEnabled by remember { mutableStateOf(whitelistManager.isAutoSkipEnabled()) }
    var isToastEnabled by remember { mutableStateOf(whitelistManager.isToastEnabled()) }
    var showLogForPackage by remember { mutableStateOf<String?>(null) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            whitelistManager.setToastEnabled(true)
            isToastEnabled = true
        } else {
            whitelistManager.setToastEnabled(false)
            isToastEnabled = false
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        isServiceEnabled = isAccessibilityServiceEnabled(context)
        whitelistPackages = whitelistManager.getWhitelistPackages()
        isAutoSkipEnabled = whitelistManager.isAutoSkipEnabled()
        // 用户可能从系统设置关闭了通知权限，同步状态
        if (isToastEnabled && !isNotificationPermissionGranted(context)) {
            whitelistManager.setToastEnabled(false)
            isToastEnabled = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AutoSkip") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                ServiceStatusCard(isServiceEnabled) {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }

            item {
                AutoSkipToggleCard(
                    isEnabled = isAutoSkipEnabled,
                    onToggle = { enabled ->
                        whitelistManager.setAutoSkipEnabled(enabled)
                        isAutoSkipEnabled = enabled
                    }
                )
            }

            item {
                ToastToggleCard(
                    isEnabled = isToastEnabled,
                    onToggle = { enabled ->
                        if (enabled) {
                            if (isNotificationPermissionGranted(context)) {
                                whitelistManager.setToastEnabled(true)
                                isToastEnabled = true
                            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                // Android 12 及以下，引导到通知设置
                                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                }
                                context.startActivity(intent)
                            }
                        } else {
                            whitelistManager.setToastEnabled(false)
                            isToastEnabled = false
                        }
                    }
                )
            }

            item {
                AddWhitelistCard(onClick = { showAppPicker = true })
            }

            item {
                Text(
                    "白名单应用 (${whitelistPackages.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (whitelistPackages.isEmpty()) {
                item {
                    Text(
                        "点击上方“添加白名单应用”选择需要跳过广告的应用",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                WhitelistAppList(
                    packages = whitelistPackages.toList().sorted(),
                    context = context,
                    onRemove = { pkg ->
                        whitelistManager.removePackage(pkg)
                        whitelistPackages = whitelistManager.getWhitelistPackages()
                    },
                    onShowLog = { pkg -> showLogForPackage = pkg }
                )
            }
        }
    }

    // App 选择弹窗
    if (showAppPicker) {
        AppPickerDialog(
            context = context,
            whitelistManager = whitelistManager,
            onDismiss = {
                showAppPicker = false
                whitelistPackages = whitelistManager.getWhitelistPackages()
            }
        )
    }

    // 检测日志弹窗
    showLogForPackage?.let { pkg ->
        val pm = context.packageManager
        val appInfo = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
        val appName = appInfo?.let { pm.getApplicationLabel(it).toString() } ?: pkg
        val logs = SkipAdService.appLogs[pkg]
        DetectionLogDialog(
            appName = appName,
            packageName = pkg,
            logs = logs,
            onDismiss = { showLogForPackage = null }
        )
    }

}

@Composable
fun ServiceStatusCard(isEnabled: Boolean, onClickEnable: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.errorContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { if (!isEnabled) onClickEnable() }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    if (isEnabled) "✅ 无障碍服务已开启" else "⚠️ 无障碍服务未开启",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                if (!isEnabled) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "点击前往设置开启",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
fun ToastToggleCard(isEnabled: Boolean, onToggle: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("跳过提示", fontWeight = FontWeight.Medium, fontSize = 15.sp)
                Text(
                    "跳过广告后弹出 Toast 提示",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = isEnabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
fun AutoSkipToggleCard(isEnabled: Boolean, onToggle: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("自动跳过", fontWeight = FontWeight.Medium, fontSize = 15.sp)
                Text(
                    if (isEnabled) "已开启自动点击跳过按钮" else "已临时关闭自动点击",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = isEnabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
fun AddWhitelistCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("添加白名单应用", fontWeight = FontWeight.Medium, fontSize = 15.sp)
                Text(
                    "选择需要自动跳过开屏广告的应用",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("+", fontSize = 24.sp, fontWeight = FontWeight.Medium)
        }
    }
}

private fun LazyListScope.WhitelistAppList(
    packages: List<String>,
    context: Context,
    onRemove: (String) -> Unit,
    onShowLog: (String) -> Unit
) {
    val pm = context.packageManager

    items(packages) { pkg ->
        WhitelistAppCard(
            packageName = pkg,
            packageManager = pm,
            onRemove = onRemove,
            onShowLog = onShowLog
        )
    }
}

@Composable
private fun WhitelistAppCard(
    packageName: String,
    packageManager: PackageManager,
    onRemove: (String) -> Unit,
    onShowLog: (String) -> Unit
) {
    val appInfo = try {
        packageManager.getApplicationInfo(packageName, 0)
    } catch (_: Exception) { null }

    val appName = appInfo?.let { packageManager.getApplicationLabel(it).toString() } ?: packageName
    val icon = appInfo?.let { packageManager.getApplicationIcon(it) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onShowLog(packageName) },
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon?.let {
                Image(
                    bitmap = it.toBitmap(48, 48).asImageBitmap(),
                    contentDescription = appName,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(appName, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                Text(
                    packageName,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            TextButton(onClick = { onRemove(packageName) }) {
                Text("移除", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun AppPickerDialog(
    context: Context,
    whitelistManager: WhitelistManager,
    onDismiss: () -> Unit
) {
    val pm = context.packageManager
    var searchQuery by remember { mutableStateOf("") }
    var selectedPackages by remember { mutableStateOf(whitelistManager.getWhitelistPackages()) }
    val installedApps = remember {
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0 }
            .filter { it.packageName != context.packageName }
            .map { AppItem(it.packageName, pm.getApplicationLabel(it).toString(), pm.getApplicationIcon(it)) }
            .sortedBy { it.appName }
    }

    val filteredApps = remember(searchQuery) {
        if (searchQuery.isBlank()) installedApps
        else installedApps.filter {
            it.appName.contains(searchQuery, ignoreCase = true) ||
                    it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择应用") },
        text = {
            Column {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("搜索应用名或包名") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.height(400.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filteredApps) { app ->
                        val isInWhitelist = app.packageName in selectedPackages
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isInWhitelist) {
                                        whitelistManager.removePackage(app.packageName)
                                    } else {
                                        whitelistManager.addPackage(app.packageName)
                                    }
                                    selectedPackages = whitelistManager.getWhitelistPackages()
                                }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Image(
                                bitmap = app.icon.toBitmap(40, 40).asImageBitmap(),
                                contentDescription = app.appName,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.appName, fontSize = 14.sp)
                                Text(
                                    app.packageName,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Checkbox(
                                checked = isInWhitelist,
                                onCheckedChange = { checked ->
                                    if (checked) whitelistManager.addPackage(app.packageName)
                                    else whitelistManager.removePackage(app.packageName)
                                    selectedPackages = whitelistManager.getWhitelistPackages()
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val serviceName = "${context.packageName}/${SkipAdService::class.java.canonicalName}"
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabledServices.contains(serviceName)
}

@Composable
fun DetectionLogDialog(
    appName: String,
    packageName: String,
    logs: List<SkipAdService.DetectionLog>?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val logText = remember(logs) { formatAppLogs(packageName, appName, logs) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$appName - 检测日志") },
        text = {
            Column(modifier = Modifier.heightIn(max = 400.dp)) {
                Text(
                    text = logText,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("检测日志", logText))
                    Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                }
            ) { Text("复制") }
        }
    )
}

private fun formatAppLogs(
    packageName: String,
    appName: String,
    logs: List<SkipAdService.DetectionLog>?
): String {
    if (logs.isNullOrEmpty()) {
        return "暂无该应用的检测日志。\n\n请打开该应用触发广告页面后再次查看。"
    }

    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    val sb = StringBuilder()
    sb.appendLine("应用: $appName ($packageName)")
    sb.appendLine("日志条数: ${logs.size}")
    sb.appendLine()

    logs.forEachIndexed { index, log ->
        sb.appendLine("========== 记录 ${index + 1} ==========")
        sb.appendLine("时间: ${dateFormat.format(Date(log.timestamp))}")
        sb.appendLine("事件: ${log.eventType}")
        sb.appendLine("窗口大小: ${log.rootBounds}")
        sb.appendLine("关键词: ${log.keywords.joinToString(", ")}")
        sb.appendLine()

        if (log.candidates.isEmpty()) {
            sb.appendLine("候选节点: 无")
        } else {
            sb.appendLine("候选节点 (共 ${log.candidates.size} 个):")
            log.candidates.forEachIndexed { cIndex, c ->
                sb.appendLine("--- 候选 ${cIndex + 1} ---")
                sb.appendLine("  匹配文本: \"${c.matchedText}\"")
                sb.appendLine("  关键词: ${c.keyword}")
                sb.appendLine("  得分: ${c.score} | 深度: ${c.depth}")
                sb.appendLine("  类名: ${c.className ?: "未知"}")
                sb.appendLine("  资源名: ${c.resourceName ?: "无"}")
                sb.appendLine("  位置: ${c.bounds}")
                val result = when {
                    c.isClicked -> "✅ 已点击"
                    c.filterReason != null -> "❌ ${c.filterReason}"
                    else -> "⏭ 未尝试"
                }
                sb.appendLine("  结果: $result")
            }
        }
        if (log.clickableNodes.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("观察到的元素 (共 ${log.clickableNodes.size} 个):")
            log.clickableNodes.forEachIndexed { nodeIndex, node ->
                sb.appendLine("--- 节点 ${nodeIndex + 1} ---")
                sb.appendLine("  文本: ${node.text?.let { "\"$it\"" } ?: "无"}")
                sb.appendLine("  描述: ${node.contentDescription?.let { "\"$it\"" } ?: "无"}")
                sb.appendLine("  类名: ${node.className ?: "未知"}")
                sb.appendLine("  资源名: ${node.resourceName ?: "无"}")
                sb.appendLine("  位置: ${node.bounds}")
                sb.appendLine("  可点击: ${if (node.isClickable) "是" else "否"}")
                sb.appendLine("  含“跳过”: ${if (node.containsSkipText) "是" else "否"}")
                sb.appendLine("  启用: ${if (node.isEnabled) "是" else "否"}")
                sb.appendLine("  目标判定: ${if (node.isReasonableTarget) "通过" else "未通过"}")
                sb.appendLine("  备注: ${node.note}")
            }
        }
        sb.appendLine("最终结果: ${log.resultSummary}")
        sb.appendLine()
    }

    return sb.toString()
}

fun isNotificationPermissionGranted(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}
