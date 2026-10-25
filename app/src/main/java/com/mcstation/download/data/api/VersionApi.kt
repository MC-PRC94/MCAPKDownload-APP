package com.mcstation.download.data.api

import com.mcstation.download.data.model.McVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 版本数据来源的统一接口。
 */
interface VersionApi {
    /** 拉取全部版本列表（原始数据，筛选在 UI 层做） */
    suspend fun fetchVersions(): List<McVersion>
}

/**
 * HTTP 实现。
 *
 * 接口返回结构（已实测确认）：
 *
 * get-vslist.php:
 * { "success": true, "data": { "total": 627, "versions": [ {"version","beta","date","size"}, ... ] }, "message": "获取成功" }
 */
class HttpVersionApi(
    private val versionsUrl: String = "https://mcapks.com/api/get-vslist.php",
) : VersionApi {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetchVersions(): List<McVersion> {
        val body = httpGet(versionsUrl)
        return parseVersions(body)
    }

    // ---------- HTTP ----------

    private suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code")
            body
        } finally {
            conn.disconnect()
        }
    }

    // ---------- 解析 ----------

    private fun parseVersions(body: String): List<McVersion> {
        val root = json.parseToJsonElement(body).jsonObject
        requireSuccess(root)
        val versions = (root["data"] as? JsonObject)?.get("versions") as? JsonArray
            ?: return emptyList()
        return versions.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val version = (o["version"] as? JsonPrimitive)?.contentOrNull
                ?: return@mapNotNull null
            McVersion(
                version = version,
                beta = (o["beta"] as? JsonPrimitive)?.booleanOrNull ?: false,
                date = (o["date"] as? JsonPrimitive)?.contentOrNull ?: "",
                size = (o["size"] as? JsonPrimitive)?.contentOrNull ?: "",
            )
        }
    }

    private fun requireSuccess(root: JsonObject) {
        val success = (root["success"] as? JsonPrimitive)?.booleanOrNull ?: false
        if (!success) throw IOException("接口返回 success=false: ${root["message"]}")
    }
}
