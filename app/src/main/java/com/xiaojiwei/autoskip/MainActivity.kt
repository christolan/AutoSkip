package com.xiaojiwei.autoskip

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
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
    var showKeywordEditor by remember { mutableStateOf<String?>(null) }
    var whitelistPackages by remember { mutableStateOf(whitelistManager.getWhitelistPackages()) }
    var isToastEnabled by remember { mutableStateOf(whitelistManager.isToastEnabled()) }

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
                    whitelistManager = whitelistManager,
                    onRemove = { pkg ->
                        whitelistManager.removePackage(pkg)
                        whitelistPackages = whitelistManager.getWhitelistPackages()
                    },
                    onEditKeywords = { pkg -> showKeywordEditor = pkg }
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

    // 关键词编辑弹窗
    showKeywordEditor?.let { pkg ->
        KeywordEditorDialog(
            packageName = pkg,
            whitelistManager = whitelistManager,
            onDismiss = { showKeywordEditor = null }
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
    whitelistManager: WhitelistManager,
    onRemove: (String) -> Unit,
    onEditKeywords: (String) -> Unit
) {
    val pm = context.packageManager

    items(packages) { pkg ->
        WhitelistAppCard(
            packageName = pkg,
            packageManager = pm,
            whitelistManager = whitelistManager,
            onRemove = onRemove,
            onEditKeywords = onEditKeywords
        )
    }
}

@Composable
private fun WhitelistAppCard(
    packageName: String,
    packageManager: PackageManager,
    whitelistManager: WhitelistManager,
    onRemove: (String) -> Unit,
    onEditKeywords: (String) -> Unit
) {
    val appInfo = try {
        packageManager.getApplicationInfo(packageName, 0)
    } catch (_: Exception) { null }

    val appName = appInfo?.let { packageManager.getApplicationLabel(it).toString() } ?: packageName
    val icon = appInfo?.let { packageManager.getApplicationIcon(it) }
    val hasCustom = whitelistManager.hasCustomKeywords(packageName)

    Card(
        modifier = Modifier.fillMaxWidth(),
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
                    if (hasCustom) "自定义关键词" else "默认关键词",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            TextButton(onClick = { onEditKeywords(packageName) }) {
                Text("关键词")
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

@Composable
fun KeywordEditorDialog(
    packageName: String,
    whitelistManager: WhitelistManager,
    onDismiss: () -> Unit
) {
    val currentKeywords = remember {
        whitelistManager.getKeywordsForPackage(packageName).toMutableStateList()
    }
    var newKeyword by remember { mutableStateOf("") }
    val hasCustom = whitelistManager.hasCustomKeywords(packageName)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑关键词") },
        text = {
            Column {
                if (!hasCustom) {
                    Text(
                        "当前使用默认关键词，添加或删除后将切换为自定义模式",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newKeyword,
                        onValueChange = { newKeyword = it },
                        placeholder = { Text("添加新关键词") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        if (newKeyword.isNotBlank() && newKeyword !in currentKeywords) {
                            currentKeywords.add(newKeyword.trim())
                            newKeyword = ""
                        }
                    }) {
                        Text("添加")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier.height(300.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(currentKeywords.toList()) { keyword ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(keyword, modifier = Modifier.weight(1f))
                            TextButton(onClick = { currentKeywords.remove(keyword) }) {
                                Text("删除", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                whitelistManager.setKeywordsForPackage(packageName, currentKeywords.toList())
                onDismiss()
            }) {
                Text("保存")
            }
        },
        dismissButton = {
            Row {
                if (hasCustom) {
                    TextButton(onClick = {
                        whitelistManager.clearCustomKeywords(packageName)
                        onDismiss()
                    }) {
                        Text("恢复默认")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
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

fun isNotificationPermissionGranted(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}
