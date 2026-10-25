package com.mcstation.download.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcstation.download.BuildConfig
import com.mcstation.download.data.StatementDoc
import com.mcstation.download.data.VersionRepository
import com.mcstation.download.data.api.GitHubMirror
import com.mcstation.download.data.api.GitHubUpdateApi
import com.mcstation.download.data.api.HttpVersionApi
import com.mcstation.download.data.api.StatementApi
import com.mcstation.download.data.cache.VersionCache
import com.mcstation.download.data.download.SegmentedApkDownloader
import com.mcstation.download.data.model.DownloadStatus
import com.mcstation.download.data.model.DownloadTask
import com.mcstation.download.data.model.McVersion
import com.mcstation.download.data.model.UpdateInfo
import com.mcstation.download.data.mc.DownloadChannel
import com.mcstation.download.data.mc.McdlApi
import com.mcstation.download.data.log.AppLog
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import java.io.File

/** 版本类型筛选 */
enum class VersionFilter(val label: String) {
    RELEASE("正式版"),
    BETA("测试版"),
    ALL("全部"),
}

/**
 * 所选渠道的下载源不可用。
 *
 * 用于**禁止跨渠道静默替换**：用户选了「去验证包」就只能下小布的包，
 * 取不到直链时必须明确报错，不能偷偷改成 MCPELIFE（否则用户以为下的是 A、装的是 B）。
 */
private class SourceUnavailableException(message: String) : Exception(message)

