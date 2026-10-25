package com.mcstation.download.data.download

import android.content.Context
import com.mcstation.download.data.log.AppLog
import com.mcstation.download.data.model.McVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLongArray
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/**
 * 8 线程分段下载器（HTTP Range）+ 断点续传。
 *
 * 工作方式：
 *  1. 开始时先探测文件总大小（Range: bytes=0-0 读 Content-Range），
 *     把文件平均切成 8 个分段（服务器不支持 Range 或大小未知时退化为单线程）。
 *  2. 每个分段一个独立协程（IO 线程），带 Range 头请求自己的区间，
 *     追加写入各自的分片临时文件 .p0 ~ .p7。
 *  3. 各分段进度定期保存到 {fileName}.task.json（约 0.8s 节流，原子写：tmp -> rename）。
 *  4. 中断（崩溃/进程被杀/下载失败）后再次下载：读取 task.json，
 *     以分片文件实际长度为准，从断点继续（手动"取消下载"会清除断点，需重新下载）。
 *  5. 全部分段完成后按序合并成完整 APK，并清理临时文件。
 */
class SegmentedApkDownloader(
    private val context: Context,
    private val url: String,
    private val fileName: String,
    private val version: McVersion,
    private val threadCount: Int = DEFAULT_PARTS,
    /** 存储子目录（App 私有外部存储下），版本包用 download/，更新包用 update/ */
    private val subDir: String = "download",
    /**
     * 期望的 SHA-256（小写十六进制）。非空时在合并完成后校验文件摘要，
     * 不匹配视为下载失败（抛 IOException，由调用方换源重试），用于防止
     * 加速节点返回旧版本/被篡改的安装包。空串表示不做摘要校验。
     */
    private val expectedSha256: String = "",
    /**
     * 额外的 Referer 头。小布（mcdl.xbu.hk）的 123 云盘 CDN 有防盗链，
     * 不带 Referer 会返回 403，必须逐请求带上。空串表示不加。
     */
    private val referer: String = "",
    /** 检测到断点并成功恢复时回调（参数为已下载字节数） */
    private val onResumed: (Long) -> Unit = {},
) {
    companion object {
        const val DEFAULT_PARTS = 8
        /** 摘要不匹配时抛出的异常消息前缀，调用方据此把"校验失败"与其他下载失败区分开 */
        const val CHECKSUM_ERROR_PREFIX = "SHA-256 校验失败"
        private const val TAG = "Downloader"
        private const val CONNECT_TIMEOUT = 10_000
        private const val READ_TIMEOUT = 30_000
        private const val MAX_RETRIES = 5
        private const val RETRY_DELAY_MS = 1_200L
        private const val SAVE_INTERVAL_MS = 800L
        private const val BUF_SIZE = 64 * 1024
    }

    private val dir: File = context.getExternalFilesDir(subDir)
        ?: throw IOException("外部存储不可用")
    private val stateFile get() = File(dir, "$fileName.task.json")
    private val destFile get() = File(dir, fileName)

    private var total = -1L
    private var rangeSupported = true
    private var threadCountActual = threadCount
    private lateinit var segStart: LongArray
    private lateinit var segEnd: LongArray
    private lateinit var segDone: AtomicLongArray

    private val saveLock = Any()
    private var lastSaveTime = 0L

    private fun partFile(i: Int) = File(dir, "$fileName.p$i")

    /**
     * 执行下载（支持协程取消，取消时分片文件保留以便续传；是否删除由调用方决定）。
     * @return 下载完成的 APK 文件
     */
    suspend fun download(onProgress: (received: Long, total: Long) -> Unit): File = coroutineScope {
        // 之前已合并完成且无断点状态：直接返回
        if (destFile.exists() && !stateFile.exists()) return@coroutineScope destFile

        if (!restoreState()) probeAndInit()
        val n = threadCountActual

        // 进度采样协程：每 200ms 汇总一次各分段进度
        val sampler = launch(Dispatchers.IO) {
            while (isActive) {
                delay(200)
                var sum = 0L
                for (i in 0 until n) sum += segDone.get(i)
                onProgress(sum, total)
            }
        }

        try {
            // 每个分段一个独立线程（IO 协程），带 Range 头下载各自区间
            (0 until n).map { i ->
                async(Dispatchers.IO) { downloadSegment(i) }
            }.awaitAll()
            var sum = 0L
            for (i in 0 until n) sum += segDone.get(i)
            onProgress(sum, if (total > 0) total else sum)
            merge()
        } finally {
            sampler.cancel()
        }
        destFile
    }

    // ================= 分段下载 =================

    private suspend fun downloadSegment(i: Int) {
        val part = partFile(i)
        val segSize = segEnd[i] - segStart[i] + 1
        var retries = 0
        while (true) {
            // 以分片文件实际长度为断点（比内存计数更可靠，可覆盖"写盘后未来得及存进度"的情况）
            val done = (if (part.exists()) part.length() else 0L).coerceIn(0L, segSize)
            segDone.set(i, done)
            if (done >= segSize) return

            val conn = (URL(url).openConnection() as HttpURLConnection)
            try {
                conn.connectTimeout = CONNECT_TIMEOUT
                conn.readTimeout = READ_TIMEOUT
                conn.setRequestProperty("Accept", "application/vnd.android.package-archive,*/*")
                if (referer.isNotEmpty()) conn.setRequestProperty("Referer", referer)
                if (rangeSupported) {
                    conn.setRequestProperty("Range", "bytes=${segStart[i] + done}-${segEnd[i]}")
                }
                val code = conn.responseCode
                if (i == 0 && retries == 0) {
                    // 首次请求记下响应码与区间响应头，便于排查"下到了错误内容"类问题
                    AppLog.i(TAG, "seg0 响应 code=$code contentRange=${conn.getHeaderField("Content-Range")}")
                }
                if (code == 416) return // 区间已完成
                if (code !in 200..299) {
                    AppLog.e(TAG, "seg$i HTTP $code（重试 $retries/$MAX_RETRIES）url=$url")
                    throw IOException("HTTP $code")
                }

                var skip = 0L
                if (code == 200) {
                    // 服务器忽略了 Range 头：多线程时会重复下载整份文件，直接判失败；
                    // 单线程则跳过前面已下载的字节继续追加
                    if (threadCountActual > 1) throw IOException("服务器不支持分段下载")
                    skip = done
                }

                conn.inputStream.use { input ->
                    FileOutputStream(part, true).use { out ->
                        if (skip > 0) skipFully(input, skip)
                        val buf = ByteArray(BUF_SIZE)
                        var written = done
                        while (written < segSize) {
                            coroutineContext.ensureActive() // 响应协程取消
                            val toRead = min(BUF_SIZE.toLong(), segSize - written).toInt()
                            val read = input.read(buf, 0, toRead)
                            if (read == -1) break
                            out.write(buf, 0, read)
                            written += read
                            segDone.set(i, written)
                            maybeSaveState() // 定期保存断点进度
                        }
                        out.flush()
                    }
                }
                if (segDone.get(i) >= segSize || total <= 0) return
                throw IOException("连接提前结束")
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                retries++
                if (retries > MAX_RETRIES) {
                    AppLog.e(TAG, "seg$i 重试 $MAX_RETRIES 次后放弃：${e.message}", e)
                    throw e
                }
                AppLog.e(TAG, "seg$i 第 $retries 次重试（${e.message}），分片已有 $done 字节")
                delay(RETRY_DELAY_MS * retries) // 指数退避重试
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(BUF_SIZE)
        while (remaining > 0) {
            val n = input.read(buf, 0, min(BUF_SIZE.toLong(), remaining).toInt())
            if (n == -1) throw IOException("连接提前结束")
            remaining -= n
        }
    }

    // ================= 合并 =================

    private fun merge() {
        if (destFile.exists()) destFile.delete()
        FileOutputStream(destFile).use { out ->
            for (i in 0 until threadCountActual) {
                val part = partFile(i)
                if (part.exists()) part.inputStream().use { it.copyTo(out) }
            }
            out.flush()
        }
        for (i in 0 until threadCountActual) partFile(i).delete()
        stateFile.delete()
        File(dir, "$fileName.task.json.tmp").delete()
        // 成品校验：大小与探测值一致 + 是合法的 ZIP/APK，防止把损坏文件当成功交给安装器
        validateApk(destFile)
    }

    /**
     * APK 完整性校验（合并完成后调用）：
     *  1. 文件大小 == 探测到的总大小（服务器给了 total 时）
     *  2. 文件头是 ZIP 魔数（PK），且 ZIP 中央目录能被完整解析（ZipFile 打开成功）
     *  3. 给了 [expectedSha256] 时，文件摘要必须与之完全一致
     * 任一不满足则删除成品并抛异常（任务标记失败），绝不把损坏/可疑文件交给安装器。
     */
    private fun validateApk(file: File) {
        val len = file.length()
        if (total > 0 && len != total) {
            val msg = "文件大小不符：实际 $len 字节 / 应为 $total 字节"
            AppLog.e(TAG, "APK 校验失败 $fileName：$msg")
            file.delete()
            throw IOException(msg)
        }
        try {
            java.util.zip.ZipFile(file).use { zip ->
                if (zip.size() < 1) throw IOException("ZIP 内没有任何条目")
                // 确认是 Android 安装包：包含 AndroidManifest.xml
                if (zip.getEntry("AndroidManifest.xml") == null) {
                    throw IOException("缺少 AndroidManifest.xml，不是有效的 APK")
                }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "APK 校验失败 $fileName（大小 $len）：${e.message}", e)
            file.delete()
            throw IOException("APK 完整性校验失败（下载内容损坏）", e)
        }
        if (expectedSha256.isNotEmpty()) {
            val actual = sha256(file)
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                // 常见成因：加速节点缓存了旧版本、或返回了被篡改的文件 —— 删掉并让调用方换源
                AppLog.e(
                    TAG,
                    "更新包摘要不符 $fileName：期望 $expectedSha256 实际 $actual（来源 $url）",
                )
                file.delete()
                throw IOException("$CHECKSUM_ERROR_PREFIX：期望 $expectedSha256 实际 $actual")
            }
            AppLog.i(TAG, "SHA-256 校验通过 $fileName（$actual）")
        }
        AppLog.i(TAG, "APK 校验通过 $fileName（$len 字节）")
    }

    /** 计算文件 SHA-256，返回小写十六进制；流式读，避免把整个 APK 读进内存 */
    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(BUF_SIZE)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ================= 断点状态持久化 =================

    /** 读取本地保存的断点进度；成功返回 true（segDone 已恢复，并回调 onResumed） */
    private fun restoreState(): Boolean {
        val state = stateFile
        if (!state.exists()) return false
        return try {
            val json = JSONObject(state.readText())
            if (json.optString("url") != url) return false
            val starts = json.optJSONArray("segStart") ?: return false
            val ends = json.optJSONArray("segEnd") ?: return false
            val dones = json.optJSONArray("segDone") ?: return false
            val n = starts.length()
            if (n < 1 || n > threadCount) return false
            threadCountActual = n
            total = json.optLong("total", -1L)
            rangeSupported = json.optBoolean("range", true)
            segStart = LongArray(n) { starts.getLong(it) }
            segEnd = LongArray(n) { ends.getLong(it) }
            segDone = AtomicLongArray(LongArray(n) { i ->
                val segSize = segEnd[i] - segStart[i] + 1
                val part = partFile(i)
                val saved = dones.optLong(i, 0L)
                // 取 分片文件实际长度 与 已存进度 的较小值，夹在分段大小内
                min(saved.toLong(), if (part.exists()) part.length() else 0L).coerceIn(0L, segSize)
            })
            val resumed = (0 until n).sumOf { segDone.get(it) }
            if (resumed > 0) onResumed(resumed)
            saveState()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 探测文件总大小并初始化分段 */
    private fun probeAndInit() {
        var conn: HttpURLConnection? = null
        var supported = false
        var t = -1L
        var code = -1
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("Range", "bytes=0-0")
                setRequestProperty("Accept", "application/vnd.android.package-archive,*/*")
                if (referer.isNotEmpty()) setRequestProperty("Referer", referer)
            }
            code = conn.responseCode
            if (code == 206) {
                supported = true
                // Content-Range: bytes 0-0/75916912
                t = conn.getHeaderField("Content-Range")
                    ?.substringAfterLast('/')?.toLongOrNull() ?: -1L
            } else if (code in 200..299) {
                supported = false
                t = conn.contentLengthLong
            } else {
                AppLog.e(TAG, "探测失败 $fileName HTTP $code url=$url")
                throw IOException("HTTP $code")
            }
        } finally {
            conn?.disconnect()
        }
        AppLog.i(TAG, "探测 $fileName：code=$code total=$t range=$supported 线程=${if (supported && t > 0) threadCount else 1}")

        total = t
        rangeSupported = supported
        threadCountActual = if (supported && t > 0) threadCount else 1
        segStart = LongArray(threadCountActual)
        segEnd = LongArray(threadCountActual)
        if (t > 0) {
            // 平均切分，余数分给前面的分段
            val base = t / threadCountActual
            val remainder = t % threadCountActual
            var start = 0L
            for (i in 0 until threadCountActual) {
                val size = base + if (i < remainder) 1 else 0
                segStart[i] = start
                segEnd[i] = start + size - 1
                start += size
            }
        } else {
            // 大小未知：单线程下载到流结束
            segStart[0] = 0
            segEnd[0] = Long.MAX_VALUE / 2
        }
        segDone = AtomicLongArray(LongArray(threadCountActual))
        saveState()
    }

    private fun maybeSaveState() {
        val now = System.currentTimeMillis()
        if (now - lastSaveTime < SAVE_INTERVAL_MS) return
        synchronized(saveLock) {
            if (System.currentTimeMillis() - lastSaveTime < SAVE_INTERVAL_MS) return
            saveState()
        }
    }

    /** 原子写入断点状态：写 tmp 再 rename，避免写一半被杀导致状态损坏 */
    private fun saveState() {
        try {
            lastSaveTime = System.currentTimeMillis()
            val json = JSONObject()
                .put("fileName", fileName)
                .put("url", url)
                .put("version", version.version)
                .put("beta", version.beta)
                .put("date", version.date)
                .put("size", version.size)
                .put("total", total)
                .put("range", rangeSupported)
            val starts = JSONArray()
            val ends = JSONArray()
            val dones = JSONArray()
            for (i in 0 until threadCountActual) {
                starts.put(segStart[i])
                ends.put(segEnd[i])
                dones.put(segDone.get(i))
            }
            json.put("segStart", starts).put("segEnd", ends).put("segDone", dones)
            val tmp = File(dir, "$fileName.task.json.tmp")
            tmp.writeText(json.toString())
            if (stateFile.exists()) stateFile.delete()
            if (!tmp.renameTo(stateFile)) tmp.delete()
        } catch (e: Exception) {
            // 保存失败不影响下载，仅影响续传粒度
        }
    }
}
