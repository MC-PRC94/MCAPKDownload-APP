package com.mcstation.download.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcstation.download.data.model.McVersion
import com.mcstation.download.ui.theme.Primary

/**
 * 版本详情页（下载页）：展示日期 / 体积 + 下载按钮
 *
 * 点击"下载 APK" -> 请求 get-download.php -> 弹出下载源对话框（夸克/百度优先）
 * 点击某个网盘 -> 用系统浏览器打开对应链接（App 内不直接下载文件）
 */
@Composable
fun VersionDetailScreen(
    version: McVersion,
    viewModel: AppViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

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
                    .background(Primary.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Public, contentDescription = null, tint = Primary)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(version.shortVersion, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = if (version.beta) "测试版" else "正式版",
                    style = MaterialTheme.typography.labelMedium,
                    color = Primary,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // 信息区
        DetailRow("发布日期", version.date.ifEmpty { "未知" })
        DetailRow("文件大小", version.size.ifEmpty { "未知" })

        Spacer(Modifier.height(28.dp))

        Button(
            onClick = { viewModel.requestDownloads(version.version) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Primary),
        ) {
            Icon(Icons.Filled.Download, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("下载 APK")
        }
        Spacer(Modifier.height(16.dp))
    }

    // ---------- 获取下载链接中 ----------
    if (viewModel.fetchingDownloads) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDownloads() },
            confirmButton = {},
            title = { Text("正在获取下载链接...") },
            text = {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Primary)
                }
            },
        )
    }

    // ---------- 获取失败 ----------
    if (viewModel.downloadError) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDownloads() },
            title = { Text("获取失败，请检查网络") },
            text = { Text("下载链接获取失败，请检查网络后重试。") },
            confirmButton = {
                Button(
                    onClick = { viewModel.retryDownloads() },
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                ) { Text("重试") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDownloads() }) { Text("取消") }
            },
        )
    }

    // ---------- 下载源列表 ----------
    viewModel.downloadSources?.let { sources ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissDownloads() },
            confirmButton = {},
            title = { Text("选择下载源 · ${version.version}") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    sources.forEach { source ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    // 用系统浏览器打开，App 内不直接下载
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(source.url)),
                                    )
                                    viewModel.dismissDownloads()
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Cloud,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(source.name, style = MaterialTheme.typography.titleMedium)
                                if (source.password.isNotEmpty()) {
                                    Text(
                                        "提取码：${source.password}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color(0xFF79747E),
                                    )
                                }
                            }
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = Color(0xFF79747E),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDownloads() }) { Text("关闭") }
            },
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Color(0xFF79747E))
        Text(value, fontWeight = FontWeight.Medium)
    }
}
