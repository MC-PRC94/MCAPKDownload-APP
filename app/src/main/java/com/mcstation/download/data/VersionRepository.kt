package com.mcstation.download.data

import com.mcstation.download.data.api.VersionApi
import com.mcstation.download.data.cache.VersionCache
import com.mcstation.download.data.model.DownloadSource
import com.mcstation.download.data.model.McVersion

/**
 * 数据仓库：缓存优先，API 刷新，26.x 之外的旧版本不入缓存。
 *
 * 流程：
 *   启动 -> 读本地缓存 -> 未过期(24h)直接返回，不请求接口（避免高频请求）
 *        -> 过期/无缓存/手动刷新 -> 请求 API -> 截掉 26.x 之前的旧版本 -> 覆盖缓存 -> 更新 UI
 *        -> 请求失败 -> 有缓存则回退缓存，无缓存则抛异常（UI 显示错误+重试）
 *
 * 缓存策略：接口虽然返回 627 条全量历史，但**入库前只保留 26.x**，
 * 老版本（如 1.21.x）不落盘，省空间也省解析时间。
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
            val kept = filter26x(api.fetchVersions())
            if (kept.isNotEmpty()) {
                cache.save(kept) // 只缓存 26.x
                kept
            } else {
                cached // 接口异常返回空时保底用旧缓存
            }
        } catch (e: Exception) {
            if (cached.isNotEmpty()) cached else throw e
        }
    }

    /** 只保留 26.x 版本（"26.60.23" 保留，"1.21.94" 舍弃） */
    private fun filter26x(list: List<McVersion>): List<McVersion> =
        list.filter { it.version.substringBefore('.').toIntOrNull() == 26 }

    /**
     * 获取指定版本的网盘下载源，夸克/百度优先排序（稳定排序，其余保持接口原顺序）。
     * 不下载文件，只返回链接列表（点击后由系统浏览器打开）。
     */
    suspend fun fetchDownloads(version: String): List<DownloadSource> =
        api.fetchDownloads(version).sortedWith(
            compareBy(
                { !it.name.contains("夸克") },
                { !it.name.contains("百度") },
            ),
        )
}
