package com.mcstation.download

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcstation.download.data.cache.DisplayPrefs
import com.mcstation.download.data.crash.CrashReporter
import com.mcstation.download.ui.SettingsScreen
import com.mcstation.download.ui.AboutScreen
import com.mcstation.download.ui.AppViewModel
import com.mcstation.download.ui.DownloadScreen
import com.mcstation.download.ui.VersionDetailScreen
import com.mcstation.download.ui.theme.MCDownloadStationTheme
import com.mcstation.download.ui.theme.Primary
import com.mcstation.download.data.model.DownloadStatus
import com.mcstation.download.data.model.DownloadTask
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    /** 监听 Minecraft PE 安装成功广播：自动删除勾选了"安装完后删除安装包"的安装包 */
    private var installReceiver: android.content.BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // 先初始化落盘日志，再接管未捕获异常：
        // 闪退时把堆栈写入 crash_last.txt 供下次启动显示，同时记入运行日志
        com.mcstation.download.data.log.AppLog.init(applicationContext)
        CrashReporter.install(this)
        com.mcstation.download.data.log.AppLog.i("App", "应用启动（${com.mcstation.download.BuildConfig.VERSION_NAME}）")
        super.onCreate(savedInstanceState)
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_PACKAGE_ADDED)
            addAction(android.content.Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        installReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                val pkg = intent.data?.schemeSpecificPart ?: return
                // Minecraft PE 的包名：安装/更新成功后触发删除安装包
                if (pkg == "com.mojang.minecraftpe") {
                    viewModel.onMinecraftInstalled()
                }
            }
        }
        registerReceiver(installReceiver, filter)

        // 显示偏好（字体大小 / 显示尺寸）：启动时读一次落盘值，之后设置页改动能实时生效
        DisplayPrefs.init(applicationContext)

        setContent {
            AppThemeWithDisplayScale {
                // 崩溃分支也要实例化 ViewModel：崩溃界面提供"检查更新"能力，
                // 用户可以在打不开主界面的情况下应用内升级到修复版本。
                // runCatching 兜底：万一 ViewModel 初始化本身崩溃，崩溃报告界面仍要能显示。
                val vm = remember { runCatching { viewModel }.getOrNull() }
                // 上次有崩溃日志未处理时：只显示崩溃详情对话框，不组合主界面——
                // 即使是"一进去就闪退"的必现崩溃，用户也能看到并复制崩溃信息反馈
                var crashReport by remember { mutableStateOf(CrashReporter.readCrash(this)) }
                if (crashReport != null) {
                    CrashReportDialog(
                        viewModel = vm,
                        report = crashReport!!,
                        onContinue = {
                            CrashReporter.clear(this)
                            crashReport = null
                        },
                    )
                    // 崩溃界面也能走完整的"发现新版本 → 下载 → 安装"流程
                    if (vm != null) UpdateFlowDialogs(vm)
                } else {
                    // 启动页：图标 + 应用名，1 秒后淡出进入主界面
                    var showSplash by remember { mutableStateOf(true) }
                    Crossfade(
                        targetState = showSplash,
                        animationSpec = tween(300),
                        label = "splashTransition",
                    ) { splash ->
                        if (splash) SplashScreen(onDone = { showSplash = false }) else MainApp(viewModel)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        installReceiver?.let { unregisterReceiver(it) }
        installReceiver = null
        super.onDestroy()
    }
}

// ================= 更新说明 Markdown 渲染（GitHub Release body 子集） =================

/** 行内语法：**加粗** 或 [文字](网址) */
private val INLINE_MD_REGEX = Regex("""\*\*(.+?)\*\*|\[([^\]]+)\]\(([^)\s]+)\)""")
private const val MD_URL_TAG = "MD_URL"

/**
 * 更新说明渲染：支持
 *  - `### 标题`（#/##/###...）→ 加粗中标题
 *  - `**文字**` → 加粗
 *  - `[文字](网址)` → 链接样式，点击用浏览器打开
 *  - `- 内容` → 小圆点列表
 *  - `1. 内容` 等普通行 → 正文段落，空行留白
 */
@Composable
private fun ReleaseNotesBody(body: String) {
    if (body.isBlank()) {
        Text("暂无更新说明", style = MaterialTheme.typography.bodyMedium)
        return
    }
    val context = LocalContext.current
    Column(Modifier.verticalScroll(rememberScrollState())) {
        body.lines().forEach { raw ->
            val trimmed = raw.trim()
            when {
                trimmed.isEmpty() -> Spacer(Modifier.height(6.dp))

                // 标题：去掉行首 # 号，加粗中标题
                trimmed.startsWith("#") -> {
                    val text = buildInlineMd(trimmed.dropWhile { it == '#' }.trim(), MaterialTheme.colorScheme.primary)
                    Text(
                        text,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp),
                    )
                }

                // 圆点列表：- 内容 / * 内容
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    val text = buildInlineMd(trimmed.substring(2).trim(), MaterialTheme.colorScheme.primary)
                    Row(Modifier.padding(vertical = 1.dp)) {
                        Text("•", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.widthIn(min = 6.dp))
                        ClickableText(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            onClick = { openMdLink(context, text, it) },
                        )
                    }
                }

                // 普通段落（含 1. 2. 3. 这类编号行，原样显示）
                else -> {
                    val text = buildInlineMd(trimmed, MaterialTheme.colorScheme.primary)
                    ClickableText(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 20.sp,
                        ),
                        modifier = Modifier.padding(vertical = 1.dp),
                        onClick = { openMdLink(context, text, it) },
                    )
                }
            }
        }
    }
}

