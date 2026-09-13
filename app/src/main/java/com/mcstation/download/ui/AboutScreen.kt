package com.mcstation.download.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcstation.download.ui.theme.Primary

/**
 * 关于页：图标 -> 应用名 -> 版本号 -> 介绍卡片 -> 操作按钮（居中排版）
 */
@Composable
fun AboutScreen(
    modifier: Modifier = Modifier,
    onContactClick: () -> Unit = {},
    onCheckUpdateClick: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))

        // 应用图标（占位：下载箭头，正式图标待定）
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

        // 应用名
        Text(
            "Minecraft安卓下载站",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(6.dp))

        // 版本号（偏小字）
        Text(
            "版本 1.0.0",
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
            color = Color(0xFF79747E),
        )

        Spacer(Modifier.height(28.dp))

        // 介绍卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(
                    "介绍",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "一个Minecraft安卓版本的下载工具，数据来源于网站API接口，帮助玩家们快速下载游戏，每日自动刷新版本列表。安全可靠，纯净无病毒，可放心下载使用！",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF49454F),
                    lineHeight = 24.sp,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // 操作按钮：联系我们（次） + 检查更新（主）
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onContactClick,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Primary),
                border = BorderStroke(1.dp, Primary.copy(alpha = 0.5f)),
            ) {
                Icon(Icons.Filled.MailOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("联系我们")
            }
            Button(
                onClick = onCheckUpdateClick,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary),
            ) {
                Icon(Icons.Filled.SystemUpdateAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("检查更新")
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}
