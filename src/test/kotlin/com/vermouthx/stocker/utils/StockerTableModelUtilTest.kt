package com.vermouthx.stocker.utils

import com.vermouthx.stocker.enums.StockerTableColumn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import javax.swing.event.TableModelEvent
import javax.swing.table.DefaultTableModel

class StockerTableModelUtilTest {

    private lateinit var model: DefaultTableModel
    private val updateEvents = mutableListOf<TableModelEvent>()

    @BeforeEach
    fun setUp() {
        model = DefaultTableModel()
        model.setColumnIdentifiers(arrayOf(StockerTableColumn.SYMBOL.name, StockerTableColumn.CURRENT.name))
        model.addRow(arrayOf<Any?>("SH600519", 1270.0))
        updateEvents.clear()
        model.addTableModelListener { e ->
            if (e.type == TableModelEvent.UPDATE) updateEvents.add(e)
        }
    }

    @Test
    fun `setIfChanged fires exactly one update event when the value differs`() {
        StockerTableModelUtil.setIfChanged(model, 0, 1, 1271.0)
        assertEquals(1, updateEvents.size)
        assertEquals(1271.0, model.getValueAt(0, 1))
    }

    @Test
    fun `setIfChanged fires no event when the value is unchanged`() {
        StockerTableModelUtil.setIfChanged(model, 0, 1, 1270.0)
        assertEquals(0, updateEvents.size)
    }

    @Test
    fun `setIfChanged is a no-op for out-of-range coordinates`() {
        StockerTableModelUtil.setIfChanged(model, 0, -1, 9.9)
        StockerTableModelUtil.setIfChanged(model, 0, 5, 9.9)
        StockerTableModelUtil.setIfChanged(model, 3, 1, 9.9)
        assertEquals(0, updateEvents.size)
    }

    @Test
    fun `rowIndexByCode maps every row's symbol to its model index`() {
        model.addRow(arrayOf<Any?>("00700", 410.0))
        model.addRow(arrayOf<Any?>("NVDA", 130.0))
        val index = StockerTableModelUtil.rowIndexByCode(model)
        assertEquals(mapOf("SH600519" to 0, "00700" to 1, "NVDA" to 2), index)
    }

    @Test
    fun `rowIndexByCode skips rows with a null symbol cell`() {
        model.addRow(arrayOf<Any?>(null, 1.0))
        val index = StockerTableModelUtil.rowIndexByCode(model)
        assertEquals(mapOf("SH600519" to 0), index)
    }
}
