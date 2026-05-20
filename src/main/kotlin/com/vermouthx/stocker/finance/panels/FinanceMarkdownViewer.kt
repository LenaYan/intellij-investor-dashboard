package com.vermouthx.stocker.finance.panels

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument

/**
 * Lightweight markdown viewer used by the finance/ panels.
 *
 * Strategy: monospace-rendered raw markdown with per-line attribute styling.
 * No HTML transform, no external dependency. Sufficient for the report content
 * we consume (well-formed markdown with tables, headings, lists).
 *
 * Updating is **idempotent**: calling [setMarkdown] with the same text is a no-op,
 * so we can freely poll the file on disk.
 */
internal class FinanceMarkdownViewer : JPanel(BorderLayout()) {

    private val textPane: JTextPane = JTextPane().apply {
        isEditable = false
        // Use the IDE editor font so the table-style monospace lines up.
        val schemeFont = EditorColorsManager.getInstance().globalScheme.editorFontName
        font = Font(schemeFont, Font.PLAIN, 12)
        margin = java.awt.Insets(8, 12, 8, 12)
        background = JBColor.background()
    }

    private var lastText: String? = null

    init {
        add(JBScrollPane(textPane), BorderLayout.CENTER)
        border = BorderFactory.createEmptyBorder()
    }

    fun setMarkdown(md: String?) {
        val text = md ?: ""
        if (text == lastText) return
        lastText = text
        val doc = textPane.styledDocument
        doc.remove(0, doc.length)
        if (text.isEmpty()) {
            appendStyled(doc, "（暂无数据）\n", colorMuted, italic = true)
            return
        }
        text.lines().forEach { renderLine(doc, it) }
        textPane.caretPosition = 0
    }

    fun setEmptyMessage(msg: String) {
        lastText = null
        val doc = textPane.styledDocument
        doc.remove(0, doc.length)
        appendStyled(doc, msg, colorMuted, italic = true)
    }

    private fun renderLine(doc: StyledDocument, line: String) {
        when {
            line.startsWith("# ")    -> appendStyled(doc, line + "\n", colorH1, bold = true)
            line.startsWith("## ")   -> appendStyled(doc, line + "\n", colorH2, bold = true)
            line.startsWith("### ")  -> appendStyled(doc, line + "\n", colorH3, bold = true)
            line.startsWith("#### ") -> appendStyled(doc, line + "\n", colorH3, bold = true)
            line.startsWith("---")   -> appendStyled(doc, line + "\n", colorMuted)
            line.startsWith("|")     -> appendStyled(doc, line + "\n", colorTable)
            line.startsWith("- ") || line.startsWith("* ") -> appendStyled(doc, line + "\n", colorList)
            line.startsWith("```")   -> appendStyled(doc, line + "\n", colorCode)
            line.startsWith(">")     -> appendStyled(doc, line + "\n", colorQuote, italic = true)
            else                     -> appendStyled(doc, line + "\n", colorBody)
        }
    }

    private fun appendStyled(
        doc: StyledDocument,
        s: String,
        color: Color,
        bold: Boolean = false,
        italic: Boolean = false,
    ) {
        val attrs = SimpleAttributeSet()
        StyleConstants.setForeground(attrs, color)
        if (bold) StyleConstants.setBold(attrs, true)
        if (italic) StyleConstants.setItalic(attrs, true)
        try {
            doc.insertString(doc.length, s, attrs)
        } catch (_: Exception) {
            // ignore: best-effort rendering
        }
    }

    companion object {
        private val colorBody  = JBColor(Color(0x1F1F1F), Color(0xC8C8C8))
        private val colorH1    = JBColor(Color(0x004C7F), Color(0x6CB8FF))
        private val colorH2    = JBColor(Color(0x005C9C), Color(0x84C5FF))
        private val colorH3    = JBColor(Color(0x006FBA), Color(0xA0D2FF))
        private val colorMuted = JBColor(Color(0x666666), Color(0x9E9E9E))
        private val colorTable = JBColor(Color(0x2E4A6B), Color(0xB6CFEA))
        private val colorList  = JBColor(Color(0x444444), Color(0xBFBFBF))
        private val colorCode  = JBColor(Color(0x800080), Color(0xCE93D8))
        private val colorQuote = JBColor(Color(0x37474F), Color(0x90A4AE))
    }
}
