package com.mcstation.download.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 独立「声明」页面：从设置页「声明」入口进入（返回键 / 左上角返回设置页）。
 *
 * ⚠️ 页面正文不写在这个文件里。
 *    正文放在 `app/src/main/assets/statement.txt`，改那个 txt 就能改整页内容。
 *    本文件只负责排版（小节标题 / 段落 / 提示 / 分隔线）。
 *
 * 排版结构：
 *   顶部栏（返回 + 标题「声明」+ 右上角刷新）
 *   一张卡片，内部按 statement.txt 的行顺序渲染：
 *     - 小节标题：主题色 + 半粗，上方留较大间距
 *     - 普通段落：正文色，行高 23sp
 *     - `> ` 提示：outline 灰 + 13sp
 *     - `---`：细分隔线
 *
 * 内容更新策略（AppViewModel 负责）：
 *   1. 每次打开应用，AppViewModel.init 里后台拉一次最新声明（无时间间隔限制），结果写进缓存
 *   2. 页面正文实时跟随 [AppViewModel.statementText]——启动拉取完成时页面若已打开会自动刷新
 *   3. 拉不到 → 静默，继续显示本地内容
 *   4. 右上角刷新按钮手动强制拉取
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatementScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
) {
    // 正文由 ViewModel 持有：启动时自动更新，这里只管显示
    val raw = viewModel.statementText
    val refreshing = viewModel.statementRefreshing
    val blocks = remember(raw) { StatementParser.parse(raw) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // ---------- 顶部栏 ----------
        TopAppBar(
            title = { Text("声明") },
            modifier = Modifier.padding(top = 14.dp),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            actions = {
                IconButton(
                    onClick = { viewModel.refreshStatement(manual = true) },
                    enabled = !refreshing,
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "刷新声明",
                        tint = if (refreshing) {
                            MaterialTheme.colorScheme.outlineVariant
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        // ---------- 正文卡片 ----------
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(Modifier.padding(18.dp)) {
                    blocks.forEach { block ->
                        when (block) {
                            is StatementBlock.Heading -> {
                                // 小节标题：上下留白由块本身的 padding 控制
                                if (!block.first) Spacer(Modifier.height(18.dp))
                                Text(
                                    block.text,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.height(8.dp))
                            }

                            is StatementBlock.Paragraph -> {
                                Text(
                                    text = block.annotated,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 21.sp,
                                )
                                Spacer(Modifier.height(12.dp))
                            }

                            is StatementBlock.Hint -> {
                                Text(
                                    text = block.annotated,
                                    fontSize = 13.sp,
                                    lineHeight = 20.sp,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                                Spacer(Modifier.height(10.dp))
                            }

                            StatementBlock.Divider -> {
                                Spacer(Modifier.height(6.dp))
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    thickness = 0.7.dp,
                                )
                                Spacer(Modifier.height(14.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 声明页的一个排版块 */
sealed interface StatementBlock {
    /** 带序号用于判断是否首块（首块不加上方间距） */
    data class Heading(val text: String, val first: Boolean) : StatementBlock

    /** 普通段落，内部用 annotated 支持 **加粗** */
    data class Paragraph(val annotated: androidx.compose.ui.text.AnnotatedString) : StatementBlock

    /** `> ` 开头的灰色提示 */
    data class Hint(val annotated: androidx.compose.ui.text.AnnotatedString) : StatementBlock

    data object Divider : StatementBlock
}

/**
 * 极简 Markdown 子集解析器。
 *
 * 只认这几种行首标记，其他一律当普通段落处理：
 *   `# `    → 忽略（页面标题固定为「声明」，避免正文标题与顶栏重复）
 *   `## `   → 小节标题
 *   `> `    → 灰色提示
 *   `---`   → 分隔线
 *   其他    → 普通段落（连续多行会合并成一段）
 *
 * 另外支持行内 `**加粗**`。
 * 设计原则：**遇到不认识的东西一律降级成纯文本，绝不抛异常**——
 * 这样你可以随便改 statement.txt，最坏情况只是显示得朴素一点。
 */
object StatementParser {

    fun parse(raw: String): List<StatementBlock> {
        val out = mutableListOf<StatementBlock>()
        val para = StringBuilder()
        var headingCount = 0
        // 是否正处于 <!-- ... --> 注释块内（注释只给编辑者看，不进界面）
        var inComment = false

        fun flushPara() {
            if (para.isEmpty()) return
            out += StatementBlock.Paragraph(inline(para.toString().trim()))
            para.clear()
        }

        for (line in raw.replace("\r\n", "\n").split('\n')) {
            val l = line.trimEnd()
            val t = l.trim()

            // ---------- 注释处理 ----------
            if (inComment) {
                if (t.contains("-->")) inComment = false
                continue
            }
            if (t.startsWith("<!--")) {
                // 单行注释 <!-- ... --> 直接跳过；没闭合就进入注释态
                if (!t.contains("-->")) inComment = true
                flushPara()
                continue
            }

            when {
                l.isBlank() -> flushPara()

                l.trim() == "---" -> {
                    flushPara()
                    out += StatementBlock.Divider
                }

                // 大标题不渲染（顶栏已经有「声明」了）
                l.startsWith("# ") -> flushPara()

                l.startsWith("## ") -> {
                    flushPara()
                    out += StatementBlock.Heading(l.removePrefix("## ").trim(), first = headingCount++ == 0)
                }

                l.startsWith("> ") -> {
                    flushPara()
                    out += StatementBlock.Hint(inline(l.removePrefix("> ").trim()))
                }

                // 单独的 ">"（块引用空行）直接丢掉
                l.trim() == ">" -> flushPara()

                // 连续列表项合并成一段，行前加圆点
                l.startsWith("- ") -> {
                    if (para.isNotEmpty() && !para.endsWith("\n")) para.append('\n')
                    para.append("· ").append(l.removePrefix("- ").trim())
                }

                // 反引号包裹的内容（例如 `## ` 这类格式说明）原样保留，不做行内处理
                else -> {
                    if (para.isNotEmpty() && !para.endsWith("\n")) para.append(' ')
                    para.append(l.trim())
                }
            }
        }
        flushPara()
        return out
    }

    /** 处理行内 `**加粗**`，返回带样式的 AnnotatedString */
    private fun inline(text: String): androidx.compose.ui.text.AnnotatedString =
        buildAnnotatedString {
            var i = 0
            while (i < text.length) {
                val start = text.indexOf("**", i)
                if (start < 0) {
                    append(text.substring(i))
                    break
                }
                val end = text.indexOf("**", start + 2)
                if (end < 0) {
                    // 没闭合，原样输出
                    append(text.substring(i))
                    break
                }
                append(text.substring(i, start))
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                    append(text.substring(start + 2, end))
                }
                i = end + 2
            }
        }
}
