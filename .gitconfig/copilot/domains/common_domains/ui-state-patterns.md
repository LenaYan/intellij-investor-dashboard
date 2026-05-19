# UI State Patterns

Scope:
- Governs how tool windows, tables, dialogs, and message-bus events manage UI state in Stocker.

## Goal
- Consistent, responsive UI that updates via message bus without tight coupling between components.

## Rules
- Tool windows are registered in `plugin.xml` via `<toolWindow>` with a `factoryClass`
- Each tool window tab corresponds to a market type (A-Share, HK, US, Crypto)
- Table data is managed by `StockerTableModel` (Java, extends `AbstractTableModel`)
- UI refresh flow: Background fetch → Message Bus publish → Listener receives → EDT update
- All table model mutations (add/remove/update rows) must happen on EDT
- Three-state sorting is handled by `StockerSortState` enum: `NONE → ASC → DESC → NONE`
- Color patterns are driven by `StockerQuoteColorPattern` enum applied in cell renderers
- Dialogs extend `DialogWrapper` (IntelliJ Platform base class)
- User preferences affecting display (columns, color pattern) are read from `StockerSettingState`

## Message Bus Topics

| Topic | Publisher | Subscribers |
|-------|----------|-------------|
| QuoteUpdate | Background refresh timer | Table views (all tabs) |
| QuoteReload | User action (refresh/add/delete) | Table views (affected tab) |
| QuoteDelete | User action (remove stock) | Table views (affected tab) |

## Anti-Patterns
- Directly calling `tableModel.fireTableDataChanged()` from a background thread
- Storing UI component references in services or entities
- Creating multiple instances of the same tool window content
- Bypassing message bus to directly push data into table models
- Using `JOptionPane` instead of `DialogWrapper` for dialogs
- Hardcoding column indices — use `StockerTableColumn` enum ordinals

## Verified Against
- `src/main/java/com/vermouthx/stocker/views/StockerTableView.java`
- `src/main/java/com/vermouthx/stocker/components/StockerTableModel.java`
- `src/main/kotlin/com/vermouthx/stocker/views/windows/StockerToolWindow.kt`
