package com.mcstation.download

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mcstation.download.ui.AboutScreen
import com.mcstation.download.ui.AppViewModel
import com.mcstation.download.ui.DownloadScreen
import com.mcstation.download.ui.VersionDetailScreen
import com.mcstation.download.ui.theme.MCDownloadStationTheme
import com.mcstation.download.ui.theme.Primary
import com.mcstation.download.ui.theme.SurfaceBg
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MCDownloadStationTheme {
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
            .background(SurfaceBg),
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

private enum class Tab { Download, About }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(viewModel: AppViewModel) {
    val context = LocalContext.current
    var currentTab by rememberSaveable { mutableStateOf(Tab.Download) }
    var detailVersionId by rememberSaveable { mutableStateOf<String?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showRefreshConfirm by remember { mutableStateOf(false) }
    var showContactDialog by remember { mutableStateOf(false) }

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

    // 手动刷新确认框
    if (showRefreshConfirm) {
        AlertDialog(
            onDismissRequest = { showRefreshConfirm = false },
            title = { Text("刷新") },
            text = { Text("手动刷新会消耗一定网络资源，您确定要刷新吗") },
            confirmButton = {
                Button(
                    onClick = {
                        showRefreshConfirm = false
                        viewModel.load(forceRefresh = true) // 跳过缓存有效期，真正请求接口
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showRefreshConfirm = false }) { Text("取消") }
            },
        )
    }

    // "联系我们"对话框：展示联系方式，GitHub 可点击跳转系统浏览器
    if (showContactDialog) {
        AlertDialog(
            onDismissRequest = { showContactDialog = false },
            title = { Text("联系我们") },
            text = {
                Column {
                    Text("QQ：2381446726", modifier = Modifier.padding(vertical = 4.dp))
                    Text("EMail：2381446726@qq.com", modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        "Github：https://github.com/MC-PRC94/MCAPKDownload-APP",
                        color = Primary,
                        modifier = Modifier
                            .padding(vertical = 4.dp)
                            .clickable {
                                showContactDialog = false
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse("https://github.com/MC-PRC94/MCAPKDownload-APP"),
                                    )
                                )
                            },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showContactDialog = false }) { Text("关闭") }
            },
        )
    }

    Scaffold(
        containerColor = SurfaceBg,
        topBar = {
            if (inDetail) {
                // ---------- 详情页顶栏 ----------
                TopAppBar(
                    title = { Text("版本详情") },
                    modifier = Modifier.padding(top = 14.dp),
                    navigationIcon = {
                        IconButton(onClick = { detailVersionId = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceBg),
                )
            } else {
                // ---------- 主页顶栏 ----------
                TopAppBar(
                    title = { Text("Minecraft安卓下载站") },
                    modifier = Modifier.padding(top = 14.dp), // 标题整体下移一点
                    navigationIcon = {
                        // 左上角叉号：关闭应用
                        IconButton(onClick = { (context as? Activity)?.finish() }) {
                            Icon(Icons.Filled.Close, contentDescription = "关闭应用")
                        }
                    },
                    actions = {
                        // 右侧三条杠：下拉菜单（筛选 / 刷新）
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Filled.Menu, contentDescription = "菜单")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            containerColor = Color.White,
                        ) {
                            DropdownMenuItem(
                                text = { Text("刷新") },
                                leadingIcon = {
                                    Icon(Icons.Filled.Refresh, contentDescription = null, tint = Primary)
                                },
                                onClick = {
                                    menuExpanded = false
                                    showRefreshConfirm = true // 弹确认框，确认后才真正刷新
                                },
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = SurfaceBg,
                        titleContentColor = Color(0xFF1D1B20),
                    ),
                )
            }
        },
        bottomBar = {
            if (!inDetail) {
                NavigationBar(containerColor = SurfaceBg, tonalElevation = 0.dp) {
                    NavigationBarItem(
                        selected = currentTab == Tab.Download,
                        onClick = { currentTab = Tab.Download },
                        icon = { Icon(Icons.Filled.Download, contentDescription = null) },
                        label = { Text("下载") },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = Primary.copy(alpha = 0.18f),
                        ),
                    )
                    NavigationBarItem(
                        selected = currentTab == Tab.About,
                        onClick = { currentTab = Tab.About },
                        icon = { Icon(Icons.Filled.Info, contentDescription = null) },
                        label = { Text("关于") },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = Primary.copy(alpha = 0.18f),
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
                            Tab.About -> AboutScreen(
                                onContactClick = { showContactDialog = true },
                                onCheckUpdateClick = { /* 暂无功能，点击无反应 */ },
                            )
                        }
                    }
                }
            }
        }
    }
}
