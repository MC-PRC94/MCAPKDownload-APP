package com.mcstation.download.data.crash

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃捕获：应用闪退时把完整堆栈写入外部私有目录（crash_last.txt），
 * 下次启动时由 MainActivity 优先弹出崩溃详情对话框（此时不组合主界面，
 * 保证"一进去就闪退"的情况下用户仍能看到、复制崩溃信息反馈给开发者）。
 *
 * 日志文件位于 Android/data/com.mcstation.download/files/crash_last.txt，
 * 用户也可用系统文件管理器直接取出。
 */
object CrashReporter {

    private const val FILE_NAME = "crash_last.txt"

    /** 在 MainActivity.onCreate 最先调用：接管默认未捕获异常处理器 */
    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val text = buildString {
                    // 版本号动态读取当前安装包信息，自动跟随内部版本号
                    val pkg = try {
                        context.packageManager.getPackageInfo(context.packageName, 0)
                    } catch (_: Exception) {
                        null
                    }
                    val code = if (android.os.Build.VERSION.SDK_INT >= 28) {
                        pkg?.longVersionCode ?: 0L
                    } else {
                        @Suppress("DEPRECATION")
                        pkg?.versionCode?.toLong() ?: 0L
                    }
                    appendLine("应用版本：${pkg?.versionName ?: "未知"} ($code)")
                    appendLine(
                        "时间：" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date()),
                    )
                    appendLine("线程：${thread.name}")
                    appendLine("异常：${throwable.javaClass.name}: ${throwable.message}")
                    appendLine()
                    append(sw.toString())
                }
                crashFile(context).writeText(text)
                // 同步记入运行日志（AppLog 已初始化时生效）
                com.mcstation.download.data.log.AppLog.e(
                    "Crash",
                    "未捕获异常：${throwable.javaClass.name}: ${throwable.message}",
                    throwable,
                )
            } catch (_: Throwable) {
                // 写日志失败也不能干扰系统默认崩溃流程
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 崩溃日志文件（外部私有目录优先，存储未挂载时退回内部存储） */
    fun crashFile(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, FILE_NAME)

    /** 读取上一次崩溃日志；无日志返回 null */
    fun readCrash(context: Context): String? {
        val f = crashFile(context)
        return if (f.exists() && f.length() > 0) {
            try {
                f.readText()
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
    }

    /** 清除崩溃日志（用户看完点"关闭并继续"后调用） */
    fun clear(context: Context) {
        try {
            crashFile(context).delete()
        } catch (_: Exception) {
        }
    }
}
