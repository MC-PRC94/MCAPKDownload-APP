package com.mcstation.download.data.wiki

import com.mcstation.download.data.model.McVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 抓取中文 Minecraft Wiki 上的版本介绍句（如"26.51是基岩版的一次热修复更新……"）。
 *
 * 方案一（主）：OkHttp GET /w/{基岩版+版本号} 页面（带浏览器 User-Agent），
 *              Jsoup 解析 class="mw-parser-output" 的 div，遍历其中 <p> 标签，
 *              找第一段含"{版本号}是基岩版…更新"的文字，取其纯文本。
 * 方案二（兜底）：/w/ 页面有 Cloudflare 防护，可能直接 403；此时改走 MediaWiki 官方
 *              api.php 的 TextExtracts 接口拿纯文本导语，再正则提取目标句。
 * 两路都失败返回"获取失败"。
 */
object WikiInfoFetcher {

    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * 目标段判定：包含版本号，且同时提到"基岩版"和（"更新"或"测试版"）。
     * 不要求"{版本号}是基岩版"紧邻——句式多变，如：
     *   正式版："26.50是奔赴荒野的首个正式版，也是基岩版的一次小更新"
     *   测试版："beta 26.60.24（ChromeOS/Android）、Preview 26.60.24（Windows/Xbox）是基岩版26.60的第3个测试版"
     */
    private fun isIntroParagraph(versionNo: String, text: String): Boolean =
        text.contains(versionNo) && text.contains("基岩版") &&
            (text.contains("更新") || text.contains("测试版"))

    suspend fun fetchIntro(version: McVersion): String = withContext(Dispatchers.IO) {
        val versionNo = version.shortVersion
        fetchFromPage(versionNo) ?: fetchFromApi(versionNo) ?: "获取失败"
    }

    /** 方案一：抓 /w/ 页面 HTML，Jsoup 解析（用户指定方案） */
    private fun fetchFromPage(versionNo: String): String? = try {
        val url = wikiPageUrl(versionNo)
        val request = Request.Builder().url(url).header("User-Agent", BROWSER_UA).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val html = resp.body?.string() ?: return@use null
            val doc = Jsoup.parse(html)
            val output = doc.selectFirst("div.mw-parser-output") ?: return@use null
            // 只遍历 mw-parser-output 的直接 <p> 子节点（"此条目包含了…"等提示是 div，天然被排除）
            for (p in output.select("> p")) {
                val text = p.text()
                if (isIntroParagraph(versionNo, text)) return@use text
            }
            null
        }
    } catch (e: Exception) {
        null
    }

    /** 方案二：MediaWiki API TextExtracts 拿纯文本导语（绕开 Cloudflare 对页面的拦截） */
    private fun fetchFromApi(versionNo: String): String? = try {
        val url = "https://zh.minecraft.wiki/api.php?action=query&prop=extracts" +
            "&explaintext=1&exintro=1&redirects=1&format=json&titles=" +
            URLEncoder.encode("基岩版$versionNo", "UTF-8")
        val request = Request.Builder().url(url)
            .header("User-Agent", "MCDLApp/1.1 (Android)").build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val body = resp.body?.string() ?: return@use null
            val pages = org.json.JSONObject(body)
                .getJSONObject("query").getJSONObject("pages")
            val extract = pages.keys().asSequence()
                .map { pages.getJSONObject(it) }
                .firstOrNull { it.optString("extract").isNotBlank() }
                ?.optString("extract") ?: return@use null
            // 按行找第一段目标介绍（hatnote 之类不含版本号，天然被过滤）
            extract.split('\n').firstOrNull { isIntroParagraph(versionNo, it) }
                ?: if (isIntroParagraph(versionNo, extract)) extract.trim() else null
        }
    } catch (e: Exception) {
        null
    }

    /** Wiki 页面地址：https://zh.minecraft.wiki/w/基岩版{版本号}（中文做 URL 编码） */
    fun wikiPageUrl(versionNo: String): String =
        "https://zh.minecraft.wiki/w/" + URLEncoder.encode("基岩版$versionNo", "UTF-8")

    // ================= 发布日期提取 =================

    /** 日期匹配：形如"2026年9月15日" */
    private val datePattern = Regex("""\d{4}年\d{1,2}月\d{1,2}日""")

    /** 发布日期 = 导语里的最后一个日期（兜底用：可能不是 Android 平台的日期） */
    fun extractLastDate(intro: String): String? =
        datePattern.findAll(intro).lastOrNull()?.value

    /**
     * 抓取 Android 平台的发布日期（api.php 解析页面 wikitext，不走被 Cloudflare 拦的 /w/ 页面）。
     *
     * Infobox 的 |date= 字段两种形态（已实测）：
     *   1. 单一日期（所有平台同天）：|date=2026年9月15日
     *   2. 按平台分段：|date='''Windows''' - 2026年9月15日<br>'''ChromeOS/Android, Fire, ...''' - 2026年9月16日
     * 形态 2 取包含 "Android" 的分段里的日期。失败返回 null。
     */
    suspend fun fetchReleaseDate(versionNo: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = "https://zh.minecraft.wiki/api.php?action=parse&page=" +
                URLEncoder.encode("基岩版$versionNo", "UTF-8") +
                "&prop=wikitext&format=json&formatversion=2&redirects=1"
            val request = Request.Builder().url(url)
                .header("User-Agent", "MCDLApp/1.1 (Android)").build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                val wikitext = org.json.JSONObject(body)
                    .getJSONObject("parse").getString("wikitext")
                parseAndroidDate(wikitext)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Infobox 的 |date= 字段（跨行，直到下一个 | 开头的字段为止） */
    private val dateFieldRegex =
        Regex("""\|\s*date\s*=(.*?)(?=\n\|)""", RegexOption.DOT_MATCHES_ALL)

    private fun parseAndroidDate(wikitext: String): String? {
        val field = dateFieldRegex.find(wikitext)?.groupValues?.get(1)?.trim() ?: return null
        // 平台段以 <br>（其后可能跟换行）分隔，'''xxx''' 是平台列表
        val segments = field.split("<br>", "\n").map { it.replace("'''", "").trim() }
        segments.firstOrNull { it.contains("Android", ignoreCase = true) }?.let { seg ->
            return datePattern.find(seg)?.value
        }
        // 形态 1：没有平台分段，整个字段就是一个日期
        return if (field.contains("'''")) null else datePattern.find(field)?.value
    }
}
