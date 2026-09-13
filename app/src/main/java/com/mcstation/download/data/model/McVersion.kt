package com.mcstation.download.data.model

import kotlinx.serialization.Serializable

/**
 * 一个 Minecraft 版本条目。
 * 字段名与 API 返回（data.versions 数组元素）保持一致，可直接序列化/反序列化。
 */
@Serializable
data class McVersion(
    /** 版本号，如 "26.60.23" */
    val version: String,
    /** true = 测试版，false = 正式版 */
    val beta: Boolean = false,
    /** 发布日期，如 "2026-09-09" */
    val date: String = "",
    /** 体积，如 "732.3 MB" */
    val size: String = "",
    /** 下载链接（下载接口待接入，暂时留空） */
    val downloadUrl: String = "",
) {
    /**
     * 显示用版本号：如果版本号有两个及以上的点，把第二个点和后面的截掉。
     * 例：26.45.1 -> 26.45；26.45 -> 26.45
     * 请求接口、列表去重仍使用完整的 [version]。
     */
    val shortVersion: String
        get() {
            val first = version.indexOf('.')
            if (first == -1) return version
            val second = version.indexOf('.', first + 1)
            return if (second == -1) version else version.substring(0, second)
        }
}

/**
 * 一个网盘下载源（来自 get-download.php 的 data.downloads 数组）。
 * 示例：{"name":"夸克网盘","url":"https://pan.quark.cn/s/xxx"} / {"name":"百度网盘","url":"...","password":"KLPZ"}
 */
@Serializable
data class DownloadSource(
    /** 网盘名称，如 "夸克网盘" */
    val name: String,
    /** 网盘分享链接 */
    val url: String,
    /** 提取码（部分源才有） */
    val password: String = "",
)
