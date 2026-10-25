package com.mcstation.download.data.mc

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Minecraft 游戏数据的备份与回填（只处理 Android/data 下的数据目录）。
 *
 * 为什么需要：不同渠道的包签名不同，装新渠道必须先卸载旧包，
 * 而 Android 11+ 上 Minecraft 的数据在
 * `/sdcard/Android/data/com.mojang.minecraftpe/files/games/com.mojang`，
 * 卸载会连目录一起删除 —— 所以**必须在卸载前把数据复制出来**，装好新包后再粘贴回去。
 *
 * 权限：Android 11+ 需要「所有文件访问」（MANAGE_EXTERNAL_STORAGE），
 * 否则读不到 Android/data 下其他应用的目录；Android 10 及以下用读写外部存储权限。
 */
object McDataBackup {

    /** 游戏数据目录（相对外部存储根） */
    private const val DATA_RELATIVE = "Android/data/${InstalledMc.PACKAGE}/files/games/com.mojang"

    /**
     * 备份范围：游戏数据目录下的**全部内容**（存档、资源包、行为包、皮肤包、自定义皮肤、
     * 世界模板、截图、设备标识文件等），只排除可重建的日志目录。
     * 用全量而不是白名单，避免以后 Minecraft 新增数据目录时漏备。
     */
    private val EXCLUDED = setOf("logs")

    private const val BACKUP_DIR = "mcbackup"

    data class BackupInfo(
        val time: Long,
        val files: Int,
        val bytes: Long,
    )

    data class Result(
        val ok: Boolean,
        val files: Int = 0,
        val bytes: Long = 0L,
        /** 失败原因（用户可见），成功时为 null */
        val error: String? = null,
    )

    // ================= 权限 =================

