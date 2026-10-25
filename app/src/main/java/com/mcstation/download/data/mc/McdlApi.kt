package com.mcstation.download.data.mc

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 小布のMC版本库（mcdl.xbu.hk）数据接口。
 *
 * 只用于取「去验证包」直链：
 *  - 版本目录：GET https://mcdl.xbu.hk/api/data （公开 JSON，无需登录/验证码）
 *  - 直链格式：https://{cdn}/1846098926/{id}，托管在 123 云盘 CDN
 *  - **防盗链**：不带 `Referer: https://mcdl.xbu.hk/` 会返回 403（code 1032），
 *    所以下载器必须带上这个头（[REFERER]）。
 *
 * 结构（节选）：
 *  - 26.12.2 起：`v8aFiles` 四包可选
 *  {"versions":{"stable":[{"version":"1.26.52.3","v8aFiles":[
 *      {"label":"原版 (APKS)","url":"…"},
 *      {"label":"去验证版","url":"…"}, …]}], "beta":[…]}}
 *  - 26.10 及以下：只有单链接 `v8a`，内容即去验证包
 *  {"versions":{"stable":[{"version":"1.26.10.27","v8a":"…"}]}}
 *
 * 可靠性策略（**失败绝不回退到别的下载渠道**，只在本源内部自愈）：
 *  1. 主站 + v6 镜像两套入口，任一可用即可；
 *  2. 每个入口重试 [ATTEMPTS_PER_HOST] 次，间隔递增；
 *  3. 整体 45 秒超时，避免长时间卡住；
 *  4. 缓存里查不到该版本时，强制刷新一次再找（可能是缓存过期或新版本刚上架）。
 *
 * 缓存 6 小时；取不到时返回 null，由调用方明确报错（不做跨渠道替换）。
 */
object McdlApi {

    /** 小布 CDN 的防盗链要求，所有下载/探测请求都要带 */
    const val REFERER = "https://mcdl.xbu.hk/"

    /** 主站与镜像（v6 内容一致），主站失败换镜像，不涉及渠道切换 */
    private val DATA_HOSTS = listOf("https://mcdl.xbu.hk", "https://v6.mcdl.xbu.hk")
    private const val ATTEMPTS_PER_HOST = 2

    /** 整套拉取流程的总超时，防止网络极差时一直转圈 */
    private const val TOTAL_TIMEOUT_MS = 45_000L

    private const val PREFS = "mcdl_api"
    private const val KEY_MAP = "version_map"
    private const val KEY_TIME = "saved_at"
    private const val MAX_AGE_MS = 6 * 60 * 60 * 1000L
    private const val CONNECT_TIMEOUT = 6_000
    private const val READ_TIMEOUT = 12_000

    /** 去验证包标签（优先按标签匹配，匹配不到取第 2 项） */
    private const val LABEL_DEV_VERIFIED = "去验证"

    @Volatile
    private var cached: Map<String, String>? = null

    /** 最近一次拉取失败的简述，供 UI 提示（成功时清空） */
    @Volatile
    var lastFailure: String? = null
        private set

    /**
     * 最近一次可用版本表的大小。
     * 0 = 一个版本都没拿到（接口/网络彻底不可用）；
     * >0 但查不到目标版本 = 接口正常、只是没有这个版本的去验证包。
     * 用来区分「源挂了」和「这个版本没有去验证包」两种情况。
     */
    @Volatile
    var lastMapSize: Int = 0
        private set

    /**
     * 取某版本的「去验证包」直链；没有该版本或网络失败返回 null。
     * @param force true = 忽略 6 小时缓存强制刷新
     */
    suspend fun devVerifiedUrl(context: Context, version: String, force: Boolean = false): String? {
        val key = version.trim()
        lookup(loadMap(context, force), key)?.let { return it }
        // 缓存里没有：可能缓存过期 / 该版本刚上架，强制刷新一次再找
        if (!force) {
            AppLogHolder.i("McdlApi", "缓存中无 $key 的去验证包，强制刷新重试")
            lookup(loadMap(context, force = true), key)?.let { return it }
        }
        return null
    }

    /** 精确匹配优先，再按归一化版本号匹配 */
    private fun lookup(map: Map<String, String>, key: String): String? {
        map[key]?.let { return it }
        val n = norm(key)
        return map.entries.firstOrNull { norm(it.key) == n }?.value
    }

    /** 版本号归一化：去掉开头的 "1."，让 1.26.52.3 与 26.52.3 视为同一版本 */
    private fun norm(v: String) = v.trim().removePrefix("1.").lowercase()

    /** 版本号 → 去验证包直链（缓存优先） */
    suspend fun loadMap(context: Context, force: Boolean = false): Map<String, String> {
        cached?.let { if (!force && it.isNotEmpty()) return it }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!force) {
            val savedAt = prefs.getLong(KEY_TIME, 0L)
            if (System.currentTimeMillis() - savedAt < MAX_AGE_MS) {
                val fromPrefs = decode(prefs.getString(KEY_MAP, null))
                if (fromPrefs.isNotEmpty()) {
                    cached = fromPrefs
                    lastMapSize = fromPrefs.size
                    return fromPrefs
                }
            }
        }