/**
 * 全局 UI 状态。页面只读这里的值，逻辑都收敛在 ViewModel。
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
            val base = dedupeByDisplay(versions)
            return when (filterMode) {
                VersionFilter.RELEASE -> base.filter { !it.beta }
                VersionFilter.BETA -> base.filter { it.beta }
                VersionFilter.ALL -> base
            }
        }

    /**
     * 正式版按显示版本号去重：API 里 1.21.51.01 与 1.21.51.02 都是正式版，显示都是 1.21.51，
     * 列表只保留较新的 1.21.51.02。测试版全号唯一，不受影响；分组后保持原有（日期倒序）排列。
     * 仅用于列表浏览；搜索路径不去重，保证按完整构建号（如 1.21.51.01）能搜到具体版本。
     */
    private fun dedupeByDisplay(list: List<McVersion>): List<McVersion> =
        list.groupBy { if (it.beta) it.version else it.shortVersion }
            .values
            .map { group ->
                if (group.size == 1) {
                    group.first()
                } else {
                    group.maxWithOrNull { x, y -> compareVersionNum(x.version, y.version) }
                        ?: group.first()
                }
            }

    /** 点分版本号数值比较（逐段比较，缺失段按 0）：用于去重时保留较新的构建 */
    private fun compareVersionNum(a: String, b: String): Int {
        val pa = a.split('.')
        val pb = b.split('.')
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i)?.toIntOrNull() ?: 0
            val y = pb.getOrNull(i)?.toIntOrNull() ?: 0
            if (x != y) return x - y
        }
        return 0
    }

    // ================= 已安装版本识别 =================

    /** 设备上已安装的 Minecraft 版本号（未安装为 null），如 "26.60.24" */
    var installedMinecraftVersion by mutableStateOf<String?>(null)
        private set

    // ================= Wiki 简介缓存（init 前声明：load()/预取协程会用到） =================

    private val wikiIntroCache = mutableMapOf<String, String>()

    /** 发布日期的本地缓存：抓取一次落盘，重启后直接显示，无需再请求 */
    private val wikiDateCache =
        com.mcstation.download.data.cache.WikiDateCache(getApplication())

    /** 版本介绍的本地缓存：抓取一次落盘，详情页秒开，无需再等网络 */
    private val wikiIntroDiskCache =
        com.mcstation.download.data.cache.WikiIntroCache(getApplication())

    /** 后台预取任务（进行中则不重复启动） */
    private var wikiPrefetchJob: kotlinx.coroutines.Job? = null

    // ---------- 声明联网更新 ----------
    // ⚠️ 这些属性必须声明在 init 之前：init 里会调 refreshStatement()，
    //    Kotlin 按声明顺序初始化，放在 init 后面会在启动时 NPE 崩溃（1.3.3-beta 首包踩过）。

    /** 声明正文：初始为本地内容（上次缓存 → 内置 assets），启动后联网刷新 */
    var statementText by mutableStateOf(StatementDoc.readAll(application))
        private set

    /** 声明正在联网拉取（刷新按钮置灰用） */
    var statementRefreshing by mutableStateOf(false)
        private set

    /** 自动拉取只在本进程生命周期内执行一次；手动刷新不受限制 */
    private var statementAutoFetched = false

    init {
        load()
        resumeInterruptedDownloads() // 恢复上次被中断的下载，自动断点续传
        cleanupInstalledUpdateApks() // 清理已安装完成的 APP 更新安装包
        detectInstalledMinecraft()   // 读取设备上已安装的 Minecraft 版本号
        refreshStatement()           // 每次打开应用后台拉一次最新声明（无时间间隔限制）
    }

    /**
     * 拉取最新声明：成功即写缓存并热更新 [statementText]（声明页实时跟随）。
     * 失败静默，保留原内容。
     *
     * @param manual 用户点了刷新按钮（true = 强制拉取，无视本进程已拉过）
     */
    fun refreshStatement(manual: Boolean = false) {
        if (statementRefreshing) return
        if (!manual && statementAutoFetched) return
        statementAutoFetched = true
        viewModelScope.launch {
            statementRefreshing = true
            try {
                val latest = StatementApi.fetchLatest()
                if (!latest.isNullOrBlank()) {
                    StatementDoc.saveCache(getApplication(), latest)
                    statementText = latest
                }
            } finally {
                statementRefreshing = false
            }
        }
    }

    /** 读取 com.mojang.minecraftpe 的版本号；未安装或读取失败返回 null */
    private fun detectInstalledMinecraft() {
        installedMinecraftVersion = try {
            getApplication<Application>().packageManager
                .getPackageInfo("com.mojang.minecraftpe", 0)
                .versionName
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 某版本是否已安装在设备上：完整匹配 或 已装版本是该版本的前缀（26.51 ⊆ 26.51.1）。
     * 设备上的 versionName 可能带大版本前缀 "1."（如 "1.26.51.1"），而列表版本号是 "26.51"，
     * 因此原始值和去掉开头 "1." 的值都参与匹配，兼容新旧两种编号体系。
     */
    fun isVersionInstalled(version: McVersion): Boolean {
        val raw = installedMinecraftVersion ?: return false
        val candidates = listOf(raw, raw.removePrefix("1.")).distinct()
        return candidates.any {
            it == version.version || it.startsWith(version.version + ".")
        }
    }

    /**
     * 清理已安装的更新安装包。
     * 更新包安装的是本应用自身，安装完成瞬间进程即被系统结束，无法当场删除；
     * 因此在下次启动时检查 update/ 目录：安装包版本号与当前版本一致 = 已装上，直接删除。
     * 版本号不一致（含已取消安装的）的安装包保留，之后"立即下载"时可直接复用无需重新下载。
     */
    private fun cleanupInstalledUpdateApks() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val dir = getApplication<Application>().getExternalFilesDir("update") ?: return@launch
            val current = BuildConfig.VERSION_NAME
            dir.listFiles { f -> f.isFile && f.name.startsWith("MCDLAPP-") && f.name.endsWith(".apk") }
                ?.forEach { f ->
                    val tag = f.name.removePrefix("MCDLAPP-").removeSuffix(".apk")
                    // 只删"正是当前已装版本"的安装包：归一化后精确比对（去 v 前缀、忽略大小写）
                    val tagNorm = tag.trim().removePrefix("v").removePrefix("V").trim().lowercase()
                    val curNorm = current.trim().removePrefix("v").removePrefix("V").trim().lowercase()
                    if (tagNorm == curNorm) f.delete()
                }
        }
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
                com.mcstation.download.data.log.AppLog.i("List", "版本列表加载完成：${versions.size} 条（force=$forceRefresh）")
                prefetchWikiIntros(versions) // 后台预取简介+日期，详情页秒开
                if (forceRefresh) message = "版本列表已刷新"
            } catch (e: Exception) {
                com.mcstation.download.data.log.AppLog.e("List", "版本列表加载失败：${e.message}", e)
                if (versions.isEmpty()) {
                    loadError = true // 完全没数据：全屏错误 + 重试
                } else {
                    message = "获取失败，请检查网络或稍后再试" // 还有旧数据可看：轻提示
                }
            }
            loading = false

            // 异步补全真实文件大小：HEAD 请求下载直链（只取响应头不下载），完成后静默更新列表与缓存
            if (versions.isNotEmpty()) {
                launch {
                    versions = repository.refreshRealSizes(versions, force = forceRefresh)
                }
            }
        }
    }

    /** 详情页打开时补查单个版本的真实大小（列表异步刷新可能尚未覆盖到） */
    fun refreshSizeFor(versionStr: String) {
        val target = versions.firstOrNull { it.version == versionStr } ?: return
        if (target.realSize > 0 && target.devSize > 0) return
        viewModelScope.launch {
            val updated = repository.refreshSizeFor(target, getApplication())
            versions = versions.map { if (it.version == versionStr) updated else it }
        }
    }

    fun consumeMessage() {
        message = null
    }

    // ================= APK 下载（mcpelife 直链，8 线程分段 + 断点续传 + 顶栏角标） =================

    /** 拼接 mcpelite 下载直链：
     *  完整版本号 26.51.1 -> WV = "1-26-51-1"
     *  https://dl.mcpelife.com/minecraft-pe/{WV}/minecraft-{WV 横杠变点}-arm64-v8a.apk
     */
    fun buildDownloadUrl(fullVersion: String): String {
        val wv = "1-" + fullVersion.trim().replace('.', '-')
        return "https://dl.mcpelife.com/minecraft-pe/$wv/minecraft-${wv.replace('-', '.')}-arm64-v8a.apk"
    }

    /** 全部下载任务（下载中 / 完成 / 失败），顶栏下载列表显示用 */
    var downloadTasks by mutableStateOf<List<DownloadTask>>(emptyList())
        private set

    /** 正在下载的任务数（顶栏角标数字） */
    val activeDownloadCount: Int
        get() = downloadTasks.count { it.status == DownloadStatus.DOWNLOADING }

    private var nextTaskId = 1L
    private val activeJobs = mutableMapOf<Long, kotlinx.coroutines.Job>()

    private fun updateTask(id: Long, transform: (DownloadTask) -> DownloadTask) {
        downloadTasks = downloadTasks.map { if (it.id == id) transform(it) else it }
    }

    /**
     * 点击"下载 APK"后调用（先经过下载确认框）：
     * @param threadCount 分段下载线程数（1 = 单线程，最大 16，越界自动收敛）
     * @param deleteAfterInstall true = 安装完成后自动删除安装包
     */
    fun startDownload(
        version: McVersion,
        channel: com.mcstation.download.data.mc.DownloadChannel = com.mcstation.download.data.mc.DownloadChannel.MCPELIFE,
        threadCount: Int = 8,
        deleteAfterInstall: Boolean = true,
    ) {
        // 同一版本已在下载中则忽略
        if (downloadTasks.any { it.version.version == version.version && it.status == DownloadStatus.DOWNLOADING }) {
            message = "该版本正在下载中"
            return
        }
        // 失败/完成的旧记录先移除（失败任务的断点文件保留，新任务会自动续传）
        downloadTasks = downloadTasks.filter { it.version.version != version.version }
        val id = nextTaskId++
        val wv = "1-" + version.version.trim().replace('.', '-')
        // 文件名带渠道后缀：不同渠道的包内容不同，断点分片不能混用，否则续传会拼出坏包
        val channelSuffix = if (channel == com.mcstation.download.data.mc.DownloadChannel.MCPELIFE) "" else "-${channel.name.lowercase()}"
        val fileName = "minecraft-${wv.replace('-', '.')}-arm64-v8a$channelSuffix.apk"
        // 旧任务若处于暂停集合里，重新下载即视为继续，从暂停集合移除
        com.mcstation.download.data.cache.DownloadPrefs.removePausedTask(getApplication(), fileName)
        downloadTasks = downloadTasks + DownloadTask(
            id = id,
            version = version,
            fileName = fileName,
            deleteAfterInstall = deleteAfterInstall,
            threadCount = threadCount.coerceIn(1, 16),
        )
        launchDownload(id, version, channel, fileName, threadCount.coerceIn(1, 16))
        com.mcstation.download.data.log.AppLog.i(
            "Download",
            "开始下载 ${version.version}（${channel.title}，${threadCount.coerceIn(1, 16)} 线程）",
        )
    }

    /** 渠道解析结果：直链 + 需要的 Referer（小布 CDN 有防盗链） */
    private data class ResolvedSource(
        val url: String,
        val referer: String,
        val channel: com.mcstation.download.data.mc.DownloadChannel,
    )

    /**
     * 按渠道解析下载直链。
     *
     * **严格按用户所选渠道，绝不跨渠道回退**：
     * 选了去验证包就只能下小布的包，取不到直链就抛 [SourceUnavailableException]
     * 明确报错，而不是偷偷换成 MCPELIFE（否则用户以为下的是 A，实际装的是 B）。
     */
    private suspend fun resolveSource(
        version: McVersion,
        channel: com.mcstation.download.data.mc.DownloadChannel,
    ): ResolvedSource {
        when (channel) {
            DownloadChannel.DEV_VERIFIED -> {
                val url = McdlApi.devVerifiedUrl(getApplication(), version.version)
                if (url.isNullOrEmpty()) {
                    AppLog.e(
                        "Download",
                        "标准去验证包源不可用：${version.version}，版本表条数=${McdlApi.lastMapSize}，" +
                            "最后一次错误=${McdlApi.lastFailure ?: "无该版本的标准去验证包"}",
                    )
                    throw SourceUnavailableException(
                        if (McdlApi.lastMapSize > 0) {
                            // 接口正常，只是这个版本没有去验证包：不换渠道，让用户自己决定
                            "${version.shortVersion} 暂无标准去验证包，请选择其他版本或渠道"
                        } else {
                            "标准去验证包下载源暂时不可用（已自动重试主站与镜像），请稍后再试"
                        },
                    )
                }
                AppLog.i("Download", "使用小布标准去验证包：${version.version} $url")
                return ResolvedSource(url, McdlApi.REFERER, channel)
            }

            DownloadChannel.OFFICIAL -> throw SourceUnavailableException(
                "官方包渠道暂未开放，请选择其他渠道",
            )

            DownloadChannel.MCPELIFE -> {
                val url = buildDownloadUrl(version.version)
                AppLog.i("Download", "使用 MCPELIFE 直链：${version.version} $url")
                return ResolvedSource(url, "", channel)
            }
        }
    }

    /** 启动一个下载任务（新任务 / 断点续传共用） */
    private fun launchDownload(
        id: Long,
        version: McVersion,
        channel: com.mcstation.download.data.mc.DownloadChannel,
        fileName: String,
        threadCount: Int = SegmentedApkDownloader.DEFAULT_PARTS,
        /** 恢复任务时直接给定已解析的源（task.json 里存的就是最终直链），不走渠道解析 */
        presetSource: ResolvedSource? = null,
    ) {
        val job = viewModelScope.launch {
            try {
                val source = presetSource ?: resolveSource(version, channel)
                // MCPELIFE 的两种直链格式都尝试：拼接直链不可用时从站点下载中转页解析真实链接；
                // 小布直链本身就是最终地址，不需要再解析
                val finalUrl = if (source.channel == com.mcstation.download.data.mc.DownloadChannel.MCPELIFE) {
                    resolveDownloadUrl(version, source.url)
                } else {
                    source.url
                }
                // 速度采样：滑动窗口取近 2 秒平均，避免瞬时值抖动
                val speedMeter = SpeedMeter()
                val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    SegmentedApkDownloader(
                        context = getApplication(),
                        url = finalUrl,
                        fileName = fileName,
                        version = version,
                        threadCount = threadCount,
                        referer = source.referer,
                        onResumed = { resumed ->
                            // 检测到断点：提示已续传
                            message = "检测到未完成任务，已从断点继续（已下载 ${"%.1f".format(resumed / 1048576.0)} MB）"
                        },
                    ).download { received, total ->
                        val speed = speedMeter.speed(System.currentTimeMillis(), received)
                        updateTask(id) { t ->
                            t.copy(
                                received = received,
                                total = total,
                                progress = if (total > 0) {
                                    (received * 100 / total).toInt().coerceIn(0, 100)
                                } else t.progress,
                                speedBytesPerSec = if (speed >= 0) speed else t.speedBytesPerSec,
                            )
                        }
                    }
                }
                updateTask(id) {
                    it.copy(
                        status = DownloadStatus.DONE,
                        progress = 100,
                        file = file,
                        speedBytesPerSec = 0,
                    )
                }
                com.mcstation.download.data.log.AppLog.i(
                    "Download",
                    "下载完成 ${version.version}（${file.length()} 字节）",
                )
                showDoneNotification(file, version.shortVersion)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 任务被取消：手动取消时已在 cancelDownload 中清理断点文件；
                // 暂停不抛出取消（见 pauseDownload），这里只处理真取消
                throw e
            } catch (e: Exception) {
                updateTask(id) { it.copy(status = DownloadStatus.FAILED, speedBytesPerSec = 0) }
                com.mcstation.download.data.log.AppLog.e(
                    "Download",
                    "下载失败 ${version.version}：${e.message}",
                    e,
                )
                val failureMsg = when {
                    // 渠道源不可用：明确告知，不做跨渠道替换
                    e is SourceUnavailableException -> e.message ?: "下载源暂时不可用"
                    // 404 = 源站没有这个版本的安装包（如 26.33），给出明确提示而不是笼统的"失败"
                    e.message?.contains("HTTP 404") == true ->
                        "${version.shortVersion} 的安装包在源站暂未提供（404），请稍后再试或选择其他版本"
                    else -> null
                }
                if (failureMsg != null) message = failureMsg
                // 断点与分段文件保留：再次点击"下载 APK"自动从断点续传
            }
            activeJobs.remove(id)
        }
        activeJobs[id] = job
    }

    // ================= 直链解析（拼接直链 + 下载中转页两种格式都尝试） =================

    /**
     * 解析真实下载直链：
     *  1. 先探测拼接直链（minecraft-pe/1-{版本}/minecraft-1.{版本}-arm64-v8a.apk，多数版本可用）
     *  2. 不可用（如 26.33 用新命名 26-33/minecraft-26-33-01.apk）时，从 mcpelife 的
     *     下载中转页（mcpelife.com/minecraft-pe-{slug}/download/1/）解析页面里的真实 APK 链接
     *  都失败时回退拼接直链（由下载器报 404 明确提示）。
     */
    private suspend fun resolveDownloadUrl(version: McVersion, directUrl: String): String {
        val directOk = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            probeUrlOk(directUrl)
        }
        if (directOk) return directUrl

        com.mcstation.download.data.log.AppLog.i(
            "Download",
            "拼接直链不可用，改从下载中转页解析真实链接：${version.version}",
        )
        val v = version.version.trim()
        // 页面 slug 有多种形态（站点新旧命名不统一），按命中率依次尝试：
        // 完整版本号（1-20-71-01 / 26-60-28）、去掉末段（1-21-132 / 26-33）、
        // 旧式 1- 前缀（1-26-60-23）、前两段（26-51）
        val parts = v.split('.')
        val slugs = buildList {
            add("minecraft-pe-" + parts.joinToString("-"))
            if (parts.size >= 3) add("minecraft-pe-" + parts.dropLast(1).joinToString("-"))
            add("minecraft-pe-1-" + parts.joinToString("-"))
            if (parts.size >= 2) add("minecraft-pe-" + parts.take(2).joinToString("-"))
        }.distinct()
        for (slug in slugs) {
            // 每个页面试 download/1 与 download/2（1 的链接偶尔过期，2 是备用包）
            for (page in listOf("1", "2")) {
                val link = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    scrapeDownloadLink("https://mcpelife.com/$slug/download/$page/")
                } ?: continue
                val linkOk = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    probeUrlOk(link)
                }
                if (linkOk) {
                    com.mcstation.download.data.log.AppLog.i(
                        "Download",
                        "从 $slug 中转页 download/$page 解析到可用链接：$link",
                    )
                    return link
                }
                com.mcstation.download.data.log.AppLog.e(
                    "Download",
                    "$slug 中转页 download/$page 的链接已失效（$link），继续尝试",
                )
            }
        }
        com.mcstation.download.data.log.AppLog.e(
            "Download",
            "下载中转页也未找到安装包链接：${version.version}，回退拼接直链",
        )
        return directUrl
    }

    /** 探测 URL 是否可用（1 字节 Range 请求，200/206 均可用） */
    private fun probeUrlOk(url: String): Boolean = try {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/vnd.android.package-archive,*/*")
            conn.setRequestProperty("Range", "bytes=0-0")
            conn.responseCode in 200..299
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        false
    }

    /** 抓取 mcpelife 下载中转页，提取页面里的 dl.mcpelife.com APK 直链；失败返回 null */
    private fun scrapeDownloadLink(pageUrl: String): String? = try {
        val conn = java.net.URL(pageUrl).openConnection() as java.net.HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.instanceFollowRedirects = true
            // 页面在 Cloudflare 后面，用浏览器 UA 避免被拦截
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
            if (conn.responseCode in 200..299) {
                val html = conn.inputStream.bufferedReader().readText()
                Regex("""https://dl\.mcpelife\.com/[^"'<>)\s]+\.apk""").find(html)?.value
            } else {
                null
            }
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    /** 暂停下载：停止协程但保留断点与分片文件，任务标记为 PAUSED，可随时继续 */
    fun pauseDownload(id: Long) {
        val task = downloadTasks.firstOrNull { it.id == id } ?: return
        activeJobs.remove(id)?.cancel()
        updateTask(id) { it.copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0) }
        com.mcstation.download.data.cache.DownloadPrefs.addPausedTask(getApplication(), task.fileName)
    }

    /** 继续已暂停的下载：沿用原线程数，下载器会自动从断点续传 */
    fun resumeDownload(id: Long) {
        val task = downloadTasks.firstOrNull { it.id == id } ?: return
        if (task.status != DownloadStatus.PAUSED) return
        com.mcstation.download.data.cache.DownloadPrefs.removePausedTask(getApplication(), task.fileName)
        updateTask(id) { it.copy(status = DownloadStatus.DOWNLOADING) }
        val channel = if (task.fileName.contains("-dev_verified.")) {
            DownloadChannel.DEV_VERIFIED
        } else {
            DownloadChannel.MCPELIFE
        }
        launchDownload(task.id, task.version, channel, task.fileName, task.threadCount)
    }

    /** 手动取消并移除某个下载任务：等下载线程停止后，清除断点进度与全部分段临时文件（下次需重新下载） */
    fun cancelDownload(id: Long) {
        val task = downloadTasks.firstOrNull { it.id == id } ?: return
        val job = activeJobs.remove(id)
        downloadTasks = downloadTasks.filter { it.id != id }
        com.mcstation.download.data.cache.DownloadPrefs.removePausedTask(getApplication(), task.fileName)
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            job?.cancelAndJoin() // 等所有分段线程真正退出，不再写盘，再删文件
            cleanupDownloadFiles(task.fileName)
        }
    }

    /** 移除列表中的任务；未完成的任务等线程停止后同时清理断点文件 */
    fun removeDownloadTask(id: Long) {
        val task = downloadTasks.firstOrNull { it.id == id } ?: return
        downloadTasks = downloadTasks.filter { it.id != id }
        if (task.status != DownloadStatus.DONE) {
            com.mcstation.download.data.cache.DownloadPrefs.removePausedTask(getApplication(), task.fileName)
            val job = activeJobs.remove(id)
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                job?.cancelAndJoin()
                cleanupDownloadFiles(task.fileName)
            }
        }
    }

    /** 删除某任务的断点状态与全部分段临时文件（线程数 1~16 可变，按前缀扫描删除所有 .p 分片） */
    private fun cleanupDownloadFiles(fileName: String) {
        val dir = getApplication<Application>().getExternalFilesDir("download") ?: return
        File(dir, "$fileName.task.json").delete()
        File(dir, "$fileName.task.json.tmp").delete()
        dir.listFiles { f -> f.isFile && f.name.startsWith("$fileName.p") }?.forEach { it.delete() }
    }

    /** 启动时扫描断点状态文件，恢复被中断的下载任务并自动从断点续传；用户主动暂停的任务保持暂停 */
    private fun resumeInterruptedDownloads() {
        val context = getApplication<Application>()
        val paused = com.mcstation.download.data.cache.DownloadPrefs.pausedTasks(context)
        val dir = getApplication<Application>().getExternalFilesDir("download") ?: return
        val states = dir.listFiles { f -> f.isFile && f.name.endsWith(".task.json") } ?: return
        for (state in states) {
            try {
                val json = org.json.JSONObject(state.readText())
                val fileName = json.getString("fileName")
                val url = json.getString("url")
                val version = McVersion(
                    version = json.getString("version"),
                    beta = json.getBoolean("beta"),
                    date = json.optString("date"),
                    size = json.optString("size"),
                )
                // 列表里已有同版本任务（如用户刚手动点过）则不重复恢复
                if (downloadTasks.any { it.version.version == version.version }) continue
                val id = nextTaskId++
                if (fileName in paused) {
                    // 用户主动暂停：保持暂停状态，等用户点"继续"
                    downloadTasks = downloadTasks + DownloadTask(
                        id = id, version = version, fileName = fileName,
                        status = DownloadStatus.PAUSED,
                    )
                } else {
                    downloadTasks = downloadTasks + DownloadTask(id = id, version = version, fileName = fileName)
                    // task.json 里存的是已经解析好的最终直链，直接沿用，不再走渠道解析
                    val isMcdl = url.contains("123clouddisk")
                    launchDownload(
                        id = id,
                        version = version,
                        channel = if (isMcdl) DownloadChannel.DEV_VERIFIED else DownloadChannel.MCPELIFE,
                        fileName = fileName,
                        presetSource = ResolvedSource(
                            url = url,
                            referer = if (isMcdl) McdlApi.REFERER else "",
                            channel = if (isMcdl) DownloadChannel.DEV_VERIFIED else DownloadChannel.MCPELIFE,
                        ),
                    )
                }
            } catch (e: Exception) {
                // 状态文件损坏：跳过（不影响其他任务）
            }
        }
    }

    /** 下载完成后由 UI 调起系统安装器，并标记已调起（避免重复弹窗） */
    fun markInstallTriggered(id: Long) {
        updateTask(id) { it.copy(installTriggered = true) }
    }

    // ================= 下载文件管理（download/ 目录文件列表 + 删除） =================

    /** download/ 目录下的全部文件（文件管理界面显示用） */
    var downloadFiles by mutableStateOf<List<File>>(emptyList())
        private set

    /** 刷新文件列表 */
    fun refreshDownloadFiles() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val dir = getApplication<Application>().getExternalFilesDir("download")
            downloadFiles = dir?.listFiles()
                ?.filter { it.isFile }
                ?.sortedWith(compareBy<File> { !it.name.endsWith(".apk") }.thenBy { it.name.lowercase() })
                ?: emptyList()
        }
    }

    /** 删除单个文件并刷新列表 */
    fun deleteDownloadFile(file: File) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            file.delete()
            downloadFiles = downloadFiles.filterNot { it.absolutePath == file.absolutePath }
        }
    }

    /** 是否为下载器的重要文件：分段临时文件（.p0~.p7）与断点续传进度文件（.task.json/.tmp） */
    fun isImportantDownloadFile(file: File): Boolean {
        val name = file.name
        if (name.endsWith(".task.json") || name.endsWith(".task.json.tmp")) return true
        return Regex("\\.p\\d+$").containsMatchIn(name)
    }

    // ================= 版本介绍（Wiki 导语句抓取） =================

    /** 获取 Wiki 版本介绍句的完整流程见上方 prefetchWikiIntros/getWikiIntro */

    /**
     * 后台预取所有版本的 Wiki 简介与发布日期：列表加载完后自动调用。
     * 只抓没有缓存的版本，3 路并发控制请求频率，全部写入磁盘缓存；
     * 用户点进详情时通常已缓存，直接秒开。
     */
    private fun prefetchWikiIntros(versions: List<McVersion>) {
        if (wikiPrefetchJob?.isActive == true) return
        wikiPrefetchJob = viewModelScope.launch {
            val sem = kotlinx.coroutines.sync.Semaphore(3)
            versions.map { v ->
                launch {
                    sem.withPermit {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            if (wikiIntroCache[v.version] == null &&
                                wikiIntroDiskCache.get(v.version) == null
                            ) {
                                try {
                                    getWikiIntro(v)
                                } catch (_: Exception) {
                                    // 预取失败不打扰用户：详情页打开时会再试
                                }
                            }
                        }
                    }
                }
            }.joinAll()
        }
    }

    /** 版本发布日期的缓存值；没有返回 null（详情页用它做初始显示） */
    fun getCachedDate(versionNo: String): String? = wikiDateCache.get(versionNo)

    /** 获取 Wiki 版本介绍句（内存缓存 -> 磁盘缓存 -> 网络）；抓到的同时提取发布日期落盘 */
    suspend fun getWikiIntro(version: McVersion): String {
        wikiIntroCache[version.version]?.let { return it }
        wikiIntroDiskCache.get(version.version)?.let {
            wikiIntroCache[version.version] = it
            return it
        }
        val text = com.mcstation.download.data.wiki.WikiInfoFetcher.fetchIntro(version)
        wikiIntroCache[version.version] = text
        wikiIntroDiskCache.put(version.version, text)
        // 发布日期：优先取 Wiki 信息框里 Android 平台的日期（准确），
        // 取不到再退回导语最后一个日期；写入本地缓存并覆盖旧值（修正历史错误日期）
        val releaseDate =
            com.mcstation.download.data.wiki.WikiInfoFetcher.fetchReleaseDate(version.shortVersion)
                ?: com.mcstation.download.data.wiki.WikiInfoFetcher.extractLastDate(text)
        releaseDate?.let { wikiDateCache.put(version.version, it) }
        return text
    }

    /** 清理缓存（设置页）：Wiki 日期/介绍缓存 + 版本列表缓存，随后重新加载列表 */
    fun clearCaches() {
        wikiDateCache.clear()
        wikiIntroDiskCache.clear()
        wikiIntroCache.clear()
        repository.clearCache()
        load()
        message = "缓存已清理"
    }

    /**
     * Minecraft PE 安装成功后由 MainActivity 的系统广播回调：
     * 删除勾选了"安装完后删除安装包"的已完成任务的安装包，并从下载列表移除。
     */
    fun onMinecraftInstalled() {
        val toDelete = downloadTasks.filter {
            it.status == DownloadStatus.DONE && it.deleteAfterInstall && it.file != null
        }
        if (toDelete.isEmpty()) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            toDelete.forEach { task ->
                task.file?.delete()
                cleanupDownloadFiles(task.fileName)
            }
        }
        val removedIds = toDelete.map { it.id }.toSet()
        downloadTasks = downloadTasks.filter { it.id !in removedIds }
    }

    // ================= 检查更新 =================

    private val updateApi = GitHubUpdateApi()

    /** 正在检查更新... */
    var checkingUpdate by mutableStateOf(false)
        private set
    /** 非 null 时弹出"发现新版本"对话框 */
    var updateInfo by mutableStateOf<UpdateInfo?>(null)
        private set

    /**
     * 检查更新。
     * [includePrerelease] = false（默认）：只查正式版 Release —— 启动自动检查用；
     * true：同时查 Pre-release（releases 列表最新一条非 draft）—— 设置页手动检查用。
     */
    fun checkForUpdate(includePrerelease: Boolean = false) {
        if (checkingUpdate) return
        viewModelScope.launch {
            checkingUpdate = true
            try {
                val release = if (includePrerelease) {
                    updateApi.fetchLatestReleaseIncludingPrerelease()
                } else {
                    updateApi.fetchLatestRelease()
                }
                if (isNewerVersion(release.versionName, BuildConfig.VERSION_NAME)) {
                    updateInfo = release // 远端比当前新：弹"发现新版本"对话框
                } else {
                    message = "已是最新版本"
                }
            } catch (e: Exception) {
                message = "检查更新失败，请检查网络"
            }
            checkingUpdate = false
        }
    }

    /** 关闭"发现新版本"对话框 */
    fun dismissUpdate() {
        updateInfo = null
    }

    // ================= 更新包应用内下载（Release 直链，4 线程分段） =================

    /** 非 null 时表示有更新包下载任务在进行/失败/完成 */
    var updateDownload by mutableStateOf<UpdateDownloadState?>(null)
        private set

    private var updateDownloadJob: kotlinx.coroutines.Job? = null

    /** 点击"立即下载"后调用：4 线程下载更新包，完成后由 UI 调起系统安装器。
     *  默认依次尝试各加速节点（GitHubMirror.filePrefixes），全部失败才回落 GitHub 直连；
     *  preferMirror=false 时直连优先，失败再逐个换加速节点。
     *  [sha256] 非空时在下载完成后校验安装包摘要，不匹配则删除文件并继续换源
     *  —— 加速节点可能缓存了旧版本、或返回被篡改的文件。
     */
    fun startUpdateDownload(
        url: String,
        tagName: String,
        preferMirror: Boolean = true,
        sha256: String = "",
    ) {
        if (updateDownload?.status == DownloadStatus.DOWNLOADING) return
        val safeTag = tagName.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
        val fileName = "MCDLAPP-$safeTag.apk"
        val mirrors = mirrorUrls(url)
        val candidates = if (preferMirror) mirrors + url else listOf(url) + mirrors
        updateDownload = UpdateDownloadState(status = DownloadStatus.DOWNLOADING, fileName = fileName)
        val pseudoVersion = McVersion(version = safeTag, beta = false)
        com.mcstation.download.data.log.AppLog.i(
            "Update",
            "开始下载更新包 $tagName，候选源=${candidates.joinToString(" | ")}",
        )
        updateDownloadJob = viewModelScope.launch {
            var file: java.io.File? = null
            // 是否出现过"摘要校验不通过"：所有源都失败时用来给出准确的失败原因
            var checksumFailed = false
            for ((index, candidate) in candidates.withIndex()) {
                val isMirror = candidate != url
                if (index > 0) {
                    // 换源：断点数据按 URL 校验不可混用，清掉分片与状态文件从头下
                    message = if (isMirror) {
                        "下载源失败，正在切换其他加速节点重试（${index + 1}/${candidates.size}）"
                    } else {
                        "加速节点下载失败，正在切换 GitHub 直连重试"
                    }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        cleanupUpdateFiles(fileName)
                    }
                }
                updateDownload = UpdateDownloadState(
                    status = DownloadStatus.DOWNLOADING,
                    fileName = fileName,
                    usedMirror = isMirror,
                )
                try {
                    // 速度采样：滑动窗口取近 2 秒平均，避免瞬时值抖动
                    val speedMeter = SpeedMeter()
                    file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        SegmentedApkDownloader(
                            context = getApplication(),
                            url = candidate,
                            fileName = fileName,
                            version = pseudoVersion,
                            threadCount = 4,
                            subDir = "update",
                            expectedSha256 = sha256,
                        ).download { received, total ->
                            val speed = speedMeter.speed(System.currentTimeMillis(), received)
                            updateDownload = (updateDownload ?: UpdateDownloadState(DownloadStatus.DOWNLOADING)).copy(
                                received = received,
                                total = total,
                                progress = if (total > 0) (received * 100 / total).toInt().coerceIn(0, 100) else 0,
                                speedBytesPerSec = if (speed >= 0) speed else (updateDownload?.speedBytesPerSec ?: 0L),
                            )
                        }
                    }
                    break // 本源下载成功
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e // 用户取消：直接退出，不换源
                } catch (e: Exception) {
                    if (e.message?.startsWith(SegmentedApkDownloader.CHECKSUM_ERROR_PREFIX) == true) {
                        checksumFailed = true
                    }
                    com.mcstation.download.data.log.AppLog.e(
                        "Update",
                        "源下载失败（${if (isMirror) "镜像" else "直链"}）$tagName：${e.message}",
                        e,
                    )
                    // 本源失败：还有候选源则继续循环换源重试
                }
            }
            if (file != null) {
                updateDownload = updateDownload?.copy(
                    status = DownloadStatus.DONE,
                    progress = 100,
                    speedBytesPerSec = 0,
                    file = file,
                )
                com.mcstation.download.data.log.AppLog.i(
                    "Update",
                    "更新包下载完成 $tagName（${file?.length()} 字节）",
                )
                updateInfo = null // 下载完成：收起"发现新版本"对话框，由安装器接管
            } else {
                updateDownload = updateDownload?.copy(status = DownloadStatus.FAILED, speedBytesPerSec = 0)
                if (checksumFailed) message = "更新包校验未通过，已中止安装（可稍后重试）"
                com.mcstation.download.data.log.AppLog.e("Update", "更新包全部候选源均失败 $tagName")
            }
        }
    }

    /**
     * GitHub 加速候选地址：在 GitHub 直链前拼镜像前缀即可加速下载（已实测支持 Range 分段）。
     * 节点列表见 [GitHubMirror.filePrefixes]；仅对 github.com 直链生效，其他地址返回空列表（无镜像可换）。
     */
    private fun mirrorUrls(url: String): List<String> =
        GitHubMirror.candidates(url, GitHubMirror.filePrefixes)

    /** 下载失败后重试：上次走直链失败则优先镜像，上次走镜像失败则优先直链，从断点续传 */
    fun retryUpdateDownload() {
        val info = updateInfo ?: return
        if (info.downloadUrl.isNotEmpty()) {
            val preferMirror = updateDownload?.usedMirror == false
            startUpdateDownload(info.downloadUrl, info.tagName, preferMirror, info.sha256)
        }
    }

    /**
     * 取消更新包下载：不保留断点进度。
     * 更新包文件很小，先等待下载线程完全停止（cancelAndJoin），再删除
     * 全部分片临时文件与断点状态文件，避免线程收尾时又把进度写回来。
     */
    fun cancelUpdateDownload() {
        val job = updateDownloadJob
        updateDownloadJob = null
        val fileName = updateDownload?.fileName
        updateDownload = null
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            job?.cancelAndJoin() // 等所有分段线程真正退出，不再写盘
            if (fileName != null) cleanupUpdateFiles(fileName)
        }
    }

    /** 更新包下载完成后由 UI 调起系统安装器，并标记已调起（避免重复弹窗） */
    fun markUpdateInstallTriggered() {
        updateDownload = updateDownload?.copy(installTriggered = true)
    }

    /** 删除更新包的断点状态与分片临时文件 */
    private fun cleanupUpdateFiles(fileName: String) {
        val dir = getApplication<Application>().getExternalFilesDir("update") ?: return
        File(dir, "$fileName.task.json").delete()
        File(dir, "$fileName.task.json.tmp").delete()
        repeat(4) { File(dir, "$fileName.p$it").delete() }
    }

    /** 清除失败状态的更新下载记录（配合"关闭"按钮） */
    fun clearUpdateDownload() {
        updateDownloadJob?.cancel()
        updateDownloadJob = null
        updateDownload = null
    }

    // ================= 下载完成系统通知 =================

    private val NOTIFICATION_CHANNEL_ID = "download_done"

    /**
     * 下载完成后发系统通知，点击直接调起系统安装器。
     * Android 13+ 需要通知权限（在开始下载时由 UI 请求）；未授权时静默跳过。
     */
    private fun showDoneNotification(file: java.io.File, shortVersion: String) {
        val context = getApplication<Application>()
        try {
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    android.app.NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        "下载完成",
                        android.app.NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply { description = "版本安装包下载完成提醒" }
                )
            }
            // 点击通知 → 调起系统安装器（与下载完成后的自动安装一致）
            com.mcstation.download.data.log.AppLog.i(
                "Install",
                "通知点击调起安装器：${file.name}（${file.length()} 字节，路径 ${file.absolutePath}）",
            )
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val installIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                )
            }
            val pendingIntent = android.app.PendingIntent.getActivity(
                context,
                file.absolutePath.hashCode(),
                installIntent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = androidx.core.app.NotificationCompat.Builder(
                context,
                NOTIFICATION_CHANNEL_ID,
            )
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("$shortVersion 下载完成")
                .setContentText("点击安装 ${shortVersion} 版本安装包")
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
            manager.notify(file.absolutePath.hashCode(), notification)
        } catch (_: Exception) {
            // 通知失败（如未授权）不影响下载流程
        }
    }

    /**
     * remote 是否比 current 更新（用于决定是否弹"发现新版本"）。
     *
     * 取版本号主体（去掉 v/V 前缀、去掉 "-beta-2" 这类测试版后缀）按数字逐段比较：
     *   - remote 主体更大 → 有更新
     *   - 主体相同且 remote 是正式版、current 是测试版 → 正式版已发布，也算有更新
     *   - 其余情况（remote 更小 / 完全相同 / remote 是测试版而 current 是同号正式版）→ 无更新
     *
     * 之前只比较字符串是否相等：beta 包（如 1.3.3-beta-2）会被旧正式版（v1.3.2）劝降，
     * 2026-10-24 在模拟器里实测发现。
     */
    private fun isNewerVersion(remote: String, current: String): Boolean {
        fun parse(v: String): Pair<List<Int>, String> {
            val t = v.trim().removePrefix("v").removePrefix("V").trim()
            val core = t.substringBefore('-').substringBefore(' ').trimEnd('.')
            val suffix = if (t.length > core.length) t.substring(core.length).lowercase() else ""
            val nums = core.split('.').map { seg -> seg.filter { it.isDigit() }.ifEmpty { "0" }.toInt() }
            return nums to suffix
        }
        val (remoteNums, remoteSuffix) = parse(remote)
        val (currentNums, currentSuffix) = parse(current)
        for (i in 0 until maxOf(remoteNums.size, currentNums.size)) {
            val r = remoteNums.getOrElse(i) { 0 }
            val c = currentNums.getOrElse(i) { 0 }
            if (r != c) return r > c
        }
        return remoteSuffix.isBlank() && currentSuffix.isNotBlank()
    }
}

    /** 更新包下载状态（应用内下载 Release APK） */
    data class UpdateDownloadState(
        val status: DownloadStatus,
        /** 下载写入的文件名（update/ 目录下），取消清理时用 */
        val fileName: String = "",
        val received: Long = 0,
        val total: Long = 0,
        val progress: Int = 0,
        /** 实时速度（字节/秒），由下载进度回调估算 */
        val speedBytesPerSec: Long = 0,
        val file: File? = null,
        val installTriggered: Boolean = false,
        /** 本次下载是否走的 ghproxy 镜像（失败重试时交替换源用） */
        val usedMirror: Boolean = false,
    )

    /**
     * 速度计：滑动窗口取近 windowMs 毫秒的 (时间, 累计字节) 样本算平均速度，
     * 避免瞬时采样抖动。窗口不足 minWindowMs 时返回 -1（调用方沿用上一次速度）。
     */
    private class SpeedMeter(
        private val windowMs: Long = 2000,
        private val minWindowMs: Long = 500,
    ) {
        private val samples = ArrayDeque<Pair<Long, Long>>()
        fun speed(now: Long, received: Long): Long {
            samples.addLast(now to received)
            val earliest = now - windowMs
            while (samples.size > 1 && samples.first().first < earliest) samples.removeFirst()
            val (t0, b0) = samples.first()
            val dt = now - t0
            return if (dt >= minWindowMs) (received - b0) * 1000 / dt else -1L
        }
    }
