package com.vermouthx.stocker.utils;

import com.vermouthx.stocker.enums.StockerTableColumn;

import javax.swing.table.DefaultTableModel;
import java.util.Objects;

public final class StockerTableModelUtil {

    public static int existAt(DefaultTableModel tableModel, String code) {
        int codeCol = tableModel.findColumn(StockerTableColumn.SYMBOL.name());
        if (codeCol < 0) codeCol = 0;
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            Object cell = tableModel.getValueAt(i, codeCol);
            if (cell != null && code != null && code.equals(cell.toString())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Update a cell only when the new value differs from the existing one, firing
     * {@code fireTableCellUpdated} exactly when a change happened. No-op for unknown columns.
     */
    public static void setIfChanged(DefaultTableModel model, int row, int col, Object newValue) {
        if (col < 0 || col >= model.getColumnCount() || row < 0 || row >= model.getRowCount()) {
            return;
        }
        Object existing = model.getValueAt(row, col);
        if (!Objects.equals(existing, newValue)) {
            model.setValueAt(newValue, row, col);
            model.fireTableCellUpdated(row, col);
        }
    }

    /** Resolve a model column index by {@link StockerTableColumn} enum, returning -1 if absent. */
    public static int colOf(DefaultTableModel model, StockerTableColumn column) {
        return model.findColumn(column.name());
    }

    private StockerTableModelUtil() {
    }
}
