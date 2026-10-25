package com.mcstation.download.data.cache

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Wiki 版本发布日期的本地缓存（版本号 -> 日期字符串，如 "26.51" -> "2026年9月15日"）。
 *
 * 日期从 Wiki 导语里提取，抓取一次后落盘，之后打开详情页直接显示缓存值，
 * 不用再等网络请求。数据量小且不变（历史版本的发布日期不会变），无需过期机制。
 */
class WikiDateCache(context: Context) {

    @Serializable
    private data class Data(val dates: Map<String, String> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true }
    private val file: File = File(context.filesDir, "wiki_dates.json")

    // 启动时整体读入内存，之后读写都走内存，仅在新增时回写文件
    private var cache: Map<String, String> = load()

    private fun load(): Map<String, String> = try {
        if (file.exists()) json.decodeFromString<Data>(file.readText()).dates else emptyMap()
    } catch (e: Exception) {
        emptyMap() // 缓存损坏时当作没有缓存
    }

    /** 取某版本的缓存日期；没有返回 null */
    fun get(version: String): String? = cache[version]

    /** 保存（新增/覆盖一个版本号 -> 日期） */
    fun put(version: String, date: String) {
        if (cache[version] == date) return
        cache = cache + (version to date)
        try {
            file.writeText(json.encodeToString(Data(cache)))
        } catch (_: Exception) {
            // 写入失败不影响主流程，内存里仍然生效
        }
    }

    /** 清空缓存（设置页"清理缓存"用） */
    fun clear() {
        cache = emptyMap()
        try {
            file.delete()
        } catch (_: Exception) {
        }
    }
}
