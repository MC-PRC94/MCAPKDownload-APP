package com.mcstation.download.data.api

/**
 * GitHub 加速节点（gh-proxy 类：在完整 GitHub 地址前拼前缀即可加速）。
 *
 * 选点依据：2026-10 用本仓库 v1.2.4 的 18.7MB Release 资产与 releases API 实测
 * （1MB 分段请求，记录状态码 / 实收字节 / 速度 / 首字节耗时）。只保留「支持
 * Range 206 分段」的节点 —— APP 是多线程断点续传下载，节点若忽略 Range 会直接破坏分块。
 *
 * 实测结果：
 *   github.tbap.top    628KB/s 首字节0.31s  文件✓  API✗(403)
 *   tvv.tw             755KB/s 首字节0.58s  文件✓  API✓
 *   cdn.akaere.online  144KB/s 首字节1.47s  文件✓  API✓
 *   gh-proxy.com        89KB/s 首字节0.71s  文件✓  API✓
 *   ghproxy.net          8KB/s 首字节5.22s  文件✓  API✗  ← 原内置节点，高峰期已劣化
 *   git.yylx.win            忽略 Range（返回 200 全量）→ 无法用于分段下载
 *   cdn.gh-proxy.com        302 跳转 cdn.gh-proxy.org（HttpURLConnection 会跟到不通的域名，卡死）
 *   ghm.078465.xyz / ghfile.geekertao.top / github.dpik.top / 其余 → 超时、429 或不支持 API
 *
 * 两类节点分开维护：部分节点只做文件加速，不放开 api.github.com 透传。
 */
object GitHubMirror {

    /** 更新检查用（api.github.com 透传）：实测能返回 GitHub API JSON 的节点，按速度排序 */
    val apiPrefixes: List<String> = listOf(
        "https://tvv.tw/",
        "https://cdn.akaere.online/",
        "https://gh-proxy.com/",
    )

    /** 更新包下载用（github.com 资产直链）：实测支持 Range 分段且速度可用的节点 */
    val filePrefixes: List<String> = listOf(
        "https://github.tbap.top/",
        "https://tvv.tw/",
        "https://cdn.akaere.online/",
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
    )

    /**
     * raw 纯文本用（raw.githubusercontent.com → 声明等小文本）。
     *
     * 选点依据：2026-10 用 github/gitignore 的 README.md 实测各节点对 raw 域名的透传，
     * 记录状态码 / 实收字节 / 耗时。**注意 raw 直连在多数国内线路完全不可达（实测返回 000），
     * jsDelivr 也不可用（cdn.jsdelivr.net 不通，fastly 会 301 跳到不通的域名）**，
     * 所以这份列表是声明更新的唯一可用通道，不能删空。
     *
     * 实测（5 个节点全部 200，字节数与源站一致）：
     *   cdn.akaere.online  0.90s
     *   tvv.tw             1.07s
     *   github.tbap.top    1.12s
     *   ghproxy.net        1.27s
     *   gh-proxy.com       2.32s
     */
    val rawPrefixes: List<String> = listOf(
        "https://cdn.akaere.online/",
        "https://tvv.tw/",
        "https://github.tbap.top/",
        "https://ghproxy.net/",
        "https://gh-proxy.com/",
    )

    /** 是否为可加速的 GitHub 地址（其他域名一律不改写） */
    fun supports(url: String): Boolean =
        url.startsWith("https://github.com/") ||
            url.startsWith("https://api.github.com/") ||
            url.startsWith("https://raw.githubusercontent.com/")

    /**
     * 给 GitHub 地址拼出全部镜像候选（**不含**直连地址，直连由调用方决定放在首位还是兜底）。
     * 非 GitHub 地址返回空列表。
     */
    fun candidates(url: String, prefixes: List<String>): List<String> =
        if (supports(url)) prefixes.map { it + url } else emptyList()
}
