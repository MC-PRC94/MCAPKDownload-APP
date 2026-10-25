package com.mcstation.download.data.model

/** 下载任务状态 */
enum class DownloadStatus {
    DOWNLOADING,
    DONE,
    FAILED,
    /** 已暂停：协程已停止，断点与分片文件保留，可随时继续 */
    PAUSED,
}

/**
 * 一个 APK 下载任务（支持多个版本同时下载）。
 */
data class DownloadTask(
    val id: Long,
    val version: McVersion,
    val fileName: String,
    val progress: Int = 0,
    val received: Long = 0,
    val total: Long = 0,
    /** 实时速度（字节/秒），由下载进度回调估算 */
    val speedBytesPerSec: Long = 0,
    val status: DownloadStatus = DownloadStatus.DOWNLOADING,
    val file: java.io.File? = null,
    /** 已调起过系统安装器（避免重复弹安装） */
    val installTriggered: Boolean = false,
    /** 安装完成后自动删除安装包（下载确认框勾选项） */
    val deleteAfterInstall: Boolean = true,
    /** 分段下载线程数（1~16），暂停后继续时沿用 */
    val threadCount: Int = 12,
) {
    /** 显示用短版本号 */
    val shortVersion: String get() = version.shortVersion
}
