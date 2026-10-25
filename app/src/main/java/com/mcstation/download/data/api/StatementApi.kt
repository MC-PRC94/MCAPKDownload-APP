package com.mcstation.download.data.api

import com.mcstation.download.data.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 远程声明文本拉取（GitHub raw）。
 *
 * 文件放在 App 发布仓库里：
 *   https://raw.githubusercontent.com/MC-PRC94/MCAPKDownload-APP/main/legal/statement.txt
 *
 * 为什么必须走镜像：
 *   2026-10 实测，raw.githubusercontent.com **直连在国内多数线路返回 000（完全不可达）**；
 *   jsDelivr 同样不可用（cdn.jsdelivr.net 不通，fastly.jsdelivr.net 会 301 跳到不通的域名）。
 *   所以按 [GitHubMirror.rawPrefixes] 依次尝试镜像，全部失败才回落直连（给海外用户兜底）。
 *
 * 三道校验（缺一不可，全部通过才认为拿到了**有效的声明正文**）：
 *   1. HTTP 200
 *   2. 字节数在 (0, MAX_BYTES] 之间 —— 防止镜像返回空响应或超大文件
 *   3. 首行必须是 [MAGIC] —— 镜像被限流时返回的是 HTML 错误页而不是 404，
 *      没有这行标记就会把报错页面当成声明渲染出来（更新检查接口踩过同样的坑）
 *
 * 失败时返回 null（不抛异常），由调用方保留上一次的缓存。
 */
object StatementApi {

    private const val TAG = "Statement"

    /** 声明文件的 GitHub raw 地址 */
    private const val RAW_URL =
        "https://raw.githubusercontent.com/MC-PRC94/MCAPKDownload-APP/main/legal/statement.txt"

    /**
     * 文件首行固定标识，**改声明内容时这一行必须原样保留**。
     * 末尾的 v1 是格式版本号：将来排版标记有语义变化时递增，
     * App 遇到不认识的版本可以整体降级成纯文本，避免把旧客户端搞乱。
     */
    const val MAGIC = "<!-- mcstation-statement v1 -->"

    /** 声明正文体积上限（正常 4~8KB，512KB 足够宽松，防止误拉到别的东西） */
    private const val MAX_BYTES = 512 * 1024

    /**
     * 拉取最新声明正文。
     *
     * @return 去掉 [MAGIC] 首行后的正文；所有源都失败时返回 null
     */
    suspend fun fetchLatest(): String? = withContext(Dispatchers.IO) {
        val sources = GitHubMirror.candidates(RAW_URL, GitHubMirror.rawPrefixes) + RAW_URL
        for (source in sources) {
            val label = if (source == RAW_URL) "直连" else source.substringBefore(RAW_URL)
            try {
                val body = httpGet(source)
                AppLog.i(TAG, "声明拉取成功（$label），${body.length} 字")
                return@withContext body
            } catch (e: Exception) {
                AppLog.e(TAG, "声明拉取源失败（$label）：${e.message}")
            }
        }
        AppLog.e(TAG, "全部声明源均不可用，沿用本地内容")
        null
    }

    /**
     * 请求并校验，返回剥离 MAGIC 行后的正文。
     * 任何一项校验不通过都抛 [IOException]，交给上层换下一个源。
     */
    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            // 某些镜像不带 UA 会直接拒绝
            setRequestProperty("User-Agent", "MCAPKDownload-APP")
            setRequestProperty("Accept", "text/plain")
            // 声明会改，不希望中间层长时间缓存旧版本
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")

            // 先看 Content-Length，超限直接放弃，避免真的把大文件读进内存
            val declared = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            if (declared > MAX_BYTES) throw IOException("文件过大（$declared B）")

            val buf = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val chunk = ByteArray(8 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    if (buf.size() > MAX_BYTES) throw IOException("响应超过上限")
                }
            }
            val bytes = buf.toByteArray()
            if (bytes.isEmpty()) throw IOException("响应为空")

            // 按 UTF-8 解码时必须严格，否则说明拿到的不是我们那份纯文本
            val text = String(bytes, Charsets.UTF_8)
            if (text.contains('\uFFFD')) throw IOException("不是合法的 UTF-8 文本")

            val normalized = text.replace("\r\n", "\n").trim()
            // 镜像限流页/错误页（HTML）在这里被拦下
            if (!normalized.startsWith(MAGIC)) throw IOException("缺少文件标记，疑似错误页")

            return normalized.removePrefix(MAGIC).trimStart('\n').trimEnd()
        } finally {
            conn.disconnect()
        }
    }
}
