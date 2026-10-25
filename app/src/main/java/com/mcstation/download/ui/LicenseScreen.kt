package com.mcstation.download.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 独立「开源许可」页面：从设置页「声明」分组进入（返回键 / 左上角返回设置页）。
 *
 * 展示本应用使用的第三方开源库及其许可证（Apache 2.0 协议要求分发时附带协议声明）。
 * 列表手写维护——依赖变化时同步更新这里（库数量少，不引入自动生成插件）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenseScreen(
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // ---------- 顶部栏（与声明页一致） ----------
        TopAppBar(
            title = { Text("开源许可") },
            modifier = Modifier.padding(top = 14.dp),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(6.dp))

            Text(
                "本应用使用了以下开源项目，感谢开源社区的贡献：",
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            OssLibrary(
                name = "Kotlin",
                usage = "编程语言与标准库",
                license = "Apache License 2.0",
            )
            OssLibrary(
                name = "Jetpack Compose · Material 3",
                usage = "声明式界面框架与设计规范",
                license = "Apache License 2.0",
            )
            OssLibrary(
                name = "AndroidX",
                usage = "core-ktx、Lifecycle、Activity Compose 等系统组件",
                license = "Apache License 2.0",
            )
            OssLibrary(
                name = "kotlinx.serialization",
                usage = "JSON 序列化（API 响应解析与本地缓存）",
                license = "Apache License 2.0",
            )
            OssLibrary(
                name = "OkHttp · Okio",
                usage = "网络请求与多线程分段下载",
                license = "Apache License 2.0",
            )
            OssLibrary(
                name = "Jsoup",
                usage = "HTML 解析（Wiki 版本介绍抓取）",
                license = "MIT License",
            )
            OssLibrary(
                name = "Desugar JDK Libraries",
                usage = "Java API 脱糖（Android 低版本兼容）",
                license = "Apache License 2.0",
            )

            Spacer(Modifier.height(16.dp))

            Text(
                "各库的许可证全文可在其官方仓库查看；本应用基于 Apache License 2.0 之规定于分发时附带上述许可声明。应用本身以 MIT 协议开源，见 GitHub 仓库。",
                style = MaterialTheme.typography.labelSmall,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.outline,
            )

            Spacer(Modifier.height(20.dp))
        }
    }
}

/** 单个开源库条目卡片：库名 + 用途 + 协议标签 */
@Composable
private fun OssLibrary(
    name: String,
    usage: String,
    license: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    usage,
                    style = MaterialTheme.typography.labelSmall,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                license,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}
