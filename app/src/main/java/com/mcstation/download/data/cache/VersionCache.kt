package com.mcstation.download.data.cache

import android.content.Context
import com.mcstation.download.data.model.McVersion
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** 缓存文件结构：带保存时间，用于判断是否过期（避免高频请求） */
@Serializable
private data class CacheData(val savedAt: Long, val versions: List<McVersion>)

/**
 * 本地 JSON 文件缓存。
 *
 * 策略：API 拉取成功后整体覆盖写入；启动时先读缓存，
 * 缓存未过期（24 小时内）则不再请求接口 —— 对应"避免高频请求/每日自动刷新"。
 */
class VersionCache(context: Context) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file: File = File(context.filesDir, "versions_cache.json")

    fun load(): List<McVersion> = try {
        if (file.exists()) json.decodeFromString<CacheData>(file.readText()).versions else emptyList()
    } catch (e: Exception) {
        emptyList() // 缓存损坏时当作没有缓存
    }

    /** 上次成功保存的时间戳；无缓存返回 0 */
    fun savedAt(): Long = try {
        if (file.exists()) json.decodeFromString<CacheData>(file.readText()).savedAt else 0L
    } catch (e: Exception) {
        0L
    }

    fun save(versions: List<McVersion>) = try {
        file.writeText(json.encodeToString(CacheData(System.currentTimeMillis(), versions)))
    } catch (_: Exception) {
        // 写入失败不影响主流程
    }

    fun clear() = file.delete()

    companion object {
        /** 缓存有效期：24 小时 */
        const val MAX_AGE_MS: Long = 24 * 60 * 60 * 1000L
    }
}
