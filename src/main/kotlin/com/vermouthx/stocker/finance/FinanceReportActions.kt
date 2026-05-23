package com.vermouthx.stocker.finance

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.vermouthx.stocker.finance.panels.FinanceMarkdownViewer
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.nio.file.Files
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Right-click "View bull-bear debate" / "View style jury" — pops up the report
 * for the clicked symbol when present.
 *
 * Looks up reports/<today>/{prefix}-{symbol}.md with 5-day fallback. If the
 * report file doesn't exist, shows an informational dialog.
 *
 * Prefixes:
 *   - bull-bear  (重仓建仓前压力测试)
 *   - style-jury (5 风格独立票)
 */
object FinanceReportActions {

    @JvmStatic
    fun showBullBear(parent: Component, symbol: String, displayName: String?) {
        showReport(parent, "bull-bear", symbol, displayName, "多空辩论 (bull-bear)")
    }

    @JvmStatic
    fun showStyleJury(parent: Component, symbol: String, displayName: String?) {
        showReport(parent, "style-jury", symbol, displayName, "风格投票 (style-jury)")
    }

    private fun showReport(
        parent: Component,
        prefix: String,
        symbol: String,
        displayName: String?,
        kindLabel: String,
    ) {
        val (path, date) = findReportPath(prefix, symbol) ?: run {
            Messages.showInfoMessage(
                parent,
                "${displayName ?: symbol} (${symbol}) 在过去 7 天没有 ${prefix}-* 报告。\n\n" +
                    "在 Claude 终端运行对应 agent 后会生成：\n" +
                    "  ~/Claude/finance/reports/<date>/${prefix}-${symbol}.md",
                kindLabel
            )
            return
        }
        val md = try { Files.readString(path) } catch (_: Exception) { null }
        if (md == null) {
            Messages.showErrorDialog(parent, "无法读取 $path", kindLabel)
            return
        }
        FinanceReportDialog(kindLabel, displayName ?: symbol, symbol, date.toString(), md).show()
    }

    private fun findReportPath(prefix: String, symbol: String): Pair<java.nio.file.Path, java.time.LocalDate>? {
        val dir = FinanceBridgeService.instance.financeDir()
        val today = FinanceReportLocator.today()
        val normSym = symbol.substringBefore('.').trim()
        for (b in 0..7) {
            val d = today.minusDays(b.toLong())
            val dayDir = dir.resolve("reports").resolve(d.toString())
            if (!Files.isDirectory(dayDir)) continue
            // Try exact match {prefix}-{symbol}.md first
            val direct = dayDir.resolve("$prefix-$normSym.md")
            if (Files.isRegularFile(direct)) return direct to d
            // Try any file matching prefix-* and containing the symbol substring
            try {
                Files.list(dayDir).use { stream ->
                    val hit = stream
                        .filter { it.fileName.toString().endsWith(".md") }
                        .filter { it.fileName.toString().startsWith("$prefix-") }
                        .filter { it.fileName.toString().contains(normSym) }
                        .findFirst()
                        .orElse(null)
                    if (hit != null) return hit to d
                }
            } catch (_: Exception) { /* fall through */ }
        }
        return null
    }
}

private class FinanceReportDialog(
    private val kindLabel: String,
    private val displayName: String,
    private val symbol: String,
    private val reportDate: String,
    private val markdown: String,
) : DialogWrapper(null, true) {

    init {
        title = "$kindLabel · $displayName ($symbol) · $reportDate"
        setOKButtonText("关闭")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(720, 640)
        panel.border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
        val viewer = FinanceMarkdownViewer()
        viewer.setMarkdown(markdown)
        panel.add(viewer, BorderLayout.CENTER)
        return panel
    }

    override fun createActions() = arrayOf(okAction)
}
