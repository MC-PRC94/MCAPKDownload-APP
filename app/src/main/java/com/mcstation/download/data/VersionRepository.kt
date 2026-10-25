package com.mcstation.download.data

import com.mcstation.download.data.api.VersionApi
import com.mcstation.download.data.cache.VersionCache
import com.mcstation.download.data.model.McVersion
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 数据仓库：缓存优先，API 刷新，全量历史版本。
 *
 * 流程：
 *   启动 -> 读本地缓存 -> 未过期(24h)直接返回，不请求接口（避免高频请求）
 *        -> 过期/无缓存/手动刷新 -> 请求 API -> 覆盖缓存 -> 更新 UI
 *        -> 请求失败 -> 有缓存则回退缓存，无缓存则抛异常（UI 显示错误+重试）
 *
 * 缓存策略：接口返回 600+ 条全量历史（26.x 正式列表 + 1.x 全部旧版本），
 * 全量入库不再过滤（2026-10-25 起，支持下载远古版本）。
 */
class VersionRepository(
    private val api: VersionApi,
    private val cache: VersionCache,
) {
    /** @param forceRefresh true = 手动刷新，跳过缓存有效期直接请求接口 */
    suspend fun loadVersions(forceRefresh: Boolean = false): List<McVersion> {
        val cached = cache.load() // 缓存里本身就是筛过的 26.x
        val cacheUsable = cached.isNotEmpty() &&
            System.currentTimeMillis() - cache.savedAt() < VersionCache.MAX_AGE_MS

        if (!forceRefresh && cacheUsable) return cached

        return try {
            val kept = api.fetchVersions()
            if (kept.isNotEmpty()) {
                cache.save(kept) // 缓存全量历史（26.x 与 1.x 全部旧版本）
                kept
            } else {
                cached // 接口异常返回空时保底用旧缓存
            }
        } catch (e: Exception) {
            if (cached.isNotEmpty()) cached else throw e
        }
    }

    /** 清空版本列表缓存（设置页"清理缓存"用） */
    fun clearCache() = cache.clear()

    // ================= 真实文件大小（HEAD 探测，不下载文件） =================

    /** 拼接 mcpelife 下载直链（与下载器使用的规则一致） */
    fun buildDownloadUrl(fullVersion: String): String {
        val wv = "1-" + fullVersion.trim().replace('.', '-')
        return "https://dl.mcpelife.com/minecraft-pe/$wv/minecraft-${wv.replace('-', '.')}-arm64-v8a.apk"
    }

    /**
     * 用 HEAD 请求补全各版本下载直链的真实文件大小（只取响应头，不下载内容）。
     *
     * 消耗控制：
     *  - 只请求 [need] 里的版本：手动刷新(force=true)全部重查，否则只查还没有真实大小的版本
     *  - 4 路并发（Semaphore），每个请求 5s 超时，HEAD 被服务器拒绝时退化为 1 字节 Range 探测
     *  - 成功的结果写回缓存（保留原缓存时间戳），下次无需重复请求
     *
     * @return 补全大小后的新版本列表（请求全部失败时原样返回）
     */
    suspend fun refreshRealSizes(
        versions: List<McVersion>,
        force: Boolean = false,
    ): List<McVersion> {
        if (versions.isEmpty()) return versions
        val need = if (force) versions else versions.filter { it.realSize <= 0L }
        if (need.isEmpty()) return versions

        val semaphore = kotlinx.coroutines.sync.Semaphore(4)
        val results = kotlinx.coroutines.coroutineScope {
            need.map { v ->
                async(kotlinx.coroutines.Dispatchers.IO) {
                    semaphore.withPermit {
                        v.version to headFileSize(buildDownloadUrl(v.version))
                    }
                }
            }.awaitAll()
        }
        val sizeMap = results.filter { it.second > 0L }.toMap()
        if (sizeMap.isEmpty()) return versions

        val updated = versions.map { v ->
            sizeMap[v.version]?.let { v.copy(realSize = it) } ?: v
        }
        cache.savePreservingTime(updated)
        return updated
    }

    /** 单个版本的真实大小查询（详情页即时补查用）；失败返回原对象 */
    suspend fun refreshSizeFor(version: McVersion, context: android.content.Context): McVersion {
        if (version.realSize > 0 && version.devSize > 0) return version
        val mcpSize = if (version.realSize > 0) version.realSize else
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                headFileSize(buildDownloadUrl(version.version))
            }
        // 去验证包大小：详情页显示用，取不到（该版本没有去验证包）就保持 0
        val devSize = if (version.devSize > 0) version.devSize else
            com.mcstation.download.data.mc.McdlApi.devVerifiedSize(context, version.version)
        if (mcpSize <= 0 && devSize <= 0) return version
        val updated = version.copy(realSize = mcpSize.coerceAtLeast(0), devSize = devSize)
        // 同步写回缓存（按版本号匹配替换）
        val cached = cache.load().map { if (it.version == version.version) updated else it }
        if (cached.any { it.version == version.version }) cache.savePreservingTime(cached)
        return updated
    }

    /**
     * 探测直链文件大小：先 HEAD（只收响应头，最省流量），
     * 拿不到 Content-Length 或被拒绝时，退化为 Range: bytes=0-0 的 1 字节请求读 Content-Range。
     * 失败返回 0。
     */
    private fun headFileSize(url: String): Long = try {
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = 5_000
            readTimeout = 5_000
            setRequestProperty("Accept", "application/vnd.android.package-archive,*/*")
        }
        try {
            val code = conn.responseCode
            if (code in 200..299) {
                val len = conn.contentLengthLong
                if (len > 0) len else probeSizeByRange(url)
            } else {
                probeSizeByRange(url)
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        probeSizeByRange(url)
    }

    /** 1 字节 Range 探测：从 Content-Range: bytes 0-0/总大小 里解析总大小 */
    private fun probeSizeByRange(url: String): Long = try {
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 5_000
            setRequestProperty("Range", "bytes=0-0")
            setRequestProperty("Accept", "application/vnd.android.package-archive,*/*")
        }
        try {
            if (conn.responseCode == 206) {
                conn.getHeaderField("Content-Range")
                    ?.substringAfterLast('/')?.toLongOrNull() ?: 0L
            } else 0L
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        0L
    }
}
