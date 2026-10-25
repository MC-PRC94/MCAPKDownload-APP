package com.mcstation.download.data

import android.content.Context
import com.mcstation.download.data.api.StatementApi
import com.mcstation.download.data.log.AppLog
import java.io.File

/**
 * 「设置 → 声明」页面的正文来源（三层，按优先级从高到低）：
 *
 *   1. **联网缓存** —— 从 GitHub 拉取过的最新版本，存在 filesDir/statement_cache.txt
 *   2. **内置资源** —— app/src/main/assets/statement.txt，随安装包发布，永不缺失
 *   3. **兜底文案** —— 前两者都读不到时的一句话
 *
 * 为什么不只读本地、或只读网络：
 *   - 只读本地 → 改了 GitHub 上的声明，老用户永远看不到（除非发新版）
 *   - 只读网络 → 首次安装没网、镜像全挂、被限流时页面就空白了
 *   所以「本地永远有得显示，网络只负责更新到最新」。
 *
 * ⚠️ 正文不写在任何 .kt 里。
 *   线上版：仓库 `legal/statement.txt`（MC-PRC94/MCAPKDownload-APP，main 分支）
 *   内置版：`app/src/main/assets/statement.txt`
 *   两份内容一致，首行都必须是 [StatementApi.MAGIC]（改内容时那行原样保留）。
 *
 * 支持的轻量标记（解析在 StatementScreen.StatementParser）：
 *   `# 标题`    页面大标题（不渲染，顶栏已固定「声明」）
 *   `## 标题`   小节标题
 *   `> 文字`    灰色提示
 *   `- 文字`    列表项
 *   `---`       分隔线
 *   其他非空行  普通段落（连续行合并）
 *   空行        段落间距
 *   `**文字**`  行内加粗
 */
object StatementDoc {

    private const val TAG = "Statement"

    /** 内置资源文件名 */
    private const val ASSET_NAME = "statement.txt"

    /** 联网缓存文件名（存在 filesDir 下，卸载即清除） */
    private const val CACHE_NAME = "statement_cache.txt"

    private const val PREF_FILE = "statement_prefs"
    private const val KEY_FETCHED_AT = "fetched_at"

    /** 前两者都读不到时的兜底文案（正常永远不会出现） */
    private const val FALLBACK = "声明内容读取失败，请重新安装应用后再试。"

    private fun cacheFile(context: Context) = File(context.filesDir, CACHE_NAME)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    /**
     * 当前应显示的声明正文（已剥离首行 MAGIC 标记）。
     * 纯本地读取，不发网络请求，可以在 UI 线程直接调用。
     */
    fun readAll(context: Context): String {
        // 1. 联网缓存
        val cached = try {
            cacheFile(context).takeIf { it.exists() }?.readText(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
        if (!cached.isNullOrBlank()) return stripMagic(cached)

        // 2. 内置资源
        val builtin = try {
            context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            null
        }
        if (!builtin.isNullOrBlank()) return stripMagic(builtin)

        // 3. 兜底
        return FALLBACK
    }

    /**
     * 保存联网拉到的正文。写入失败静默（缓存不能影响主流程）。
     *
     * 日志里同时记录正文 sha256 前 12 位：声明来自第三方镜像节点，
     * 万一哪天内容被人换成诱导性文字，可以拿这个哈希和仓库里的原文比对追溯。
     *
     * @param text 已由 [StatementApi] 校验并剥离 MAGIC 的正文
     */
    fun saveCache(context: Context, text: String) {
        try {
            cacheFile(context).writeText(text, Charsets.UTF_8)
            prefs(context).edit()
                .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
                .apply()
            AppLog.i(TAG, "声明缓存已更新（${text.length} 字，sha256=${shortHash(text)}）")
        } catch (e: Exception) {
            AppLog.e(TAG, "声明缓存写入失败：${e.message}")
        }
    }

    /** 正文 sha256 前 12 位，仅用于日志比对 */
    private fun shortHash(text: String): String = try {
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .take(6)
            .joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        "-"
    }

    /** 上次成功拉取的时间戳（毫秒），从未拉取过返回 0 */
    fun fetchedAt(context: Context): Long = prefs(context).getLong(KEY_FETCHED_AT, 0L)

    /** 清除联网缓存，回退到内置版本（排查问题时可用） */
    fun clearCache(context: Context) {
        try {
            cacheFile(context).delete()
            prefs(context).edit().remove(KEY_FETCHED_AT).apply()
        } catch (_: Exception) {
        }
    }

    /** 剥离首行 MAGIC 标记；没有标记时原样返回（内置资源可能被人手动删了那行，不该因此报错） */
    private fun stripMagic(raw: String): String {
        val t = raw.replace("\r\n", "\n").trim()
        return if (t.startsWith(StatementApi.MAGIC)) {
            t.removePrefix(StatementApi.MAGIC).trimStart('\n').trimEnd()
        } else {
            t
        }
    }
}
