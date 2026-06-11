package com.vermouthx.stocker.listeners

import com.vermouthx.stocker.views.StockerTableView
import javax.swing.SwingUtilities

class StockerQuoteReloadListener(private val myTableView: StockerTableView) : StockerQuoteReloadNotifier {

    override fun clear() {
        SwingUtilities.invokeLater {
            val tableModel = myTableView.tableModel
            synchronized(tableModel) {
                tableModel.rowCount = 0
                myTableView.clearSortState()
            }
        }
    }
}
