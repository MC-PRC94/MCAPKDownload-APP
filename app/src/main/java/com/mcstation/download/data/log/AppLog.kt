package com.mcstation.download.data.log

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量落盘日志：关键事件（下载开始/失败/完成、HTTP 错误码、APK 校验等）追加写入
 * Android/data/com.mcstation.download/files/logs/app.log，便于排查"下载失败/
 * 解析安装包失败"这类只有真机上才能复现的问题。
 *
 * 特性：
 *  - 超过 512KB 自动轮转（app.log -> app.log.old，最多保留两份）
 *  - 异常可附带堆栈（只取前 12 行，避免日志爆炸）
 *  - 全部写失败静默（日志永远不能影响功能）
 */
object AppLog {

    private const val MAX_SIZE = 512L * 1024
    private const val MAX_STACK_LINES = 12

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private var dir: File? = null

    /** 在 Application/Activity 创建时调用一次 */
    fun init(context: Context) {
        dir = try {
            File(context.getExternalFilesDir(null) ?: context.filesDir, "logs").apply { mkdirs() }
        } catch (_: Exception) {
            null
        }
    }

    /** 当前日志文件（可能不存在） */
    fun logFile(): File? = dir?.let { File(it, "app.log") }

    fun i(tag: String, msg: String) = write("I", tag, msg, null)

    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg, t)

    /** 读取全部日志文本（设置页"查看运行日志"用）；无日志返回提示文案 */
    fun readAll(): String {
        val f = logFile()?.takeIf { it.exists() } ?: return "（暂无日志）"
        return try {
            f.readText().ifEmpty { "（暂无日志）" }
        } catch (_: Exception) {
            "（日志读取失败）"
        }
    }

    /** 清空日志（设置页用） */
    fun clear() {
        try {
            logFile()?.delete()
            dir?.let { File(it, "app.log.old").delete() }
        } catch (_: Exception) {
        }
    }

    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        val d = dir ?: return
        val f = File(d, "app.log")
        try {
            // 轮转：超过上限时把当前日志改名为 .old 重新开始
            if (f.exists() && f.length() > MAX_SIZE) {
                val old = File(d, "app.log.old")
                old.delete()
                f.renameTo(old)
            }
            val line = buildString {
                append(timeFmt.format(Date()))
                append(' ').append(level).append('/').append(tag).append(": ").append(msg)
                if (t != null) {
                    appendLine()
                    append("  ").append(t.javaClass.name).append(": ").append(t.message)
                    t.stackTrace.take(MAX_STACK_LINES).forEach {
                        appendLine().append("    at ").append(it)
                    }
                }
                appendLine()
            }
            synchronized(this) { f.appendText(line) }
        } catch (_: Exception) {
            // 日志写入失败静默
        }
    }
}
