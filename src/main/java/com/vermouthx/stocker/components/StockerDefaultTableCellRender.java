package com.vermouthx.stocker.components;

import com.intellij.ui.JBColor;
import com.vermouthx.stocker.settings.StockerSetting;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;

public class StockerDefaultTableCellRender extends DefaultTableCellRenderer {

    private static final Color FOCUS_BACKGROUND = new JBColor(new Color(255, 248, 220), new Color(78, 68, 20));

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
            if (isFocusedRow(table, row)) {
                setBackground(FOCUS_BACKGROUND);
            } else {
                setBackground(table.getBackground());
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
}
