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
    /** 体积，如 "732.3 MB"（API 返回，可能不准，仅作兜底显示） */
    val size: String = "",
    /** 真实文件大小（字节）。由 HEAD 请求下载直链获取；0 = 尚未获取，显示时回退用 [size] */
    val realSize: Long = 0,
    /**
     * 去验证包（小布源）的真实大小（字节）。详情页优先显示它——用户多数选这个渠道；
     * 0 = 该版本没有去验证包，或尚未探测到。
     */
    val devSize: Long = 0,
    /** 下载链接（下载接口待接入，暂时留空） */
    val downloadUrl: String = "",
) {
    /**
     * 显示用版本号（正式版两个点、测试版三个点，截取规则按时代区分）：
     * - 测试版显示完整版本号（26.60.24 / 1.21.130.28，旧版本为三个点）；
     * - 正式版 26.x 时代：26.45.1 -> 26.45；
     * - 正式版 1.x 旧版本：1.21.132.1 -> 1.21.132（两个点），本身只有三段的（如 1.19.0）原样保留。
     * 请求接口、列表去重仍使用完整的 [version]。
     */
    val shortVersion: String
        get() {
            if (beta) return version
            val parts = version.split('.')
            return when {
                // 旧时代（1.x）：正式版保留前三段（1.21.132.1 -> 1.21.132）
                parts.first() == "1" ->
                    if (parts.size >= 4) parts.take(3).joinToString(".") else version
                // 新时代（26.x 等）：正式版保留前两段（26.45.1 -> 26.45）
                parts.size >= 3 -> parts.take(2).joinToString(".")
                else -> version
            }
        }
}

/**
 * GitHub Release 最新版本信息（api.github.com .../releases/latest 解析结果）。
 */
data class UpdateInfo(
    /** tag_name，如 "v1.0.1" */
    val tagName: String,
    /** body 里的更新说明 */
    val body: String,
    /** assets[0].browser_download_url，APK 下载直链 */
    val downloadUrl: String,
    /** true = Pre-release（预发布版）。自动检查只看正式版恒为 false，手动检查可能出现 */
    val prerelease: Boolean = false,
    /**
     * 该资产的 SHA-256（小写十六进制，来自 assets[].digest 的 "sha256:xxx"）。
     * 下载完成后按它校验安装包，防止加速节点返回旧版本/被篡改的文件；
     * 老 Release 没有 digest 字段时为空字符串（此时跳过校验）。
     */
    val sha256: String = "",
) {
    /** 去掉开头 v/V 后的纯版本号，如 "1.0.1" */
    val versionName: String get() = tagName.trim().removePrefix("v").removePrefix("V")
}
