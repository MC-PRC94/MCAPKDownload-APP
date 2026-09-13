package com.mcstation.download.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcstation.download.data.model.McVersion
import com.mcstation.download.ui.theme.Primary
import com.mcstation.download.ui.theme.SurfaceBg

/**
 * 下载页：搜索框 + 可展开/收起的类型筛选（正式版/测试版/全部）+ 版本列表
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    viewModel: AppViewModel,
    onVersionClick: (McVersion) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 筛选按钮组展开/收起，默认展开
    var filterExpanded by rememberSaveable { mutableStateOf(true) }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // 搜索框 + 展开/收起按钮
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = viewModel.query,
                onValueChange = { viewModel.query = it },
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                placeholder = { Text("搜索版本") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFFECE6F4),
                    unfocusedContainerColor = Color(0xFFECE6F4),
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                ),
            )
            IconButton(onClick = { filterExpanded = !filterExpanded }) {
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = if (filterExpanded) "收起筛选" else "展开筛选",
                    tint = Primary,
                    modifier = Modifier.rotate(if (filterExpanded) 180f else 0f),
                )
            }
        }

        // 类型筛选按钮组（带展开/收起动画）
        AnimatedVisibility(visible = filterExpanded) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VersionFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = viewModel.filterMode == filter,
                        onClick = { viewModel.filterMode = filter },
                        label = { Text(filter.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Primary.copy(alpha = 0.16f),
                            selectedLabelColor = Primary,
                        ),
                    )
                }
            }
        }

        when {
            // ---------- 加载中 ----------
            viewModel.loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Primary)
                        Spacer(Modifier.height(14.dp))
                        Text("正在获取版本列表...", color = Color(0xFF79747E), fontSize = 14.sp)
                    }
                }
            }
            // ---------- 加载失败（无数据可用）----------
            viewModel.loadError -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.CloudOff,
                            contentDescription = null,
                            tint = Color(0xFF79747E),
                            modifier = Modifier.size(44.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "获取失败，请检查网络或稍后再试",
                            color = Color(0xFF79747E),
                            fontSize = 14.sp,
                        )
                        Spacer(Modifier.height(18.dp))
                        Button(
                            onClick = { viewModel.load(forceRefresh = true) },
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Primary),
                        ) {
                            Text("重试")
                        }
                    }
                }
            }
            // ---------- 正常列表 ----------
            viewModel.filtered.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("没有匹配的版本", color = Color(0xFF79747E))
                }
            }
            else -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(viewModel.filtered, key = { it.version }) { version ->
                        VersionItem(version = version, onClick = { onVersionClick(version) })
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}

/**
 * 单个版本卡片（对应截图：圆形图标 + 版本号 + 右箭头，紫色描边）
 */
@Composable
private fun VersionItem(version: McVersion, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Primary),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(Primary.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Public,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = version.shortVersion,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (version.beta) {
                    Text(
                        text = "测试版",
                        style = MaterialTheme.typography.labelMedium,
                        color = Primary,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.ChevronRight,
                contentDescription = null,
                tint = Color(0xFF79747E),
            )
        }
    }
}
