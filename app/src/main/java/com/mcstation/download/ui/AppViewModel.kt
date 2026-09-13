package com.mcstation.download.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcstation.download.data.VersionRepository
import com.mcstation.download.data.api.HttpVersionApi
import com.mcstation.download.data.cache.VersionCache
import com.mcstation.download.data.model.DownloadSource
import com.mcstation.download.data.model.McVersion
import kotlinx.coroutines.launch

/** 版本类型筛选 */
enum class VersionFilter(val label: String) {
    RELEASE("正式版"),
    BETA("测试版"),
    ALL("全部"),
}

/**
 * 全局 UI 状态。页面只读这里的值，逻辑都收敛在 ViewModel。
 *
 * 状态机：
 *   loading   = 正在获取版本列表...（全屏）
 *   loadError = 获取失败，请检查网络或稍后再试 + 重试按钮（仅在完全无数据可显示时）
 *   其他      = 正常列表
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = VersionRepository(
        api = HttpVersionApi(),
        cache = VersionCache(application),
    )

    var versions by mutableStateOf<List<McVersion>>(emptyList())
        private set
    var query by mutableStateOf("")
    var filterMode by mutableStateOf(VersionFilter.RELEASE) // 默认只看正式版
    var loading by mutableStateOf(true)
        private set
    var loadError by mutableStateOf(false)
        private set

    /** 一次性 Toast 提示（如"列表有缓存时刷新失败"） */
    var message by mutableStateOf<String?>(null)
        private set

    /** 搜索/类型筛选后的列表。搜索时始终搜索全部版本（无视类型筛选） */
    val filtered: List<McVersion>
        get() {
            val q = query.trim()
            if (q.isNotEmpty()) {
                return versions.filter {
                    it.version.contains(q, ignoreCase = true) ||
                        it.shortVersion.contains(q, ignoreCase = true) ||
                        (it.beta && (q.equals("beta", true) || q.contains("测试")))
                }
            }
            return when (filterMode) {
                VersionFilter.RELEASE -> versions.filter { !it.beta }
                VersionFilter.BETA -> versions.filter { it.beta }
                VersionFilter.ALL -> versions
            }
        }

    init {
        load()
    }

    /**
     * 加载版本列表。
     * @param forceRefresh true = 手动刷新（菜单"刷新"/"检查更新"/重试按钮），跳过 24h 缓存有效期
     */
    fun load(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            loading = true
            loadError = false
            try {
                versions = repository.loadVersions(forceRefresh)
                if (forceRefresh) message = "版本列表已刷新"
            } catch (e: Exception) {
                if (versions.isEmpty()) {
                    loadError = true // 完全没数据：全屏错误 + 重试
                } else {
                    message = "获取失败，请检查网络或稍后再试" // 还有旧数据可看：轻提示
                }
            }
            loading = false
        }
    }

    fun consumeMessage() {
        message = null
    }

    // ================= 下载链接 =================

    /** 正在获取下载链接... */
    var fetchingDownloads by mutableStateOf(false)
        private set
    /** 非 null 时弹出下载源对话框 */
    var downloadSources by mutableStateOf<List<DownloadSource>?>(null)
        private set
    /** 获取下载链接失败（对话框内显示错误 + 重试） */
    var downloadError by mutableStateOf(false)
        private set
    private var pendingDownloadVersion: String? = null

    /** 点击"下载 APK"后调用：请求该版本的网盘下载源 */
    fun requestDownloads(version: String) {
        pendingDownloadVersion = version
        viewModelScope.launch {
            fetchingDownloads = true
            downloadError = false
            try {
                downloadSources = repository.fetchDownloads(version)
            } catch (e: Exception) {
                downloadError = true
            }
            fetchingDownloads = false
        }
    }

    /** 下载源对话框请求失败后重试 */
    fun retryDownloads() {
        pendingDownloadVersion?.let { requestDownloads(it) }
    }

    /** 关闭下载源对话框 / 清除错误态 */
    fun dismissDownloads() {
        downloadSources = null
        downloadError = false
        fetchingDownloads = false
    }
}
