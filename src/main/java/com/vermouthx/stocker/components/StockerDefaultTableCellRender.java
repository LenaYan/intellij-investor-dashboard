package com.vermouthx.stocker.components;

import com.intellij.ui.JBColor;
import com.vermouthx.stocker.settings.StockerSetting;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;

public class StockerDefaultTableCellRender extends DefaultTableCellRenderer {

    private static final Color FOCUS_FOREGROUND = new JBColor(new Color(204, 153, 0), new Color(255, 204, 0));

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
        Component component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

        setHorizontalAlignment(SwingConstants.CENTER);
        Border innerPadding = BorderFactory.createEmptyBorder(2, 8, 2, 8);
        boolean isLastVisibleColumn = column == table.getColumnCount() - 1;
        Border dividerBorder = isLastVisibleColumn
            ? innerPadding
            : BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, table.getGridColor()),
                innerPadding
            );
        setBorder(dividerBorder);

        if (!isSelected) {
            setBackground(table.getBackground());
            if (isFocusedRow(table, row)) {
                setForeground(FOCUS_FOREGROUND);
            } else {
                setForeground(table.getForeground());
            }
        }

        return component;
    }

    protected boolean isFocusedRow(JTable table, int row) {
        try {
            // Column 0 in the model is the code
            Object codeObj = table.getModel().getValueAt(row, 0);
            if (codeObj != null) {
                return StockerSetting.Companion.getInstance().isStockFocused(codeObj.toString());
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Apply foreground color, respecting focus state.
     * Focused rows always display in yellow regardless of column-specific color.
     */
    protected void applyForeground(JTable table, int row, Color color) {
        if (isFocusedRow(table, row)) {
            setForeground(FOCUS_FOREGROUND);
        } else {
            setForeground(color);
        }
    }
}
