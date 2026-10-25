package com.mcstation.download.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcstation.download.BuildConfig
import com.mcstation.download.ui.theme.Primary

/**
 * 独立"关于"页面：从设置页"关于"入口进入（返回键/左上角返回设置页）。
 *
 * 结构：
 *  顶部栏（返回 + 标题）
 *  居中：应用图标 / 名称 / 版本号（BuildConfig.VERSION_NAME 自动跟随）
 *  介绍卡片
 *  联系我们卡片（联系方式直接展示，GitHub 可点击跳转浏览器）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // ---------- 顶部栏 ----------
        TopAppBar(
            title = { Text("关于") },
            modifier = Modifier.padding(top = 14.dp),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )

        // ---------- 内容 ----------
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))

            // 应用图标
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .background(
                        brush = Brush.linearGradient(listOf(Color(0xFF7C5CC4), Primary)),
                        shape = RoundedCornerShape(22.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(44.dp),
                )
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "Minecraft安卓下载站",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            // 版本号直接读取 versionName，与 APP 内部版本号保持完全一致
            Text(
                "版本 ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(Modifier.height(24.dp))

            // 介绍卡片
            androidx.compose.material3.Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "应用介绍",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "一个Minecraft安卓版本的下载工具，数据来源于各大网站，帮助玩家们快速下载游戏，每日自动刷新版本列表。安全可靠，纯净无病毒，可放心下载使用！\n如果您遇到了Bug或问题，请“联系我们”，我们将在2个节假日内进行处理。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 22.sp,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // 联系我们卡片：联系方式直接展示，不再做成按钮 + 弹窗
            val context = androidx.compose.ui.platform.LocalContext.current
            androidx.compose.material3.Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.MailOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "联系我们",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    val email = "2381446726@qq.com"
                    val githubUrl = "https://github.com/MC-PRC94/MCAPKDownload-APP"
                    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    val bodyStyle = MaterialTheme.typography.bodyMedium
                    // EMail 行：「EMail：」灰色，地址紫色，点按复制到剪贴板
                    Row {
                        Text("EMail：", style = bodyStyle, color = labelColor)
                        Text(
                            email,
                            style = bodyStyle,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                val cm = context.getSystemService(
                                    android.content.Context.CLIPBOARD_SERVICE,
                                ) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("email", email))
                                android.widget.Toast
                                    .makeText(context, "已复制邮箱地址", android.widget.Toast.LENGTH_SHORT)
                                    .show()
                            },
                        )
                    }
                    // Github 行：「Github：」灰色，仓库名紫色，点按打开浏览器
                    Row(modifier = Modifier.padding(top = 6.dp)) {
                        Text("Github：", style = bodyStyle, color = labelColor)
                        Text(
                            "MC-PRC94/MCAPKDownload-APP",
                            style = bodyStyle,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(githubUrl),
                                    )
                                )
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
