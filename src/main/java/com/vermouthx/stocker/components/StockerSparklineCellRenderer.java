package com.vermouthx.stocker.components;

import com.intellij.ui.JBColor;
import com.vermouthx.stocker.entities.StockerIntradayData;

import javax.swing.*;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.geom.Path2D;
import java.util.List;

/**
 * Renders a mini sparkline (intraday price chart) within a table cell.
 */
public class StockerSparklineCellRenderer extends JPanel implements TableCellRenderer {

    private static final Color UP_COLOR = new JBColor(new Color(220, 38, 38), new Color(239, 68, 68));
    private static final Color DOWN_COLOR = new JBColor(new Color(22, 163, 74), new Color(34, 197, 94));
    private static final Color FLAT_COLOR = JBColor.GRAY;
    private static final Color CLOSE_LINE_COLOR = new JBColor(new Color(156, 163, 175), new Color(107, 114, 128));

    private StockerIntradayData intradayData;
    private boolean redUp = true;

    public StockerSparklineCellRenderer() {
        setOpaque(true);
    }

    public void setRedUp(boolean redUp) {
        this.redUp = redUp;
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value,
                                                   boolean isSelected, boolean hasFocus, int row, int column) {
        if (value instanceof StockerIntradayData) {
            this.intradayData = (StockerIntradayData) value;
        } else {
            this.intradayData = null;
        }

        if (isSelected) {
            setBackground(table.getSelectionBackground());
        } else {
            setBackground(table.getBackground());
        }

        return this;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        if (intradayData == null || intradayData.getPrices().isEmpty()) {
            return;
        }

        Graphics2D g2d = (Graphics2D) g.create();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        List<Double> prices = intradayData.getPrices();
        double close = intradayData.getClose();
        int padding = 3;
        int width = getWidth() - padding * 2;
        int height = getHeight() - padding * 2;

        if (width <= 0 || height <= 0) {
            g2d.dispose();
            return;
        }

        // Find min/max for scaling
        double min = close;
        double max = close;
        for (double price : prices) {
            min = Math.min(min, price);
            max = Math.max(max, price);
        }

        // Add some padding to the range
        double range = max - min;
        if (range < 0.001) {
            range = close * 0.01; // default 1% range if flat
            min = close - range / 2;
            max = close + range / 2;
        }

        // Draw close line (yesterday's close reference)
        double closeY = padding + height - ((close - min) / range) * height;
        g2d.setColor(CLOSE_LINE_COLOR);
        g2d.setStroke(new BasicStroke(0.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10.0f, new float[]{2.0f, 2.0f}, 0.0f));
        g2d.drawLine(padding, (int) closeY, padding + width, (int) closeY);

        // Determine line color based on last price vs close
        double lastPrice = prices.get(prices.size() - 1);
        Color lineColor;
        if (lastPrice > close) {
            lineColor = redUp ? UP_COLOR : DOWN_COLOR;
        } else if (lastPrice < close) {
            lineColor = redUp ? DOWN_COLOR : UP_COLOR;
        } else {
            lineColor = FLAT_COLOR;
        }

        // Draw price line
        g2d.setColor(lineColor);
        g2d.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        Path2D path = new Path2D.Double();
        double stepX = (double) width / Math.max(prices.size() - 1, 1);

        for (int i = 0; i < prices.size(); i++) {
            double x = padding + i * stepX;
            double y = padding + height - ((prices.get(i) - min) / range) * height;

            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        g2d.draw(path);

        // Draw filled area under the line with transparency
        Path2D fillPath = new Path2D.Double(path);
        double lastX = padding + (prices.size() - 1) * stepX;
        fillPath.lineTo(lastX, closeY);
        fillPath.lineTo(padding, closeY);
        fillPath.closePath();

        Color fillColor = new Color(lineColor.getRed(), lineColor.getGreen(), lineColor.getBlue(), 30);
        g2d.setColor(fillColor);
        g2d.fill(fillPath);

        g2d.dispose();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(100, 26);
    }
}
