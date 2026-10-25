package com.mcstation.download.data.api

import com.mcstation.download.data.log.AppLog
import com.mcstation.download.data.model.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用更新检查：请求 GitHub Releases。
 *
 * 两种模式：
 *  - [fetchLatestRelease]：GET .../releases/latest —— GitHub 只返回最新"正式版"，
 *    自动检查更新（启动时）用它，Pre-release 不会被打扰到。
 *  - [fetchLatestReleaseIncludingPrerelease]：GET .../releases?per_page=10 列表取第一个
 *    非 draft 的发布（正式版或 Pre-release，按创建时间倒序），手动"检查更新"按钮用它。
 *
 * 网络：api.github.com 在部分网络环境下不可达，因此按 [GitHubMirror.apiPrefixes]
 * 依次尝试加速节点，全部失败才回落 GitHub 直连。换源时**连解析一起重试** ——
 * 镜像被限流时返回的往往是 HTML 错误页而非 JSON。
 *
 * 返回（节选）：{ "tag_name": "v1.0.1", "prerelease": false, "draft": false, "body": "更新说明...",
 *              "assets": [ { "browser_download_url": "https://github.com/.../xxx.apk",
 *                            "digest": "sha256:..." }, ... ] }
 */
class GitHubUpdateApi(
    private val repoApiBase: String = "https://api.github.com/repos/MC-PRC94/MCAPKDownload-APP",
) {

    private companion object {
        const val TAG = "Update"
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** 最新正式版（releases/latest：GitHub 自动排除 Pre-release 与 draft） */
    suspend fun fetchLatestRelease(): UpdateInfo = withContext(Dispatchers.IO) {
        parseReleaseObject(getJson("/releases/latest").jsonObject)
    }

    /** 最新发布（含 Pre-release）：releases 列表第一个非 draft 条目 */
    suspend fun fetchLatestReleaseIncludingPrerelease(): UpdateInfo = withContext(Dispatchers.IO) {
        val arr = getJson("/releases?per_page=10") as? JsonArray
            ?: throw IOException("响应不是 Release 数组")
        val first = arr.firstNotNullOfOrNull { el ->
            val obj = el as? JsonObject
            val isDraft = (obj?.get("draft") as? JsonPrimitive)?.contentOrNull == "true"
            if (isDraft) null else obj
        } ?: throw IOException("仓库暂无可用 Release")
        parseReleaseObject(first)
    }

    // ---------- HTTP ----------

    /**
     * 依次尝试各加速源（镜像 → GitHub 直连），返回第一个能解析成合法 JSON 的响应。
     * 全部失败时抛出最后一次的错误，由调用方提示"检查更新失败"。
     */
    private fun getJson(path: String): JsonElement {
        val direct = "$repoApiBase$path"
        val sources = GitHubMirror.candidates(direct, GitHubMirror.apiPrefixes) + direct
        var lastError: Exception? = null
        for (source in sources) {
            val label = if (source == direct) "GitHub 直连" else source.substringBefore(direct)
            try {
                val element = json.parseToJsonElement(httpGet(source))
                AppLog.i(TAG, "更新检查成功（$label）")
                return element
            } catch (e: Exception) {
                lastError = e
                AppLog.e(TAG, "更新检查源失败（$label）：${e.message}")
            }
        }
        throw lastError ?: IOException("所有更新检查源均不可用")
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 10_000
            // 加速节点多为 302/301 中转，跟随跳转但限制层数，避免卡在不通的域名上
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "MCAPKDownload-APP")
        }
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code")
            return text
        } finally {
            conn.disconnect()
        }
    }

    // ---------- 解析 ----------

    private fun parseReleaseObject(root: JsonObject): UpdateInfo {
        val tagName = (root["tag_name"] as? JsonPrimitive)?.contentOrNull
            ?: throw IOException("响应缺少 tag_name")
        val description = (root["body"] as? JsonPrimitive)?.contentOrNull ?: ""
        val isPrerelease = (root["prerelease"] as? JsonPrimitive)?.contentOrNull == "true"
        val assets = root["assets"] as? JsonArray
        // 直链与 sha256 必须取自**同一个** asset，否则可能拿 A 的摘要去校验 B 的文件
        val asset = assets?.firstNotNullOfOrNull { el ->
            val obj = el as? JsonObject ?: return@firstNotNullOfOrNull null
            val url = (obj["browser_download_url"] as? JsonPrimitive)?.contentOrNull
            if (url.isNullOrEmpty()) null else obj to url
        }
        val downloadUrl = asset?.second.orEmpty()
        // digest 形如 "sha256:abcdef..."；老 Release 无此字段或为 null → 留空跳过校验
        val sha256 = (asset?.first?.get("digest") as? JsonPrimitive)?.contentOrNull
            ?.removePrefix("sha256:")?.trim()?.lowercase()
            ?.takeIf { it.length == 64 }
            .orEmpty()
        return UpdateInfo(
            tagName = tagName,
            body = description,
            downloadUrl = downloadUrl,
            prerelease = isPrerelease,
            sha256 = sha256,
        )
    }
}