        // 阻塞式 HttpURLConnection 必须切到 IO 线程：调用方是 viewModelScope（Main），
        // 直接在 Main 上跑会抛 NetworkOnMainThreadException
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val fetched = kotlinx.coroutines.withTimeoutOrNull(TOTAL_TIMEOUT_MS) { fetchWithRetry() }
                ?: emptyMap()
            if (fetched.isNotEmpty()) {
                lastFailure = null
                cached = fetched
                lastMapSize = fetched.size
                prefs.edit()
                    .putString(KEY_MAP, encode(fetched))
                    .putLong(KEY_TIME, System.currentTimeMillis())
                    .apply()
                fetched
            } else {
                // 失败/空结果**不写 cached**：写过一次空 map 后，本进程后续请求会直接返回空、不再重试
                decode(prefs.getString(KEY_MAP, null)).also { lastMapSize = it.size }
            }
        }
    }

    /** 主站 → 镜像，每个入口重试若干次 */
    private suspend fun fetchWithRetry(): Map<String, String> {
        var lastError: String? = null
        for ((hostIndex, host) in DATA_HOSTS.withIndex()) {
            for (attempt in 1..ATTEMPTS_PER_HOST) {
                try {
                    val map = fetchAndParse("$host/api/data")
                    if (map.isNotEmpty()) return map
                    lastError = "接口返回空数据（$host）"
                    AppLogHolder.e("McdlApi", lastError)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = "${e.message}（$host 第 $attempt/$ATTEMPTS_PER_HOST 次）"
                    AppLogHolder.e("McdlApi", "拉取失败：$lastError", e)
                    val hasNext = attempt < ATTEMPTS_PER_HOST || hostIndex < DATA_HOSTS.lastIndex
                    if (hasNext) kotlinx.coroutines.delay(400L * attempt * (hostIndex + 1))
                }
            }
        }
        lastFailure = lastError
        return emptyMap()
    }

    private fun fetchAndParse(url: String): Map<String, String> {
        val origin = url.substringBefore("/api/data")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            setRequestProperty("Referer", "$origin/")
            setRequestProperty("Accept", "application/json")
        }
        val body = try {
            if (conn.responseCode !in 200..299) {
                throw java.io.IOException("HTTP ${conn.responseCode}")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }

        val root = JSONObject(body)
        val versions = root.optJSONObject("versions") ?: return emptyMap()
        val result = HashMap<String, String>()

        for (key in listOf("stable", "beta")) {
            val arr = versions.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val ver = item.optString("version").trim()
                if (ver.isEmpty()) continue
                val url = pickDevVerifiedUrl(item) ?: continue
                result[ver] = url
            }
        }
        return result
    }

    /**
     * 找该版本的「去验证包」直链。两种数据形态：
     *  1. 26.12.2 起：`v8aFiles` 有 4 项（原版 APKS / 去验证版 / 去音乐包 / 改路径去音乐），按标签取「去验证版」；
     *  2. 26.10 及以下：**只有 `v8a` 单链接**，没有 v8aFiles。站点在那一时期只放改包，
     *     实测该链接内容即去验证包（文件名 `Minecraft PEB <版本> v8a.apk`，内含 `@zihao_il` 声明），
     *     所以直接用 `v8a`，而不是当成“没有去验证包”。
     * 两者都没有才返回 null。
     */
    private fun pickDevVerifiedUrl(item: JSONObject): String? {
        val files = item.optJSONArray("v8aFiles")
        if (files != null && files.length() > 0) {
            var fallback: String? = null
            for (i in 0 until files.length()) {
                val f = files.optJSONObject(i) ?: continue
                val url = f.optString("url").trim().trimEnd('#')
                if (url.isEmpty()) continue
                val label = f.optString("label")
                if (label.contains(LABEL_DEV_VERIFIED)) return url
                if (i == 1) fallback = url
            }
            return fallback
        }
        // 旧版本：单链接 v8a 即去验证包
        return item.optString("v8a").trim().trimEnd('#').takeIf { it.isNotEmpty() }
    }

    // ---- 去验证包的文件大小 ----

    /**
     * 用 Range: bytes=0-0 探测去验证包的真实大小（只读 1 字节，不下载内容）。
     * 必须带 Referer，否则 CDN 直接 403。失败返回 0。
     */
    suspend fun devVerifiedSize(context: Context, version: String): Long {
        val url = devVerifiedUrl(context, version) ?: return 0L
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            probeSize(url)
        }
    }

    private fun probeSize(url: String): Long {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("Referer", REFERER)
                setRequestProperty("Range", "bytes=0-0")
            }
            try {
                when {
                    conn.responseCode !in 200..299 -> 0L
                    else -> {
                        // Content-Range: bytes 0-0/1073741824 → 取斜杠后的总长度
                        val range = conn.getHeaderField("Content-Range")
                        val total = range?.substringAfter('/', "")?.trim()?.toLongOrNull()
                        total?.takeIf { it > 0 }
                            ?: conn.getHeaderField("Content-Length")?.trim()?.toLongOrNull()?.takeIf { it > 0 }
                            ?: 0L
                    }
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogHolder.e("McdlApi", "探测去验证包大小失败：${e.message}", e)
            0L
        }
    }

    // ---- 简单缓存序列化（避免引额外依赖） ----

    private fun encode(map: Map<String, String>): String =
        JSONObject(map as Map<*, *>).toString()

    private fun decode(json: String?): Map<String, String> {
        if (json.isNullOrEmpty()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            val out = HashMap<String, String>(obj.length())
            obj.keys().forEach { k -> out[k] = obj.optString(k) }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** 日志门面：避免每个调用点写全限定名 */
    private object AppLogHolder {
        fun i(tag: String, msg: String) =
            com.mcstation.download.data.log.AppLog.i(tag, msg)

        fun e(tag: String, msg: String, e: Throwable? = null) =
            com.mcstation.download.data.log.AppLog.e(tag, msg, e)
    }
}
