package com.vermouthx.stocker.components

import com.intellij.ui.JBColor
import com.vermouthx.stocker.entities.StockerIntradayData
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.TableCellRenderer

/**
 * Renders a mini sparkline (intraday price chart) within a table cell.
 */
class StockerSparklineCellRenderer : JPanel(), TableCellRenderer {

    private var intradayData: StockerIntradayData? = null
    var redUp: Boolean = true

    init {
        isOpaque = true
    }

    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean,
        hasFocus: Boolean, row: Int, column: Int,
    ): Component {
        intradayData = value as? StockerIntradayData
        background = if (isSelected) table.selectionBackground else table.background
        return this
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)

        val data = intradayData ?: return
        if (data.prices.isEmpty()) return

        val g2d = g.create() as Graphics2D
        try {
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val prices = data.prices
            val close = data.close
            val padding = 3
            val w = width - padding * 2
            val h = height - padding * 2
            if (w <= 0 || h <= 0) return

            // Find min/max for scaling
            var min = close
            var max = close
            for (price in prices) {
                if (price < min) min = price
                if (price > max) max = price
            }

            // Add some padding to the range
            var range = max - min
            if (range < 0.001) {
                range = close * 0.01 // default 1% range if flat
                min = close - range / 2
                max = close + range / 2
            }

            // Draw close line (yesterday's close reference)
            val closeY = padding + h - ((close - min) / range) * h
            g2d.color = CLOSE_LINE_COLOR
            g2d.stroke = BasicStroke(
                0.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10.0f, floatArrayOf(2.0f, 2.0f), 0.0f,
            )
            g2d.drawLine(padding, closeY.toInt(), padding + w, closeY.toInt())

            // Determine line color based on last price vs close
            val lastPrice = prices.last()
            val lineColor = when {
                lastPrice > close -> if (redUp) UP_COLOR else DOWN_COLOR
                lastPrice < close -> if (redUp) DOWN_COLOR else UP_COLOR
                else -> FLAT_COLOR
            }

            // Draw price line
            g2d.color = lineColor
            g2d.stroke = BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

            val path = Path2D.Double()
            // Use totalMinutes as the full X-axis width so the chart represents the entire trading day
            val totalPoints = maxOf(data.totalMinutes, prices.size)
            val stepX = w.toDouble() / maxOf(totalPoints - 1, 1)

            for (i in prices.indices) {
                val x = padding + i * stepX
                val y = padding + h - ((prices[i] - min) / range) * h
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            g2d.draw(path)

            // Draw filled area under the line with transparency
            val fillPath = Path2D.Double(path)
            val lastX = padding + (prices.size - 1) * stepX
            fillPath.lineTo(lastX, closeY)
            fillPath.lineTo(padding.toDouble(), closeY)
            fillPath.closePath()

            g2d.color = Color(lineColor.red, lineColor.green, lineColor.blue, 30)
            g2d.fill(fillPath)
        } finally {
            g2d.dispose()
        }
    }

    override fun getPreferredSize(): Dimension = Dimension(100, 26)

    companion object {
        private val UP_COLOR: Color = JBColor(Color(220, 38, 38), Color(239, 68, 68))
        private val DOWN_COLOR: Color = JBColor(Color(22, 163, 74), Color(34, 197, 94))
        private val FLAT_COLOR: Color = JBColor.GRAY
        private val CLOSE_LINE_COLOR: Color = JBColor(Color(156, 163, 175), Color(107, 114, 128))
    }
}
