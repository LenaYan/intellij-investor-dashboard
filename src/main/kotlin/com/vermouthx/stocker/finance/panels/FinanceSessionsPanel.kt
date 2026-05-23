package com.vermouthx.stocker.finance.panels

import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.vermouthx.stocker.finance.FinanceBridgeService
import java.awt.BorderLayout
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * 📚 深度研究 Tab — browser for ~/Claude/finance/sessions/{NN-分类}/YYYY-MM-DD-{slug}.md
 *
 * Layout: left list grouped by category, right markdown view of the selected session.
 * Top of list shows category prefixes (01-market-review, 03-stock, 04-sector, ...)
 * so user can mentally scan by topic.
 */
internal class FinanceSessionsPanel : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<String>()
    private val list = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = SessionCellRenderer()
        emptyText.text = "暂无 sessions/ 文件"
    }
    private val viewer = FinanceMarkdownViewer()
    private val pathByEntry = HashMap<String, Path>()
    private val refreshHook: () -> Unit = { reloadList() }

    init {
        val splitter = JBSplitter(false, 0.36f).apply {
            firstComponent = JBScrollPane(list)
            secondComponent = viewer
        }
        add(splitter, BorderLayout.CENTER)
        list.addListSelectionListener {
            if (it.valueIsAdjusting) return@addListSelectionListener
            val entry = list.selectedValue ?: return@addListSelectionListener
            renderSelected(entry)
        }
        FinanceBridgeService.instance.addRefreshListener(refreshHook)
        reloadList()
    }

    fun dispose() {
        FinanceBridgeService.instance.removeRefreshListener(refreshHook)
    }

    private fun reloadList() {
        val dir = FinanceBridgeService.instance.financeDir().resolve("sessions")
        listModel.clear()
        pathByEntry.clear()
        if (!Files.isDirectory(dir)) {
            viewer.setEmptyMessage(
                "找不到 sessions/ 目录。\n\n" +
                    "深度研究产出由 stock-deep-dive / industry-mapping 等 agent 在用户确认后落盘。\n" +
                    "位置: ~/Claude/finance/sessions/{NN-分类}/YYYY-MM-DD-{slug}.md"
            )
            return
        }
        try {
            Files.list(dir).use { catStream ->
                val categories = catStream.filter { Files.isDirectory(it) }
                    .sorted()
                    .toList()
                categories.forEach { catDir ->
                    val catName = catDir.fileName.toString()
                    // Header row (synthetic — not selectable)
                    listModel.addElement("__CAT__$catName")
                    pathByEntry["__CAT__$catName"] = catDir   // not used for rendering
                    val files = try {
                        Files.list(catDir).use { it.filter { p -> p.fileName.toString().endsWith(".md") }
                            .sorted(Comparator.reverseOrder()).toList() }
                    } catch (_: Exception) { emptyList() }
                    files.forEach { p ->
                        val basename = p.fileName.toString().removeSuffix(".md")
                        val entry = "  $basename"
                        listModel.addElement(entry)
                        pathByEntry[entry] = p
                    }
                }
            }
        } catch (_: Exception) {
            // ignore
        }
        if (listModel.size > 0) {
            // Auto-select first real (non-category) entry
            for (i in 0 until listModel.size) {
                if (!listModel.getElementAt(i).startsWith("__CAT__")) {
                    list.selectedIndex = i
                    renderSelected(listModel.getElementAt(i))
                    return
                }
            }
        }
        viewer.setEmptyMessage("sessions/ 目录下尚无 .md 文件")
    }

    private fun renderSelected(entry: String) {
        if (entry.startsWith("__CAT__")) return
        val path = pathByEntry[entry] ?: return
        val md = try { Files.readString(path) } catch (_: Exception) { null }
        if (md == null) {
            viewer.setEmptyMessage("无法读取 $entry")
        } else {
            viewer.setMarkdown(md)
        }
    }

    private class SessionCellRenderer : javax.swing.DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: javax.swing.JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): java.awt.Component {
            val s = value?.toString() ?: ""
            val isCategory = s.startsWith("__CAT__")
            val displayText = if (isCategory) "📂 ${s.removePrefix("__CAT__")}" else s
            val c = super.getListCellRendererComponent(list, displayText, index, isSelected, cellHasFocus) as JComponent
            border = javax.swing.BorderFactory.createEmptyBorder(
                if (isCategory) 8 else 3,
                if (isCategory) 8 else 16,
                if (isCategory) 4 else 3,
                12
            )
            if (isCategory) {
                font = font.deriveFont(java.awt.Font.BOLD)
            }
            return c
        }
    }
}