    /** 是否已获得读取其他应用目录的权限 */
    fun hasStorageAccess(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
        // minSdk 21：用 ContextCompat 兼容 Android 6 以下（无运行时权限）
        else -> androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** 跳转「所有文件访问」授权页（Android 11+）；低版本返回 false 由调用方走运行时权限 */
    fun openAllFilesAccessSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        return try {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (e: Exception) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    // ================= 目录定位 =================

    /** 游戏数据目录（默认位置）：/sdcard/Android/data/com.mojang.minecraftpe/files/games/com.mojang */
    fun dataDir(): File? {
        val base = Environment.getExternalStorageDirectory() ?: return null
        return File(base, DATA_RELATIVE)
    }

    /** 候选数据目录：内部存储的默认位置/旧位置，以及各外置 SD 卡的对应位置 */
    fun candidates(): List<File> {
        val out = LinkedHashSet<File>()
        Environment.getExternalStorageDirectory()?.let { base ->
            out += File(base, DATA_RELATIVE)
            out += File(base, "games/com.mojang")
            out += File(base, "Android/data/${InstalledMc.PACKAGE}/files/com.mojang")
        }
        // 外置存储卷（/storage/XXXX-XXXX）
        File("/storage").listFiles()?.forEach { vol ->
            if (vol.name != "emulated" && vol.name != "self") {
                out += File(vol, DATA_RELATIVE)
                out += File(vol, "games/com.mojang")
            }
        }
        return out.toList()
    }

    /** 实际存在、可备份的目录（按候选顺序）；都不存在时返回空列表 */
    fun availableSources(): List<File> = candidates().filter { it.isDirectory }

    /** 存档条目：目录名、世界名、图标文件、最后修改时间 */
    data class WorldEntry(
        val folder: String,
        val name: String,
        val icon: File?,
        val modified: Long,
    )

    /** 备份前预览：源目录、是否可读、存档列表、其他内容（名称 → 子项数） */
    data class Preview(
        val source: File,
        val readable: Boolean,
        val worlds: List<WorldEntry>,
        val others: List<Pair<String, Int>>,
    )

    /** 读取源目录的存档与世界图标，供备份确认弹窗展示（IO 线程调用） */
    fun loadPreview(source: File): Preview {
        // listFiles 返回 null 表示目录不存在或没有读取权限（分区存储未授权时就是这种表现）
        val top = source.listFiles()
        val readable = top != null
        val worldsDir = top?.firstOrNull { it.isDirectory && it.name == "minecraftWorlds" }
        val worlds = worldsDir?.listFiles()
            ?.filter { it.isDirectory }
            ?.map { w ->
                val name = runCatching {
                    File(w, "levelname.txt").readText(Charsets.UTF_8).trim()
                }.getOrNull()?.takeIf { it.isNotEmpty() } ?: w.name
                val icon = listOf("world_icon.jpeg", "world_icon.jpg", "world_icon.png")
                    .map { File(w, it) }
                    .firstOrNull { it.isFile }
                // level.dat 的修改时间更贴近“世界最后一次保存”
                val modified = File(w, "level.dat").takeIf { it.isFile }?.lastModified()
                    ?: w.lastModified()
                WorldEntry(folder = w.name, name = name, icon = icon, modified = modified)
            }
            ?.sortedByDescending { it.modified }
            ?: emptyList()

        // 其他内容：只数直接子项（目录=包数量），不递归，避免大目录卡顿
        val others = top
            ?.filter { it.name !in EXCLUDED && it.name != "minecraftWorlds" }
            ?.map { f -> f.name to (f.listFiles()?.size ?: 1) }
            ?.sortedBy { it.first.lowercase() }
            ?: emptyList()

        return Preview(source, readable, worlds, others)
    }

    private fun backupRoot(context: Context) = File(context.filesDir, BACKUP_DIR)

    /** 备份/回填的关键节点写运行日志，便于用户自查（日志在 files/logs 下） */
    private fun log(msg: String) =
        com.mcstation.download.data.log.AppLog.i("Backup", msg)

    /** 已有备份则返回信息（App 私有目录，卸载本 App 会一起丢失） */
    fun backupInfo(context: Context): BackupInfo? {
        val root = backupRoot(context)
        val meta = File(root, "meta.txt")
        val data = File(root, "data")
        if (!meta.exists() || !data.isDirectory) return null
        return try {
            val time = meta.readLines().firstOrNull()?.toLongOrNull() ?: meta.lastModified()
            var files = 0
            var bytes = 0L
            data.walkTopDown().forEach { f ->
                if (f.isFile) {
                    files++
                    bytes += f.length()
                }
            }
            BackupInfo(time, files, bytes)
        } catch (e: Exception) {
            null
        }
    }

    // ================= 备份 =================

    /**
     * 把指定数据目录（默认 Android/data 下的 com.mojang）复制到 App 私有目录。
     * 耗时操作，必须在 IO 线程调用。
     * @param source 要备份的源目录，null 表示用默认位置
     * @param onProgress 回调（已复制文件数 / 总文件数）
     */
    fun backup(
        context: Context,
        source: File? = null,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result {
        if (!hasStorageAccess(context)) {
            return Result(false, error = "缺少存储访问权限")
        }
        val dir = source ?: dataDir()
        if (dir == null || !dir.isDirectory) {
            return Result(
                false,
                error = "没有找到 Minecraft 数据目录。请确认游戏里「存储位置」选的是「外部存储」（选「应用存储」的数据在系统私有目录，任何 App 都读不到）",
            )
        }
        val root = backupRoot(context)
        val target = File(root, "data")

        return try {
            // 重备时先清空旧备份，避免旧存档残留
            if (root.exists()) root.deleteRecursively()
            target.mkdirs()

            val entries = dir.listFiles()
                ?.filter { it.name !in EXCLUDED }
                ?.sortedBy { it.name.lowercase() }
                ?: emptyList()
            if (entries.isEmpty()) return Result(false, error = "数据目录里没有可备份的内容")

            val total = entries.sumOf { countFiles(it) }
            var done = 0
            var bytes = 0L
            for (entry in entries) {
                bytes += copyRecursive(entry, File(target, entry.name), onProgressTick = { n ->
                    done += n
                    onProgress(done, total)
                })
            }

            // 第一行时间戳，第二行备份源路径（供恢复时参考）
            File(root, "meta.txt").writeText(
                System.currentTimeMillis().toString() + "\n" + dir.absolutePath,
            )
            log("备份完成：$total 个文件 / $bytes 字节，源=${dir.absolutePath}，目标=${root.absolutePath}")
            Result(true, files = total, bytes = bytes)
        } catch (e: Exception) {
            log("备份失败：${e.message}，源=${dir.absolutePath}")
            Result(false, error = e.message ?: "备份失败")
        }
    }

    // ================= 回填 =================

    /**
     * 把备份粘贴回游戏数据目录（装好新包后调用）。
     *
     * 目标目录优先用备份时记录在 meta.txt 第二行的**源路径**：备份源可能是
     * `files/games/com.mojang`、`games/com.mojang`、`files/com.mojang` 等不同层级，
     * 硬编码标准路径会把数据写到 Minecraft 根本不读的地方（表现为“复制成功但游戏里没有世界”）。
     * 源路径不可写时才退回默认位置。
     */
    fun restore(context: Context, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Result {
        if (!hasStorageAccess(context)) {
            return Result(false, error = "缺少存储访问权限")
        }
        val root = backupRoot(context)
        val backupData = File(root, "data")
        if (!backupData.isDirectory) return Result(false, error = "没有找到备份数据")

        val recordedPath = File(root, "meta.txt")
            .takeIf { it.isFile }
            ?.let { runCatching { it.readLines().getOrNull(1) }.getOrNull() }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val recordedDir = recordedPath?.let { File(it) }?.takeIf { it.isDirectory }

        val targetDir = listOfNotNull(recordedDir, dataDir()).firstOrNull { ensureWritable(it) }
            ?: return Result(
                false,
                error = "无法写入 Minecraft 数据目录，请确认游戏「设置 → 存储 → 文件存储位置」选的是「外部」",
            )
        log("回填开始：目标=${targetDir.absolutePath}，备份源=${recordedPath ?: "未记录"}")

        return try {
            val entries = backupData.listFiles() ?: return Result(false, error = "备份数据为空")
            val total = entries.sumOf { countFiles(it) }
            var done = 0
            var bytes = 0L
            for (entry in entries) {
                val dst = File(targetDir, entry.name)
                if (dst.exists()) dst.deleteRecursively()
                bytes += copyRecursive(entry, dst, onProgressTick = { n ->
                    done += n
                    onProgress(done, total)
                })
            }
            // 校验：备份里有几个世界，目标目录里就应该有几个
            val backupWorlds = File(backupData, "minecraftWorlds").listFiles()?.count { it.isDirectory } ?: 0
            val targetWorlds = File(targetDir, "minecraftWorlds").listFiles()?.count { it.isDirectory } ?: 0
            log("回填完成：$total 个文件 / $bytes 字节 → ${targetDir.absolutePath}；世界 $backupWorlds → $targetWorlds")
            if (backupWorlds > 0 && targetWorlds < backupWorlds) {
                return Result(
                    ok = false,
                    files = total,
                    bytes = bytes,
                    error = "文件已复制到 ${targetDir.absolutePath}，但里面只有 $targetWorlds/$backupWorlds 个世界。" +
                        "请确认游戏「文件存储位置」是「外部」，并先完全退出游戏再粘贴",
                )
            }
            Result(true, files = total, bytes = bytes)
        } catch (e: Exception) {
            log("回填失败：${e.message}")
            Result(false, error = e.message ?: "回填失败")
        }
    }

    /** 目录存在且能真正写文件（分区存储未授权时 mkdirs 会静默失败） */
    private fun ensureWritable(dir: File?): Boolean {
        if (dir == null) return false
        if (!dir.isDirectory && !dir.mkdirs()) return false
        return try {
            val probe = File(dir, ".mcdl_write_test")
            probe.writeText("1")
            probe.delete()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 删除备份（用户取消保管时用） */
    fun clear(context: Context) {
        val root = backupRoot(context)
        root.deleteRecursively()
        log("备份已删除：${root.absolutePath}")
    }

    // ================= 文件工具 =================

    private fun countFiles(f: File): Int =
        if (f.isDirectory) f.walkTopDown().count { it.isFile } else 1

    /** 递归复制，返回复制的字节数；每复制完一个文件回调一次（参数为文件数 1） */
    private fun copyRecursive(src: File, dst: File, onProgressTick: (Int) -> Unit): Long {
        if (src.isFile) {
            dst.parentFile?.mkdirs()
            var copied = 0L
            FileInputStream(src).use { input ->
                FileOutputStream(dst).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        copied += n
                    }
                }
            }
            dst.setLastModified(src.lastModified())
            onProgressTick(1)
            return copied
        }
        dst.mkdirs()
        var sum = 0L
        src.listFiles()?.forEach { child ->
            sum += copyRecursive(child, File(dst, child.name), onProgressTick)
        }
        return sum
    }
}
