package com.mcstation.download.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 下载文件管理：展示 download/ 目录下的全部文件。
 * - 每个文件可删除（普通文件一次确认；分段临时/断点进度文件需二次确认 + 3 秒冷却）
 * - APK 文件额外提供"安装"按钮
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // 第一个确认框的目标文件
    var pendingDelete by remember { mutableStateOf<File?>(null) }
    // 第二个确认框（重要文件）的目标文件
    var pendingImportant by remember { mutableStateOf<File?>(null) }
    // "重要临时文件"分组是否展开（默认收起）
    var importantExpanded by remember { mutableStateOf(false) }

    // 普通文件（安装包等）与重要临时文件（分段/断点进度）分组
    val normalFiles = viewModel.downloadFiles.filterNot { viewModel.isImportantDownloadFile(it) }
    val importantFiles = viewModel.downloadFiles.filter { viewModel.isImportantDownloadFile(it) }

    LaunchedEffect(Unit) { viewModel.refreshDownloadFiles() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("文件管理") },
                modifier = Modifier.padding(top = 14.dp),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { innerPadding ->
        if (viewModel.downloadFiles.isEmpty()) {
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(80.dp))
                Text(
                    "暂无文件",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                // 普通文件（安装包等）
                normalFiles.forEach { file ->
                    FileRow(
                        file = file,
                        onInstall = { installApk(context, file) },
                        onDelete = { pendingDelete = file },
                    )
                }
                // 重要临时文件分组：没有文件时不显示，默认收起，点击展开/收起
                if (importantFiles.isNotEmpty()) {
                    val arrowRotation by animateFloatAsState(
                        targetValue = if (importantExpanded) 180f else 0f,
                        animationSpec = tween(250),
                        label = "fmArrow",
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { importantExpanded = !importantExpanded }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "重要临时文件",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "（${importantFiles.size}）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.weight(1f))
                        Icon(
                            Icons.Filled.ExpandMore,
                            contentDescription = if (importantExpanded) "收起" else "展开",
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier
                                .size(22.dp)
                                .graphicsLayer { rotationZ = arrowRotation },
                        )
                    }
                    // 展开/收起动画：垂直展开 + 淡入淡出
                    AnimatedVisibility(
                        visible = importantExpanded,
                        enter = expandVertically(animationSpec = tween(250)) + fadeIn(tween(250)),
                        exit = shrinkVertically(animationSpec = tween(250)) + fadeOut(tween(200)),
                    ) {
                        Column {
                            importantFiles.forEach { file ->
                                FileRow(
                                    file = file,
                                    onInstall = { installApk(context, file) },
                                    onDelete = { pendingDelete = file },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------- 第一个确认框：所有文件删除都要确认 ----------
    pendingDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除文件") },
            text = { Text("确定删除 ${file.name} 吗？") },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDelete = null
                        if (viewModel.isImportantDownloadFile(file)) {
                            pendingImportant = file // 重要文件：进入第二个确认框
                        } else {
                            viewModel.deleteDownloadFile(file)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    // ---------- 第二个确认框（仅分段临时/断点进度文件）----------
    // 注意布局要求：确定按钮在左边、取消按钮在右边，确定需等 3 秒才能按
    pendingImportant?.let { file ->
        var countdown by remember(file) { mutableIntStateOf(3) }
        var canConfirm by remember(file) { mutableStateOf(false) }
        LaunchedEffect(file) {
            for (i in 3 downTo 1) {
                countdown = i
                delay(1000)
            }
            canConfirm = true
        }
        AlertDialog(
            onDismissRequest = { pendingImportant = null },
            title = { Text("再次确认") },
            text = { Text("下载时的重要文件，删除后将会发生一系列错误，再次确认是否删除？") },
            // dismissButton 渲染在左侧：放"确定"（3 秒冷却，浅色样式）
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingImportant = null
                        viewModel.deleteDownloadFile(file)
                    },
                    enabled = canConfirm,
                ) {
                    Text(
                        if (canConfirm) "确定" else "确定（${countdown}s）",
                        color = if (canConfirm) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
                    )
                }
            },
            // confirmButton 渲染在右侧：放"取消"（实心紫色样式）
            confirmButton = {
                Button(
                    onClick = { pendingImportant = null },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) { Text("取消") }
            },
        )
    }
}

@Composable
private fun FileRow(
    file: File,
    onInstall: () -> Unit,
    onDelete: () -> Unit,
) {
    val isApk = file.name.endsWith(".apk", ignoreCase = true)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                file.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Default,
                maxLines = 2,
            )
            Text(
                fmtFileSize(file.length()) + " · " +
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(file.lastModified())),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        if (isApk) {
            TextButton(onClick = onInstall) {
                Text("安装", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 通过 FileProvider + 系统安装器安装 APK 文件 */
private fun installApk(context: android.content.Context, file: File) {
    com.mcstation.download.data.log.AppLog.i(
        "Install",
        "手动调起安装器：${file.name}（${file.length()} 字节，路径 ${file.absolutePath}）",
    )
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_ACTIVITY_NEW_TASK
        )
    }
    context.startActivity(intent)
}

/** 文件大小格式化：<1MB 显示 KB，<1GB 显示 MB，否则 GB */
private fun fmtFileSize(bytes: Long): String {
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}