/**
 * 行内 Markdown 解析：普通文本原样，**加粗** 加粗，[文字](网址) 渲染为
 * 主题色+下划线并打上 URL 注解（点击时由 openMdLink 取出用浏览器打开）。
 */
private fun buildInlineMd(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var index = 0
    for (m in INLINE_MD_REGEX.findAll(text)) {
        if (m.range.first > index) append(text.substring(index, m.range.first))
        val bold = m.groupValues[1]
        val linkText = m.groupValues[2]
        val linkUrl = m.groupValues[3]
        if (bold.isNotEmpty()) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
        } else if (linkText.isNotEmpty()) {
            pushStringAnnotation(MD_URL_TAG, linkUrl)
            withStyle(
                SpanStyle(
                    color = linkColor,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = TextDecoration.Underline,
                ),
            ) { append(linkText) }
            pop()
        }
        index = m.range.last + 1
    }
    if (index < text.length) append(text.substring(index))
}

/** 点击链接文字：取点击位置的 URL 注解，用浏览器打开（无效则忽略） */
private fun openMdLink(context: android.content.Context, text: AnnotatedString, offset: Int) {
    text.getStringAnnotations(MD_URL_TAG, offset, offset)
        .firstOrNull()
        ?.item
        ?.let { url ->
            runCatching {
                context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)),
                )
            }
        }
}

/**
 * 崩溃日志对话框：显示上次闪退的完整堆栈，可复制；点"关闭并继续"清除日志进入正常启动。
 * 确认按钮在右（关闭并继续），复制/检查更新在左；堆栈下方提示反馈方式。
 *
 * [viewModel] 为 null 表示 ViewModel 初始化自身也失败——此时不提供检查更新入口。
 */
@Composable
private fun CrashReportDialog(
    viewModel: AppViewModel?,
    report: String,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    // 用户点过"检查更新"后：检查结束仍无新版本（或网络失败）时给出反馈，
    // 有新版本时由外层 UpdateFlowDialogs 弹出"发现新版本"对话框
    var askedUpdate by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { /* 不允许点空白关闭，避免误触丢日志 */ },
        title = { Text("上次异常退出") },
        text = {
            Column {
                Text(
                    report,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "如果你想反馈问题，请“复制”这段报告并“联系我们”。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (viewModel != null && askedUpdate) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            viewModel.checkingUpdate -> "正在检查更新…"
                            viewModel.updateInfo != null -> "发现新版本，可在弹窗中直接下载安装"
                            else -> "已是最新版本（或网络不可用），可尝试清除数据后重装"
                        },
                        fontSize = 12.sp,
                        color = if (viewModel.updateInfo != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onContinue,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) { Text("关闭并继续") }
        },
        dismissButton = {
            Row {
                if (viewModel != null) {
                    TextButton(
                        onClick = {
                            askedUpdate = true
                            // 崩溃场景下连 Pre-release 一起查（beta 修复版也能被找到）
                            viewModel.checkForUpdate(includePrerelease = true)
                        },
                        enabled = !viewModel.checkingUpdate,
                    ) {
                        if (viewModel.checkingUpdate) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text("检查更新")
                    }
                }
                TextButton(onClick = {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", report))
                    Toast.makeText(context, "崩溃信息已复制", Toast.LENGTH_SHORT).show()
                }) { Text("复制") }
            }
        },
    )
}

