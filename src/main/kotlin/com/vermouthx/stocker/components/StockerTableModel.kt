package com.vermouthx.stocker.components

import javax.swing.table.DefaultTableModel

class StockerTableModel : DefaultTableModel() {
    override fun isCellEditable(row: Int, column: Int): Boolean = false
}
