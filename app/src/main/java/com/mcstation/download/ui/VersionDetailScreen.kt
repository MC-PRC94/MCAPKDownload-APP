package com.mcstation.download.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcstation.download.data.model.DownloadStatus
import com.mcstation.download.data.model.McVersion
import com.mcstation.download.data.net.NetworkKind
import com.mcstation.download.data.net.currentNetworkKind
import com.mcstation.download.data.mc.DownloadChannel
import com.mcstation.download.data.mc.InstalledMc
import com.mcstation.download.data.mc.McDataBackup
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 版本详情页（下载页）：展示日期 / 体积 + 下载按钮
 *
 * 点击"下载 APK" -> 先做设备兼容性检测（版本高于系统官方最高支持时弹确认框）
 * -> 再检测联网方式（非 Wifi 弹流量消耗确认框）
 * -> 确认后拼 mcpelife 直链 -> App 内直接下载并显示进度
 * 下载完成 -> 自动调用系统安装器安装 APK
 */
@Composable
fun VersionDetailScreen(
    version: McVersion,
    viewModel: AppViewModel,
    modifier: Modifier = Modifier,
) {
    // 进度框是否被用户手动关闭（关闭后下载继续，点"下载 APK"会重新弹出）
    var dialogClosed by remember { mutableStateOf(false) }
    // 取消下载确认框
    var showCancelConfirm by remember { mutableStateOf(false) }
    // 兼容性确认框：所选版本高于设备系统官方最高支持时弹出
    var showCompatConfirm by remember { mutableStateOf(false) }
    // 非 Wifi 下载确认框：记录当前联网方式名（null = 不显示），如"移动数据"
    var dataWarnLabel by remember { mutableStateOf<String?>(null) }
    // 下载渠道选择框 / 本次选择的渠道
    var showChannelDialog by remember { mutableStateOf(false) }
    var channel by remember { mutableStateOf(DownloadChannel.DEFAULT) }
    // 手机上已装的 Minecraft（null = 未安装）；用于在渠道选项后标"已安装"
    var installed by remember { mutableStateOf<InstalledMc.Installed?>(null) }
    // 已装渠道与所选渠道不同时的备份提示
    var showBackupPrompt by remember { mutableStateOf(false) }
    // 备份执行中 / 备份失败原因
    var backupBusy by remember { mutableStateOf(false) }
    var backupError by remember { mutableStateOf<String?>(null) }
    // 备份源目录候选（默认 Android/data 下的 com.mojang）、当前选择、目录内容预览
    var backupSources by remember { mutableStateOf<List<java.io.File>>(emptyList()) }
    var backupSource by remember { mutableStateOf<java.io.File?>(null) }
    var backupPreview by remember { mutableStateOf<McDataBackup.Preview?>(null) }
    var previewLoading by remember { mutableStateOf(false) }
    var sourceMenuOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 通知权限请求器（Android 13+ 下载完成通知需要）
    val notifPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    val context = androidx.compose.ui.platform.LocalContext.current
    // 该版本是否有失败/暂停的下载任务（有则按钮显示"继续下载"，点击自动断点续传）
    val hasResumableTask = viewModel.downloadTasks.any {
        it.version.version == version.version &&
            (it.status == DownloadStatus.FAILED || it.status == DownloadStatus.PAUSED)
    }

    // 进页面时识别已装 Minecraft 的渠道（签名比对）
    LaunchedEffect(Unit) {
        installed = withContext(Dispatchers.IO) { InstalledMc.detect(context) }
    }

    // 开始下载（渠道确认后调用）：线程数/装后删除用"设置"里的默认值
    val beginDownload: () -> Unit = {
        // Android 13+ 请求通知权限（下载完成通知），拒绝也不影响下载
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        dialogClosed = false // 下载开始后弹出进度框
        viewModel.startDownload(
            version,
            channel,
            com.mcstation.download.data.cache.DownloadPrefs.threadCount(context),
            com.mcstation.download.data.cache.DownloadPrefs.deleteAfterInstall(context),
        )
    }

    // 非 Wifi 时先提示流量消耗（弹框确认后才真正开始下载）；Wifi 与"无网络"不拦截
    val startOrWarnData: () -> Unit = {
        val kind = currentNetworkKind(context)
        if (kind != NetworkKind.WIFI && kind != NetworkKind.NONE) {
            dataWarnLabel = kind.label
        } else {
            beginDownload()
        }
    }

    // 兼容性 → 流量 → 下载
    val proceedDownload: (DownloadChannel) -> Unit = { ch ->
        channel = ch
        if (isAboveDeviceSupport(version.version, android.os.Build.VERSION.SDK_INT)) {
            showCompatConfirm = true
        } else {
            startOrWarnData()
        }
    }

    // 打开备份弹窗时：列出可用的数据目录（默认 Android/data 下 com.mojang）
    LaunchedEffect(showBackupPrompt) {
        if (!showBackupPrompt) return@LaunchedEffect
        previewLoading = true
        val list = withContext(Dispatchers.IO) {
            McDataBackup.availableSources().ifEmpty { listOfNotNull(McDataBackup.dataDir()) }
        }
        backupSources = list
        if (backupSource == null || backupSource !in list) backupSource = list.firstOrNull()
        previewLoading = false
    }

    // 切换数据目录时：读取该目录的存档列表（世界名 + 图标 + 修改时间）与其他内容
    LaunchedEffect(backupSource) {
        val src = backupSource ?: return@LaunchedEffect
        previewLoading = true
        backupPreview = withContext(Dispatchers.IO) {
            runCatching { McDataBackup.loadPreview(src) }.getOrNull()
        }
        previewLoading = false
    }

    // 真正执行备份（权限已具备时）
    val performBackup: () -> Unit = {
        backupError = null
        backupBusy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { McDataBackup.backup(context, backupSource) }
            backupBusy = false
            if (result.ok) {
                showBackupPrompt = false
                proceedDownload(channel)
            } else {
                backupError = result.error
            }
        }
    }

    // Android 10 及以下的运行时存储权限
    val storagePermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) performBackup() }

    // 点"立即备份"：先确保有权限，再复制数据
    val requestBackup: () -> Unit = {
        backupError = null
        if (McDataBackup.hasStorageAccess(context)) {
            performBackup()
        } else if (!McDataBackup.openAllFilesAccessSettings(context)) {
            // Android 11+ 的跳转失败，或低版本：走运行时权限
            storagePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        // 头部
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Public, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(version.shortVersion, style = MaterialTheme.typography.titleLarge)
                    // 已安装标记：设备上已装该版本时显示
                    if (viewModel.isVersionInstalled(version)) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "已安装",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(
                    text = if (version.beta) "测试版" else "正式版",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // 版本介绍：进入详情页异步抓取 Wiki 导语句（子线程网络请求），完成/失败后显示
        var wikiIntro by remember(version.version) { mutableStateOf<String?>(null) }
        // 发布日期：本地有缓存就直接显示（重启后无需等抓取），否则等抓取完再显示
        var releaseDate by remember(version.version) {
            mutableStateOf(viewModel.getCachedDate(version.version))
        }
        LaunchedEffect(version.version) {
            wikiIntro = viewModel.getWikiIntro(version)
            viewModel.getCachedDate(version.version)?.let { releaseDate = it }
        }

        // 信息区：发布日期取 Wiki 导语里的最后一个日期（已持久化缓存）；文件大小优先用 HEAD 探测的真实大小
        DetailRow("发布日期", releaseDate ?: "正在获取…")
        DetailRow("文件大小", formatSize(version))

        // 版本详细信息：跳转中文 Minecraft Wiki 的基岩版页面
        // 正式版用简单版本号（26.51），测试版用完整版本号（26.60.24）
        val context = androidx.compose.ui.platform.LocalContext.current
        val wikiUrl = "https://zh.minecraft.wiki/w/" +
            android.net.Uri.encode("基岩版" + version.shortVersion)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .clickable {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(wikiUrl),
                        )
                    )
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("版本详细信息", color = MaterialTheme.colorScheme.outline)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "zh.minecraft.wiki",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Filled.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp),
                )
            }
        }

        // 版本介绍句（Wiki 导语，异步抓取）
        Text(
            text = wikiIntro ?: "正在获取版本介绍…",
            style = MaterialTheme.typography.bodySmall,
            color = if (wikiIntro != null && wikiIntro != "获取失败") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 2.dp),
        )

        // 打开详情页时若还没有真实大小，立即单发一个 HEAD 补查
        LaunchedEffect(version.version) {
            viewModel.refreshSizeFor(version.version)
        }

        Spacer(Modifier.height(28.dp))

        Button(
            onClick = {
                // 先选下载渠道（去验证包 / 官方包 / MCPELIFE包），选定后再走
                // [备份提示] → 兼容性 → 流量 → 下载
                showChannelDialog = true
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Icon(Icons.Filled.Download, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (hasResumableTask) "继续下载" else "下载 APK")
        }
        Spacer(Modifier.height(8.dp))
        // 点击下载会弹出进度框；按钮下方小字尽量一行显示，放不下就缩小字号
        Text(
            "多线程下载 · 断点续传 · 线程数与装后删除可在设置中调整",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
            maxLines = 1,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(16.dp))
    }

    // ---------- 下载进度框：本版本正在下载时弹出 ----------
    val myTask = viewModel.downloadTasks.firstOrNull { it.version.version == version.version }
    if (myTask != null && myTask.status == DownloadStatus.DOWNLOADING && !dialogClosed) {
        AlertDialog(
            onDismissRequest = { dialogClosed = true }, // 点空白/返回：只关窗口，下载继续
            confirmButton = {},
            title = { Text("正在下载 ${version.shortVersion}") },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = { myTask.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = buildString {
                            append("${myTask.progress}%")
                            append("  ·  ${fmtSpeed(myTask.speedBytesPerSec)}")
                            if (myTask.total > 0) {
                                append("  ·  ${fmtMb(myTask.received)} / ${fmtMb(myTask.total)} MB")
                            }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            },
            dismissButton = {
                Row {
                    // 关闭窗口：收起对话框，下载在后台继续（右上角下载列表可查看）
                    TextButton(onClick = { dialogClosed = true }) { Text("关闭") }
                    // 取消下载：先弹确认，避免误触
                    TextButton(onClick = { showCancelConfirm = true }) { Text("取消下载") }
                }
            },
        )
    }

    // ---------- 取消下载确认框（防止误触） ----------
    if (showCancelConfirm && myTask != null) {
        AlertDialog(
            onDismissRequest = { showCancelConfirm = false },
            title = { Text("取消下载") },
            text = { Text("确定要取消下载 ${version.shortVersion} 吗？取消后已下载的进度将被清除，需要重新下载。") },
            confirmButton = {
                Button(
                    onClick = {
                        showCancelConfirm = false
                        dialogClosed = true
                        viewModel.cancelDownload(myTask.id)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) { Text("确定取消") }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirm = false }) { Text("继续下载") }
            },
        )
    }

    // ---------- 版本兼容性确认框：所选版本高于设备系统官方最高支持时弹出 ----------
    if (showCompatConfirm) {
        AlertDialog(
            onDismissRequest = { showCompatConfirm = false },
            text = {
                Column {
                    Text("您的Android版本似乎不支持此版本，游玩过程中可能会出现异常，您确定要继续吗？")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "设备：Android ${android.os.Build.VERSION.RELEASE}（API ${android.os.Build.VERSION.SDK_INT}）· " +
                            "官方最高支持 ${deviceMaxDisplay(android.os.Build.VERSION.SDK_INT)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCompatConfirm = false
                        // 兼容性确认后仍要走流量检测（超版本支持的包同样是大文件）
                        startOrWarnData()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) { Text("继续下载") }
            },
            dismissButton = {
                TextButton(onClick = { showCompatConfirm = false }) { Text("取消") }
            },
        )
    }

    // ---------- 下载渠道选择框 ----------
    if (showChannelDialog) {
        AlertDialog(
            onDismissRequest = { showChannelDialog = false },
            title = { Text(DownloadChannel.DIALOG_TITLE) },
            text = {
                Column {
                    // 官方包不提供选择（selectable=false），只用于识别已装包
                    DownloadChannel.values().filter { it.selectable }.forEach { ch ->
                        val isInstalled = installed?.channel == ch
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = ch.available) {
                                    showChannelDialog = false
                                    channel = ch
                                    // 已装的是别的渠道（或无法识别的改包）→ 提示备份
                                    val inst = installed
                                    if (inst != null && inst.channel != ch) {
                                        showBackupPrompt = true
                                    } else {
                                        proceedDownload(ch)
                                    }
                                }
                                .padding(vertical = 10.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = ch.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (ch.available) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.outline,
                                )
                                if (isInstalled) {
                                    Spacer(Modifier.width(6.dp))
                                    ChannelBadge(DownloadChannel.INSTALLED_BADGE, MaterialTheme.colorScheme.primary)
                                }
                                if (!ch.available) {
                                    Spacer(Modifier.width(6.dp))
                                    ChannelBadge(
                                        DownloadChannel.UNAVAILABLE_BADGE,
                                        MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = ch.subtitle,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                                color = MaterialTheme.colorScheme.outline,
                                lineHeight = 15.sp,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showChannelDialog = false }) { Text("取消") }
            },
        )
    }

    // ---------- 渠道不一致：提示备份数据（卸载旧包会清掉 Android/data） ----------
    if (showBackupPrompt) {
        AlertDialog(
            onDismissRequest = { showBackupPrompt = false },
            title = { Text("让我们来备份你的数据") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    item {
                        Text(
                            "你的Minecraft的下载渠道与我们的渠道不同，我们将备份你的游戏数据以防万一。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    // 备份源目录（默认 Android/data 下的 com.mojang，可切换）
                    item {
                        Spacer(Modifier.height(12.dp))
                        Box {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))
                                    .clickable(enabled = backupSources.size > 1) {
                                        sourceMenuOpen = true
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "备份目录",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                    Text(
                                        text = backupSource?.absolutePath
                                            ?: "未找到 Minecraft 数据目录",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (backupSources.size > 1) {
                                    Icon(
                                        Icons.Filled.ArrowDropDown,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = sourceMenuOpen,
                                onDismissRequest = { sourceMenuOpen = false },
                            ) {
                                backupSources.forEach { src ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                src.absolutePath,
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                            )
                                        },
                                        onClick = {
                                            backupSource = src
                                            sourceMenuOpen = false
                                        },
                                    )
                                }
                            }
                        }
                    }

                    if (previewLoading) {
                        item {
                            Spacer(Modifier.height(14.dp))
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        val preview = backupPreview
                        if (preview == null || !preview.readable) {
                            item {
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    "无法读取这个目录：请先授予存储访问权限，或确认路径存在",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        } else {
                            // 存档列表：名称 + 世界图标 + 最后修改时间
                            item {
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    "存档（${preview.worlds.size}）",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (preview.worlds.isEmpty()) {
                                item {
                                    Text(
                                        "这个目录里没有找到存档",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            } else {
                                items(preview.worlds) { w -> WorldRow(w) }
                            }

                            // 存档之外的其他备份内容
                            item {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "其他备份内容",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (preview.others.isEmpty()) {
                                item {
                                    Text(
                                        "无",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            } else {
                                items(preview.others) { (name, count) ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp),
                                    ) {
                                        Text(
                                            name,
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            "$count 项",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    backupError?.let {
                        item {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { requestBackup() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) { Text("立即备份") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showBackupPrompt = false
                        proceedDownload(channel)
                    },
                ) { Text("不备份") }
            },
        )
    }

    // ---------- 备份进行中 ----------
    if (backupBusy) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("正在备份数据") },
            text = {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
            },
            confirmButton = {},
        )
    }

    // ---------- 非 Wifi 下载确认框：提示当前联网方式与预估流量 ----------
    dataWarnLabel?.let { label ->
        AlertDialog(
            onDismissRequest = { dataWarnLabel = null },
            text = {
                Text(
                    buildAnnotatedString {
                        append("您正在使用 ")
                        // 联网方式与流量体积都加粗，突出关键信息
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(label) }
                        append("，预计花费 ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(estimateTraffic(version))
                        }
                        append("，您确定要继续吗？")
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        dataWarnLabel = null
                        beginDownload()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) { Text("继续下载") }
            },
            dismissButton = {
                TextButton(onClick = { dataWarnLabel = null }) { Text("取消") }
            },
        )
    }
}

/** 渠道选项后的小标签（已安装 / 暂未开放） */
@Composable
private fun ChannelBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = color,
        )
    }
}

/** 字节转 MB 显示，保留 1 位小数 */
private fun fmtMb(bytes: Long): String {
    val mb = bytes / 1024.0 / 1024.0
    return String.format(java.util.Locale.US, "%.1f", mb)
}

/** 字节/秒转速度显示：≥1MB/s 用 MB/s，否则用 KB/s */
private fun fmtSpeed(bytesPerSec: Long): String {
    val mb = bytesPerSec / 1024.0 / 1024.0
    return if (mb >= 1.0) {
        String.format(java.util.Locale.US, "%.1f MB/s", mb)
    } else {
        String.format(java.util.Locale.US, "%.0f KB/s", bytesPerSec / 1024.0)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.outline)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

/** 文件大小显示：优先去验证包（多数用户选这个渠道），其次 MCPELIFE 包，最后回退 API 的 size 文本 */
private fun formatSize(version: McVersion): String {
    val bytes = if (version.devSize > 0) version.devSize else version.realSize
    if (bytes > 0) {
        val mb = bytes / 1024.0 / 1024.0
        return if (mb >= 1024) {
            String.format(java.util.Locale.US, "%.2f GB", mb / 1024)
        } else {
            String.format(java.util.Locale.US, "%.1f MB", mb)
        }
    }
    return version.size.ifEmpty { "未知" }
}

/** 下载流量预估：与 [formatSize] 同一口径（去验证包优先），保证两处数字一致 */
private fun estimateTraffic(version: McVersion): String {
    val bytes = if (version.devSize > 0) version.devSize else version.realSize
    if (bytes > 0) {
        val mb = bytes / 1024.0 / 1024.0
        return if (mb >= 1024) {
            String.format(java.util.Locale.US, "%.2f GB", mb / 1024)
        } else {
            String.format(java.util.Locale.US, "%.0f MB", mb)
        }
    }
    return version.size.ifBlank { "未知" }
}

/**
 * 设备 API 等级对应的官方最高支持正式版（用于版本比较，取前 3 段）。
 * 对照官方支持表：
 * - Android 5.0~7.1（API 21~25）封顶 1.20.62（1.20.63 起 APK 最低 SDK 提升，无法直接安装）
 * - Android 8.0/8.1（API 26/27）封顶 1.21.62（26.x 分支起直接禁止安装）
 * - Android 9+（API 28+）无限制，可正常安装最新 26.x
 */
private fun maxSupportedTriple(api: Int): IntArray? = when {
    api >= 28 -> null
    api >= 26 -> intArrayOf(1, 21, 62)
    else -> intArrayOf(1, 20, 62)
}

/** 所选版本（完整版本号）是否高于设备系统官方最高支持：逐段数值比较前 3 段（26.x 首段即大于 1.x） */
private fun isAboveDeviceSupport(versionText: String, api: Int): Boolean {
    val cap = maxSupportedTriple(api) ?: return false
    val parts = versionText.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0..2) {
        val p = parts.getOrElse(i) { 0 }
        if (p != cap[i]) return p > cap[i]
    }
    return false
}

/** 设备官方最高支持版本的显示文本（用于兼容提示小字） */
private fun deviceMaxDisplay(api: Int): String {
    val cap = maxSupportedTriple(api) ?: return "最新版本"
    return cap.joinToString(".")
}

/** 存档修改时间格式（弹窗里逐条显示） */
private val worldDateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)

/** 备份确认弹窗里的单条存档：世界图标 + 世界名 + 最后修改时间 */
@Composable
private fun WorldRow(entry: McDataBackup.WorldEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WorldIcon(entry.icon)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = worldDateFormat.format(java.util.Date(entry.modified)),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** 世界图标（world_icon.jpeg）；没有图标或解码失败时显示占位图标 */
@Composable
private fun WorldIcon(file: java.io.File?) {
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, file) {
        value = withContext(Dispatchers.IO) { file?.let { decodeThumbnail(it) } }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Public,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 解码缩略图：按 2 的幂次降采样到 ~160px，避免大图占内存 */
private fun decodeThumbnail(file: java.io.File, maxPx: Int = 160): android.graphics.Bitmap? = try {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxPx) sample *= 2
    android.graphics.BitmapFactory.decodeFile(
        file.absolutePath,
        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
    )
} catch (e: Exception) {
    null
}