/**
 * 启动页：居中的应用图标 + 应用名，停留 1 秒后回调 onDone 进入主界面
 */
@Composable
private fun SplashScreen(onDone: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(1000)
        onDone()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .background(
                        brush = Brush.linearGradient(listOf(Color(0xFF7C5CC4), Primary)),
                        shape = RoundedCornerShape(24.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(48.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Minecraft安卓下载站",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private enum class Tab { Download, Settings }

/**
 * 应用内更新流程：下载完成自动调起安装器 + "发现新版本"对话框（进度/失败重试/更新说明）。
 * 抽成独立函数：崩溃界面（主界面未组合时）也要能检查更新并安装修复版本。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpdateFlowDialogs(viewModel: AppViewModel) {
    val context = LocalContext.current
    // 更新包下载完成自动调起系统安装器（只调起一次，与版本包下载同用的 FileProvider 方案）
    androidx.compose.runtime.LaunchedEffect(
        viewModel.updateDownload?.status,
        viewModel.updateDownload?.file,
    ) {
        val dl = viewModel.updateDownload
        if (dl?.status == DownloadStatus.DONE && !dl.installTriggered && dl.file != null) {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                dl.file!!,
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                )
            }
            context.startActivity(intent)
            viewModel.markUpdateInstallTriggered()
        }
    }

    // "发现新版本"对话框：支持应用内 4 线程下载更新包（进度 + 实时速度），失败可重试
    viewModel.updateInfo?.let { info ->
        val dl = viewModel.updateDownload
        when {
            dl != null && dl.status == DownloadStatus.DOWNLOADING -> {
                // ---------- 正在下载：进度百分比 + 速度 ----------
                AlertDialog(
                    onDismissRequest = { /* 下载中不允许点外部关闭，避免误触 */ },
                    title = { Text("正在下载更新 ${info.tagName}") },
                    text = {
                        Column {
                            Text(
                                "${dl.progress}%  ·  ${fmtSpeed(dl.speedBytesPerSec)}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            if (dl.total > 0) {
                                Text(
                                    "${fmtMb(dl.received)} MB / ${fmtMb(dl.total)} MB",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            LinearProgressIndicator(
                                progress = { dl.progress / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { viewModel.cancelUpdateDownload() }) { Text("取消下载") }
                    },
                )
            }
            dl != null && dl.status == DownloadStatus.FAILED -> {
                // ---------- 下载失败：提示 + 重试 ----------
                AlertDialog(
                    onDismissRequest = {
                        viewModel.dismissUpdate()
                        viewModel.clearUpdateDownload()
                    },
                    title = { Text("下载失败") },
                    text = { Text("下载失败，请检查网络") },
                    confirmButton = {
                        Button(
                            onClick = { viewModel.retryUpdateDownload() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        ) { Text("重试") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            viewModel.dismissUpdate()
                            viewModel.clearUpdateDownload()
                        }) { Text("关闭") }
                    },
                )
            }
            else -> {
                // ---------- 发现新版本：更新说明 + 立即下载（应用内） ----------
                AlertDialog(
                    onDismissRequest = { viewModel.dismissUpdate() },
                    title = {
                        Text(
                            "发现新版本 ${info.tagName}" +
                                if (info.prerelease) "（预发布版）" else "",
                        )
                    },
                    text = {
                        // 更新说明按 Markdown 子集渲染：### 大标题 / **加粗** / [文字](网址) 链接 / - 圆点
                        ReleaseNotesBody(info.body)
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (info.downloadUrl.isNotEmpty()) {
                                    // 带上 GitHub Release 提供的 sha256：下载完成后校验安装包
                                    viewModel.startUpdateDownload(
                                        info.downloadUrl,
                                        info.tagName,
                                        sha256 = info.sha256,
                                    )
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        ) { Text("立即下载") }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.dismissUpdate() }) { Text("稍后再说") }
                    },
                )
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(viewModel: AppViewModel) {
    val context = LocalContext.current
    var currentTab by rememberSaveable { mutableStateOf(Tab.Download) }
    var detailVersionId by rememberSaveable { mutableStateOf<String?>(null) }
    var showDownloadPanel by remember { mutableStateOf(false) }
    var showFileManager by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showStatement by remember { mutableStateOf(false) }

    val detailVersion = viewModel.versions.firstOrNull { it.version == detailVersionId }
    val inDetail = detailVersion != null

    // 一次性提示（如刷新失败但有旧数据时）
    val contextForToast = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(viewModel.message) {
        viewModel.message?.let {
            Toast.makeText(contextForToast, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    // 启动时自动检查更新（设置页可开关，默认开启）
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (com.mcstation.download.data.cache.DownloadPrefs.autoCheckUpdate(context)) {
            viewModel.checkForUpdate()
        }
    }

    // 下载窗口打开时：按返回键 = 隐藏下载窗口
    androidx.activity.compose.BackHandler(enabled = showDownloadPanel) {
        showDownloadPanel = false
    }

    // 文件管理页打开时：按返回键 = 返回主页
    androidx.activity.compose.BackHandler(enabled = showFileManager) {
        showFileManager = false
    }

    // 详情页打开时：按返回键 = 返回主页（与左上角返回按钮一致）
    androidx.activity.compose.BackHandler(enabled = inDetail) {
        detailVersionId = null
    }

    // 设置页时：按返回键 = 回到下载页（声明页/关于页打开时优先关闭子页面）
    androidx.activity.compose.BackHandler(
        enabled = !inDetail && currentTab == Tab.Settings && !showAbout && !showStatement,
    ) {
        currentTab = Tab.Download
    }

    // 声明页打开时：按返回键 = 返回设置页
    androidx.activity.compose.BackHandler(enabled = showStatement) {
        showStatement = false
    }

    // 关于页打开时：按返回键 = 返回设置页
    androidx.activity.compose.BackHandler(enabled = showAbout) {
        showAbout = false
    }

    // 主页（无任何子页面打开）时：按返回键 = 弹出退出确认框
    var showExitConfirm by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(
        enabled = !inDetail && currentTab == Tab.Download &&
            !showDownloadPanel && !showFileManager && !showStatement && !showAbout,
    ) {
        showExitConfirm = true
    }

    // ---------- 退出确认框 ----------
    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("确认退出应用") },
            text = { Text("确定要退出应用吗？") },
            confirmButton = {
                Button(
                    onClick = {
                        showExitConfirm = false
                        (context as? android.app.Activity)?.finish()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) { Text("退出") }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) { Text("取消") }
            },
        )
    }

    // 下载完成任务自动调起系统安装器（每个任务只调起一次）
    androidx.compose.runtime.LaunchedEffect(viewModel.downloadTasks) {
        viewModel.downloadTasks.firstOrNull {
            it.status == DownloadStatus.DONE && !it.installTriggered && it.file != null
        }?.let { task ->
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                task.file!!,
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                )
            }
            context.startActivity(intent)
            viewModel.markInstallTriggered(task.id)
        }
    }

    UpdateFlowDialogs(viewModel)


    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            if (inDetail) {
                // ---------- 详情页顶栏 ----------
                TopAppBar(
                    title = { Text("版本详情") },
                    modifier = Modifier.padding(top = 14.dp),
                    navigationIcon = {
                        IconButton(onClick = { detailVersionId = null }) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
            } else {
                // ---------- 主页顶栏 ----------
                TopAppBar(
                    title = { Text("Minecraft安卓下载站") },
                    modifier = Modifier.padding(top = 4.dp), // 标题上移一点
                    navigationIcon = {
                        // 左上角刷新：点击直接刷新版本列表（跳过缓存）
                        IconButton(onClick = { viewModel.load(forceRefresh = true) }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                        }
                    },
                    actions = {
                        // 右上角下载列表入口：右下角标显示正在下载的任务数
                        IconButton(onClick = { showDownloadPanel = true }) {
                            Box {
                                Icon(Icons.Filled.Download, contentDescription = "下载列表")
                                if (viewModel.activeDownloadCount > 0) {
                                    Badge(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .offset(x = 5.dp, y = 5.dp),
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError,
                                    ) {
                                        Text("${viewModel.activeDownloadCount}")
                                    }
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            }
        },
        bottomBar = {
            if (!inDetail) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    NavigationBarItem(
                        selected = currentTab == Tab.Download,
                        onClick = { currentTab = Tab.Download },
                        icon = { Icon(Icons.Filled.Download, contentDescription = null) },
                        label = { Text("下载") },
                        // 整体向下偏移约4像素
                        modifier = Modifier.offset(y = 4.5.dp), // 底部导航整体下移
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                        ),
                    )
                    NavigationBarItem(
                        selected = currentTab == Tab.Settings,
                        onClick = { currentTab = Tab.Settings },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("设置") },
                        modifier = Modifier.offset(y = 4.5.dp), // 底部导航整体下移
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        // ---------- 详情页 <-> 主页 转场：左右滑动 + 淡入淡出 ----------
        AnimatedContent(
            targetState = detailVersion,
            modifier = Modifier.padding(innerPadding),
            transitionSpec = {
                val enteringDetail = targetState != null
                if (enteringDetail) {
                    // 进详情：新页从右侧滑入，旧页向左退
                    (slideInHorizontally(tween(300)) { it } + fadeIn(tween(300))) togetherWith
                        (slideOutHorizontally(tween(300)) { -it / 3 } + fadeOut(tween(300)))
                } else {
                    // 返回主页：新页从左侧滑回，旧页向右退
                    (slideInHorizontally(tween(300)) { -it / 3 } + fadeIn(tween(300))) togetherWith
                        (slideOutHorizontally(tween(300)) { it } + fadeOut(tween(300)))
                }
            },
            label = "detailTransition",
        ) { dv ->
            when {
                dv != null -> VersionDetailScreen(version = dv, viewModel = viewModel)
                else -> {
                    // ---------- 下载 <-> 关于 切换动画：淡入淡出 + 轻微上移 ----------
                    AnimatedContent(
                        targetState = currentTab,
                        transitionSpec = {
                            (fadeIn(tween(250)) + slideInVertically(tween(250)) { it / 16 }) togetherWith
                                fadeOut(tween(150))
                        },
                        label = "tabTransition",
                    ) { tab ->
                        when (tab) {
                            Tab.Download -> DownloadScreen(
                                viewModel = viewModel,
                                onVersionClick = { detailVersionId = it.version },
                            )
                            Tab.Settings -> SettingsScreen(
                                viewModel = viewModel,
                                onAboutClick = { showAbout = true },
                                onStatementClick = { showStatement = true },
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------- 下载窗口（列表浮层）：点击空白处隐藏；打开时淡入 + 从顶部滑入 ----------
    Box(Modifier.fillMaxSize()) {
        // 空白遮罩：点击任意空白处隐藏下载窗口（淡入淡出）
        androidx.compose.animation.AnimatedVisibility(
            visible = showDownloadPanel,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.matchParentSize(),
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color(0x52000000))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { showDownloadPanel = false },
            )
        }
        // 面板本体：从右上角顶部滑入，收起时滑回
        androidx.compose.animation.AnimatedVisibility(
            visible = showDownloadPanel,
            modifier = Modifier.align(Alignment.TopEnd),
            enter = slideInVertically(tween(250)) { -it } + fadeIn(tween(250)),
            exit = slideOutVertically(tween(200)) { -it } + fadeOut(tween(200)),
        ) {
            DownloadPanel(
                viewModel = viewModel,
                onDismiss = { showDownloadPanel = false },
                onOpenFileManager = {
                    showDownloadPanel = false
                    showFileManager = true
                },
            )
        }
    }

    // ---------- 文件管理页：全屏覆盖，从右侧滑入（与详情页转场一致） ----------
    androidx.compose.animation.AnimatedVisibility(
        visible = showFileManager,
        enter = slideInHorizontally(tween(300)) { it } + fadeIn(tween(300)),
        exit = slideOutHorizontally(tween(250)) { it } + fadeOut(tween(250)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            com.mcstation.download.ui.FileManagerScreen(
                viewModel = viewModel,
                onBack = { showFileManager = false },
            )
        }
    }

    // ---------- 声明页：全屏覆盖，从右侧滑入（返回设置页） ----------
    androidx.compose.animation.AnimatedVisibility(
        visible = showStatement,
        enter = slideInHorizontally(tween(300)) { it } + fadeIn(tween(300)),
        exit = slideOutHorizontally(tween(250)) { it } + fadeOut(tween(250)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            com.mcstation.download.ui.StatementScreen(
                viewModel = viewModel,
                onBack = { showStatement = false },
            )
        }
    }

    // ---------- 关于页：全屏覆盖，从右侧滑入（返回设置页） ----------
    androidx.compose.animation.AnimatedVisibility(
        visible = showAbout,
        enter = slideInHorizontally(tween(300)) { it } + fadeIn(tween(300)),
        exit = slideOutHorizontally(tween(250)) { it } + fadeOut(tween(250)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            AboutScreen(
                onBack = { showAbout = false },
            )
        }
    }
}

/** 下载列表浮层：显示每个下载任务的版本与进度 */
@Composable
private fun DownloadPanel(
    viewModel: AppViewModel,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
    onOpenFileManager: () -> Unit,
) {
    // 待确认取消的任务 id（下载中的任务点 ✕ 先弹确认，避免误触）
    var pendingCancelId by remember { mutableStateOf<Long?>(null) }
    val pendingCancelTask = viewModel.downloadTasks.firstOrNull { it.id == pendingCancelId }

    Card(
        modifier = modifier
            .padding(top = 76.dp, end = 12.dp)
            .widthIn(max = 320.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "下载列表",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(10.dp))

            if (viewModel.downloadTasks.isEmpty()) {
                Text(
                    "暂无下载任务",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(vertical = 18.dp),
                )
            } else {
                viewModel.downloadTasks.forEach { task ->
                    DownloadTaskRow(
                        task = task,
                        onPause = { viewModel.pauseDownload(task.id) },
                        onResume = { viewModel.resumeDownload(task.id) },
                        onCancel = {
                            if (task.status == DownloadStatus.DOWNLOADING) {
                                pendingCancelId = task.id // 下载中：先确认
                            } else {
                                viewModel.removeDownloadTask(task.id)
                            }
                        },
                        onRemove = { viewModel.removeDownloadTask(task.id) },
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }

            Spacer(Modifier.height(8.dp))
            // 底部入口：文件管理（蓝色小字 + 下划线）
            Text(
                "下载文件管理",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clickable(onClick = onOpenFileManager)
                    .padding(vertical = 4.dp),
            )
        }
    }

    // ---------- 取消下载确认框（防止误触） ----------
    if (pendingCancelTask != null) {
        AlertDialog(
            onDismissRequest = { pendingCancelId = null },
            title = { Text("取消下载") },
            text = { Text("确定要取消下载 ${pendingCancelTask.shortVersion} 吗？取消后已下载的进度将被清除，需要重新下载。") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.cancelDownload(pendingCancelTask.id)
                        pendingCancelId = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) { Text("确定取消") }
            },
            dismissButton = {
                TextButton(onClick = { pendingCancelId = null }) { Text("继续下载") }
            },
        )
    }
}

@Composable
private fun DownloadTaskRow(
    task: DownloadTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        // 第一行：版本号 + 状态 + 操作
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    task.shortVersion + if (task.version.beta) "（测试版）" else "",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = when (task.status) {
                        DownloadStatus.DOWNLOADING -> {
                            val total = if (task.total > 0) " / ${fmtMb(task.total)} MB" else ""
                            "${fmtMb(task.received)}$total · ${fmtSpeed(task.speedBytesPerSec)}"
                        }
                        DownloadStatus.DONE -> "已完成，点击安装"
                        DownloadStatus.PAUSED -> "已暂停 · ${fmtMb(task.received)} MB（断点已保留）"
                        DownloadStatus.FAILED -> "下载失败"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when (task.status) {
                        DownloadStatus.DOWNLOADING -> MaterialTheme.colorScheme.outline
                        DownloadStatus.DONE -> MaterialTheme.colorScheme.primary
                        DownloadStatus.PAUSED -> MaterialTheme.colorScheme.outline
                        DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
                    },
                )
            }
            // 暂停（下载中显示）
            if (task.status == DownloadStatus.DOWNLOADING) {
                IconButton(onClick = onPause, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Filled.Pause,
                        contentDescription = "暂停下载",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            // 继续（已暂停显示）
            if (task.status == DownloadStatus.PAUSED) {
                IconButton(onClick = onResume, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "继续下载",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                when (task.status) {
                    DownloadStatus.DOWNLOADING -> "${task.progress}%"
                    DownloadStatus.PAUSED -> "${task.progress}%"
                    DownloadStatus.DONE -> ""
                    DownloadStatus.FAILED -> ""
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            IconButton(onClick = onCancel, modifier = Modifier.size(30.dp)) {
                Icon(
                    if (task.status == DownloadStatus.DOWNLOADING) Icons.Filled.Close else Icons.Filled.Delete,
                    contentDescription = if (task.status == DownloadStatus.DOWNLOADING) "取消下载" else "移除",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
        }
        // 第二行：进度条（下载中/暂停时都显示）
        if (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.PAUSED) {
            LinearProgressIndicator(
                progress = { task.progress / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
        // 失败可点击重试
        if (task.status == DownloadStatus.FAILED) {
            TextButton(onClick = onCancel) { Text("移除") }
        }
    }
}

/** 字节转 MB 显示，保留 1 位小数 */
private fun fmtMb(bytes: Long): String {
    val mb = bytes / 1024.0 / 1024.0
    return String.format(java.util.Locale.US, "%.1f", mb)
}

/** 字节/秒转可读速度：<1MB/s 显示 KB/s，否则 MB/s */
private fun fmtSpeed(bytesPerSec: Long): String {
    return if (bytesPerSec >= 1024 * 1024) {
        String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSec / 1048576.0)
    } else {
        String.format(java.util.Locale.US, "%.0f KB/s", bytesPerSec / 1024.0)
    }
}

/**
 * 应用主题 + 显示偏好（设置页「显示」分组）：
 *  - 字体大小：只覆盖 fontScale → 只影响文字
 *  - 显示尺寸：覆盖 density → 版面与文字一起缩放（等价系统的「显示大小」）
 * 最终字号 = density × uiScale × fontScale；系统本身的字体/显示大小设置依然叠加生效。
 *
 * 放在主题最外层，主界面、各列表、对话框（AlertDialog 继承同一 CompositionLocal）全部一起生效。
 * 这里读的是 Compose 可观察状态，设置页拖动滑块 → 重组 → 全局立即变化，无需重启。
 */
@Composable
private fun AppThemeWithDisplayScale(content: @Composable () -> Unit) {
    MCDownloadStationTheme {
        val base = LocalDensity.current
        val uiScale = DisplayPrefs.uiScale.value
        val fontScale = DisplayPrefs.fontScale.value
        // 根级 Surface：让没写 color 参数的文字默认使用 onSurface（随深浅色切换）。
        // 否则 LocalContentColor 默认是黑色，深色模式下"应用名"等无色文字会黑字黑底看不清。
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = base.density * uiScale,
                    fontScale = base.fontScale * fontScale,
                ),
                content = content,
            )
        }
    }
}
