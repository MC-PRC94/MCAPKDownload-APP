@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mcstation.download.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.heightIn
import com.mcstation.download.BuildConfig
import com.mcstation.download.data.cache.DisplayPrefs
import com.mcstation.download.data.cache.DownloadPrefs
import com.mcstation.download.ui.theme.Primary
import kotlin.math.roundToInt

/**
 * 设置页（底部导航第二个标签）。
 *
 * 结构（自上而下）：
 *  [通用]     字体大小 + 显示尺寸 + 清理缓存 + 运行日志
 *  [下载设置] 默认线程数滑块 + 默认"装后删除"开关（记忆用户选择）
 *  [检查更新] 自动检查更新开关 + 手动检查按钮（点击先选检查范围：正式版 / 全部）
 *  [声明]     入口行（点击进入独立"声明"页面，正文在 assets/statement.txt）
 *  [关于]     入口行（点击进入独立"关于"页面）
 */
@Composable
fun SettingsScreen(
    viewModel: AppViewModel,
    modifier: Modifier = Modifier,
    onAboutClick: () -> Unit = {},
    onStatementClick: () -> Unit = {},
) {
    val context = LocalContext.current
    var autoCheck by remember { mutableStateOf(DownloadPrefs.autoCheckUpdate(context)) }
    var threadCount by remember { mutableStateOf(DownloadPrefs.threadCount(context)) }
    var deleteAfter by remember { mutableStateOf(DownloadPrefs.deleteAfterInstall(context)) }
    // 运行日志查看对话框
    var showLog by remember { mutableStateOf(false) }
    // 手动检查更新前的"选择检查范围"对话框
    var showScopeDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))

        // ================= 通用 =================
        // 字体大小 → 覆盖 LocalDensity.fontScale，只缩放文字；
        // 显示尺寸 → 覆盖 LocalDensity.density，版面与文字一起缩放（等价系统的"显示大小"）。
        // 两者都在 MainActivity 最外层生效，这里改完立即全局重建，不需要重启。
        val fontScale = DisplayPrefs.fontScale.value
        val uiScale = DisplayPrefs.uiScale.value
        SettingCard(title = "通用") {
            Text(
                "字体大小（${(fontScale * 100).roundToInt()}%）",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = fontScale,
                onValueChange = {
                    // 吸附到 10% 一档，避免浮点误差出现 109% 这类值
                    DisplayPrefs.setFontScale(context, (it * 10f).roundToInt() / 10f)
                },
                valueRange = DisplayPrefs.MIN_FONT_SCALE..DisplayPrefs.MAX_FONT_SCALE,
                steps = 5,
                // Material3 新默认手柄是细长竖条，观感差，换成经典圆点；并去掉手柄与轨道的间隙
                thumb = { RoundThumb() },
                track = { state ->
                    SliderDefaults.Track(sliderState = state, thumbTrackGapSize = 0.dp)
                },
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
            Text(
                "仅调整文字大小，按钮与列表间距不变",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )

            Spacer(Modifier.height(12.dp))

            Text(
                "显示尺寸（${(uiScale * 100).roundToInt()}%）",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = uiScale,
                onValueChange = {
                    // 吸附到 5% 一档
                    DisplayPrefs.setUiScale(context, (it * 20f).roundToInt() / 20f)
                },
                valueRange = DisplayPrefs.MIN_UI_SCALE..DisplayPrefs.MAX_UI_SCALE,
                steps = 5,
                // Material3 新默认手柄是细长竖条，观感差，换成经典圆点；并去掉手柄与轨道的间隙
                thumb = { RoundThumb() },
                track = { state ->
                    SliderDefaults.Track(sliderState = state, thumbTrackGapSize = 0.dp)
                },
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
            Text(
                "整体放大或缩小界面（文字与控件一起变化），相当于系统的“显示大小”",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { DisplayPrefs.reset(context) },
                    enabled = fontScale != 1f || uiScale != 1f,
                ) { Text("恢复默认") }
            }

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("清理缓存", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "清理版本列表、Wiki 介绍与日期缓存",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                TextButton(onClick = { viewModel.clearCaches() }) { Text("清理") }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showLog = true },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("运行日志", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "查看下载/安装等运行记录，反馈问题时可复制",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                TextButton(onClick = { showLog = true }) { Text("查看") }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ================= 下载设置 =================
        SettingCard(title = "下载设置") {
            Text(
                "默认线程数（${threadCount} 线程）",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = threadCount.toFloat(),
                onValueChange = {
                    threadCount = it.roundToInt()
                    DownloadPrefs.setThreadCount(context, threadCount)
                },
                valueRange = 1f..16f,
                steps = 14,
                // 同上：圆点手柄 + 无间隙
                thumb = { RoundThumb() },
                track = { state ->
                    SliderDefaults.Track(sliderState = state, thumbTrackGapSize = 0.dp)
                },
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
            SwitchRow(
                title = "安装完后删除安装包",
                subtitle = "版本安装包安装完成后自动删除",
                checked = deleteAfter,
                onCheckedChange = {
                    deleteAfter = it
                    DownloadPrefs.setDeleteAfterInstall(context, it)
                },
            )
        }

        Spacer(Modifier.height(14.dp))

        // ================= 检查更新 =================
        SettingCard(title = "检查更新") {
            SwitchRow(
                title = "自动检查更新",
                subtitle = "启动应用时自动检查是否有新版本",
                checked = autoCheck,
                onCheckedChange = {
                    autoCheck = it
                    DownloadPrefs.setAutoCheckUpdate(context, it)
                },
            )
            Button(
                onClick = { showScopeDialog = true },
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(24.dp),
                enabled = !viewModel.checkingUpdate,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Text(if (viewModel.checkingUpdate) "正在检查..." else "检查更新")
            }
        }

        Spacer(Modifier.height(14.dp))

        // ================= 声明（入口行：点击进入独立声明页） =================
        // 正文不在这里，写在 assets/statement.txt，改那个文件即可。
        SettingCard(title = "声明") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onStatementClick),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            brush = Brush.linearGradient(
                                listOf(Color(0xFF7C5CC4), Primary),
                            ),
                            shape = RoundedCornerShape(12.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Description,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "声明",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "版权、来源、隐私与免责声明",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Icon(
                    Icons.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // ================= 关于（入口行：点击进入独立关于页） =================
        SettingCard(title = "关于") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onAboutClick),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            brush = Brush.linearGradient(listOf(Color(0xFF7C5CC4), Primary)),
                            shape = RoundedCornerShape(12.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Minecraft安卓下载站",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    // 版本号直接读取 versionName，与 APP 内部版本号保持完全一致
                    Text(
                        "版本 ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Icon(
                    Icons.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Spacer(Modifier.height(20.dp))
    }

    // 手动检查更新：先选检查范围（正式版 / 全部）
    if (showScopeDialog) {
        AlertDialog(
            onDismissRequest = { showScopeDialog = false },
            title = { Text("选择检查范围") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            showScopeDialog = false
                            viewModel.checkForUpdate(includePrerelease = false)
                        },
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) { Text("正式版") }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            showScopeDialog = false
                            viewModel.checkForUpdate(includePrerelease = true)
                        },
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    ) { Text("全部", color = MaterialTheme.colorScheme.primary) }
                }
            },
            confirmButton = {
                TextButton(onClick = { showScopeDialog = false }) { Text("取消") }
            },
        )
    }

    // 运行日志对话框：等宽小字滚动查看，可复制/清空
    if (showLog) {
        LogViewDialog(onDismiss = { showLog = false })
    }
}

/** 运行日志对话框：显示 files/logs/app.log 全文，支持复制与清空 */
@Composable
private fun LogViewDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var logText by remember { mutableStateOf(com.mcstation.download.data.log.AppLog.readAll()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("运行日志") },
        text = {
            Text(
                logText,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as? android.content.ClipboardManager
                    cm?.setPrimaryClip(
                        android.content.ClipData.newPlainText("log", logText),
                    )
                }) { Text("复制") }
                TextButton(onClick = {
                    com.mcstation.download.data.log.AppLog.clear()
                    logText = "（暂无日志）"
                }) { Text("清空") }
            }
        },
    )
}

/** 设置分组卡片：标题 + 内容 */
@Composable
private fun SettingCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/** 开关行：标题 + 副标题（左）+ Switch（右） */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
        )
    }
}

/** 滑块的圆点手柄（替代 Material3 默认的细长竖条手柄）。 */
@Composable
private fun RoundThumb() {
    Box(
        modifier = Modifier
            .size(20.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
    )
}
