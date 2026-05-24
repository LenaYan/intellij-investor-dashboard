package com.vermouthx.stocker.views;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.vermouthx.stocker.StockerBundle;
import com.vermouthx.stocker.components.StockerDefaultTableCellRender;
import com.vermouthx.stocker.components.StockerSparklineCellRenderer;
import com.vermouthx.stocker.components.StockerTableHeaderRender;
import com.vermouthx.stocker.components.StockerTableModel;
import com.vermouthx.stocker.entities.StockerIntradayData;
import com.vermouthx.stocker.entities.StockerQuote;
import com.vermouthx.stocker.entities.StockerSuggestion;
import com.vermouthx.stocker.enums.StockerMarketType;
import com.vermouthx.stocker.enums.StockerSortState;
import com.vermouthx.stocker.enums.StockerTableColumn;
import com.vermouthx.stocker.finance.FinanceWatchlistActions;
import com.vermouthx.stocker.settings.StockerSetting;
import com.vermouthx.stocker.utils.StockerActionUtil;
import com.vermouthx.stocker.utils.StockerNumberFormat;
import com.vermouthx.stocker.utils.StockerPinyinUtil;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class StockerTableView implements Disposable {

    private static final List<StockerTableView> tableViews = Collections.synchronizedList(new ArrayList<>());

    private JPanel mPane;
    private JScrollPane tbPane;
    private Color upColor;
    private Color downColor;
    private Color zeroColor;
    private JBTable tbBody;
    private StockerTableModel tbModel;

    private final ComboBox<String> cbIndex = new ComboBox<>();
    private final JBLabel lbIndexValue = new JBLabel("", SwingConstants.CENTER);
    private final JBLabel lbIndexExtent = new JBLabel("", SwingConstants.CENTER);
    private final JBLabel lbIndexPercent = new JBLabel("", SwingConstants.CENTER);
    private List<StockerQuote> indices = new ArrayList<>();

    // Cache renderers to avoid creating new instances on every refresh
    private final StockerDefaultTableCellRender defaultRenderer = new StockerDefaultTableCellRender();
    private final StockerDefaultTableCellRender codeRenderer = new CodeCellRenderer();
    private final StockerDefaultTableCellRender numericRenderer = new NumericCellRenderer();
    private final StockerDefaultTableCellRender changeRenderer = new ChangeCellRenderer();
    private final StockerDefaultTableCellRender percentRenderer = new PercentCellRenderer();
    private final StockerDefaultTableCellRender costRenderer = new CostCellRenderer();
    private final StockerDefaultTableCellRender netProfitRenderer = new NetProfitCellRenderer();
    private final StockerSparklineCellRenderer sparklineRenderer = new StockerSparklineCellRenderer();
    private final StockerDefaultTableCellRender healthRenderer = new HealthCellRenderer();
    private final StockerDefaultTableCellRender distanceRenderer = new DistanceCellRenderer();

    // Sorting state
    private StockerTableHeaderRender headerRenderer;
    private int lastSortColumn = -1;
    private StockerSortState currentSortState = StockerSortState.NONE;
    // Backup data only when sorting is active (cleared when returning to NONE state)
    private List<Object[]> sortBackupData = null;
    private String popupTargetCode = null;
    private String popupTargetName = null;

    private volatile boolean disposed = false;

    public StockerTableView() {
        tableViews.add(this);
        syncColorPatternSetting();
        initPane();
        initTable();
    }

    /**
     * Clean up resources and remove this instance from the registry.
     * Called automatically when the parent Disposable is disposed.
     */
    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        tableViews.remove(this);

        // Clear data structures to help with garbage collection
        indices.clear();
        if (sortBackupData != null) {
            sortBackupData.clear();
            sortBackupData = null;
        }

        // Clear table model
        if (tbModel != null) {
            tbModel.setRowCount(0);
        }
    }

    public void syncIndices(List<StockerQuote> indices) {
        SwingUtilities.invokeLater(() -> {
            this.indices = indices;
            StockerSetting setting = StockerSetting.Companion.getInstance();

            boolean shouldRefresh = cbIndex.getItemCount() != indices.size();
            if (!shouldRefresh) {
                for (int i = 0; i < indices.size(); i++) {
                    StockerQuote index = indices.get(i);
                    String displayName = setting.getDisplayName(index.getCode(), index.getName());
                    if (!Objects.equals(displayName, cbIndex.getItemAt(i))) {
                        shouldRefresh = true;
                        break;
                    }
                }
            }

            if (shouldRefresh && !indices.isEmpty()) {
                String selectedDisplayName = cbIndex.getSelectedItem() == null ? null : cbIndex.getSelectedItem().toString();
                String selectedCode = findIndexCodeByDisplayName(selectedDisplayName, setting);
                cbIndex.removeAllItems();
                indices.forEach(i -> {
                    String displayName = setting.getDisplayName(i.getCode(), i.getName());
                    cbIndex.addItem(displayName);
                });
                if (selectedCode != null) {
                    for (int i = 0; i < indices.size(); i++) {
                        if (indices.get(i).getCode().equals(selectedCode)) {
                            cbIndex.setSelectedIndex(i);
                            break;
                        }
                    }
                } else if (!indices.isEmpty()) {
                    cbIndex.setSelectedIndex(0);
                }
            }
            syncColorPatternSetting();
            updateIndex();
        });
    }

    /**
     * Update sparkline intraday data for the given codes.
     */
    public void syncIntradayData(Map<String, StockerIntradayData> intradayMap) {
        SwingUtilities.invokeLater(() -> {
            synchronized (tbModel) {
                int sparklineColIndex = tbModel.findColumn(sparklineColumn);
                if (sparklineColIndex == -1) return;

                for (int row = 0; row < tbModel.getRowCount(); row++) {
                    Object codeObj = tbModel.getValueAt(row, 0);
                    if (codeObj != null) {
                        StockerIntradayData data = intradayMap.get(codeObj.toString());
                        if (data != null) {
                            tbModel.setValueAt(data, row, sparklineColIndex);
                            tbModel.fireTableCellUpdated(row, sparklineColIndex);
                        }
                    }
                }
            }
        });
    }

    private void syncColorPatternSetting() {
        StockerSetting setting = StockerSetting.Companion.getInstance();
        switch (setting.getQuoteColorPattern()) {
            case RED_UP_GREEN_DOWN:
                upColor = JBColor.RED;
                downColor = JBColor.GREEN;
                zeroColor = JBColor.GRAY;
                sparklineRenderer.setRedUp(true);
                break;
            case GREEN_UP_RED_DOWN:
                upColor = JBColor.GREEN;
                downColor = JBColor.RED;
                zeroColor = JBColor.GRAY;
                sparklineRenderer.setRedUp(false);
                break;
            default:
                upColor = JBColor.foreground();
                downColor = JBColor.foreground();
                zeroColor = JBColor.foreground();
                sparklineRenderer.setRedUp(true);
                break;
        }
    }

    private void updateIndex() {
        if (cbIndex.getSelectedIndex() != -1 && cbIndex.getSelectedItem() != null) {
            String selectedDisplayName = cbIndex.getSelectedItem().toString();
            StockerSetting setting = StockerSetting.Companion.getInstance();
            String selectedCode = findIndexCodeByDisplayName(selectedDisplayName, setting);

            for (StockerQuote index : indices) {
                String displayName = setting.getDisplayName(index.getCode(), index.getName());
                boolean isSelected = selectedCode != null ? index.getCode().equals(selectedCode) : displayName.equals(selectedDisplayName);
                if (isSelected) {
                    lbIndexValue.setText(Double.toString(index.getCurrent()));
                    lbIndexExtent.setText(Double.toString(index.getChange()));
                    lbIndexPercent.setText(index.getPercentage() + "%");
                    double value = index.getPercentage();
                    if (value > 0) {
                        lbIndexValue.setForeground(upColor);
                        lbIndexExtent.setForeground(upColor);
                        lbIndexPercent.setForeground(upColor);
                    } else if (value < 0) {
                        lbIndexValue.setForeground(downColor);
                        lbIndexExtent.setForeground(downColor);
                        lbIndexPercent.setForeground(downColor);
                    } else {
                        lbIndexValue.setForeground(zeroColor);
                        lbIndexExtent.setForeground(zeroColor);
                        lbIndexPercent.setForeground(zeroColor);
                    }
                    break;
                }
            }
        }
    }

    private String findIndexCodeByDisplayName(String displayName, StockerSetting setting) {
        if (displayName == null || displayName.isEmpty()) {
            return null;
        }
        for (StockerQuote index : indices) {
            String code = index.getCode();
            String customName = setting.getCustomName(code);
            if (customName != null && customName.equals(displayName)) {
                return code;
            }
            String originalName = index.getName();
            if (displayName.equals(originalName)) {
                return code;
            }
            if (displayName.equals(StockerPinyinUtil.INSTANCE.toPinyin(originalName))) {
                return code;
            }
        }
        return null;
    }

    private void initPane() {
        tbPane = new JBScrollPane();
        tbPane.setBorder(BorderFactory.createEmptyBorder());
        tbPane.setViewportBorder(BorderFactory.createEmptyBorder());

        JPanel iPane = new JPanel(new GridLayout(1, 4, 8, 0)); // Add horizontal gap between components
        iPane.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border()),
            BorderFactory.createEmptyBorder(8, 12, 8, 12) // Add padding to index panel
        ));

        // Style the index components
        Font indexFont = lbIndexValue.getFont().deriveFont(Font.BOLD, lbIndexValue.getFont().getSize() + 1f);
        lbIndexValue.setFont(indexFont);
        lbIndexExtent.setFont(indexFont);
        lbIndexPercent.setFont(indexFont);

        iPane.add(cbIndex);
        iPane.add(lbIndexValue);
        iPane.add(lbIndexExtent);
        iPane.add(lbIndexPercent);
        cbIndex.addItemListener(i -> updateIndex());
        mPane = new JPanel(new BorderLayout());
        mPane.add(tbPane, BorderLayout.CENTER);
        mPane.add(iPane, BorderLayout.SOUTH);
    }

    private static final String codeColumn = StockerTableColumn.SYMBOL.name();
    private static final String nameColumn = StockerTableColumn.NAME.name();
    private static final String currentColumn = StockerTableColumn.CURRENT.name();
    private static final String openingColumn = StockerTableColumn.OPENING.name();
    private static final String closeColumn = StockerTableColumn.CLOSE.name();
    private static final String lowColumn = StockerTableColumn.LOW.name();
    private static final String highColumn = StockerTableColumn.HIGH.name();
    private static final String changeColumn = StockerTableColumn.CHANGE.name();
    private static final String percentColumn = StockerTableColumn.CHANGE_PERCENT.name();
    private static final String costPriceColumn = StockerTableColumn.COST_PRICE.name();
    private static final String holdingsColumn = StockerTableColumn.HOLDINGS.name();
    private static final String netProfitColumn = StockerTableColumn.NET_PROFIT.name();
    private static final String sparklineColumn = StockerTableColumn.SPARKLINE.name();
    private static final String healthColumn = StockerTableColumn.HEALTH.name();
    private static final String distanceColumn = StockerTableColumn.DISTANCE.name();
    private static final List<String> allColumnNames;

    static {
        allColumnNames = new ArrayList<>();
        for (StockerTableColumn col : StockerTableColumn.values()) {
            allColumnNames.add(col.name());
        }
    }

    private void initTable() {
        tbModel = new StockerTableModel();
        tbBody = new JBTable();
        JPopupMenu rowPopupMenu = createRowPopupMenu();

        tbBody.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                if (e.isTemporary() || rowPopupMenu.isVisible()) {
                    return;
                }
                tbBody.clearSelection();
            }
        });
        tbBody.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                handleTableMouseEvent(e, rowPopupMenu);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                handleTableMouseEvent(e, rowPopupMenu);
            }
        });

        tbModel.setColumnIdentifiers(new String[]{codeColumn, nameColumn, currentColumn, openingColumn, closeColumn, lowColumn, highColumn, changeColumn, percentColumn, costPriceColumn, holdingsColumn, netProfitColumn, sparklineColumn, healthColumn, distanceColumn});

        tbBody.setModel(tbModel);
        tbBody.setAutoCreateColumnsFromModel(false);
        updateLocalizedHeaders();

        // Table grid styling
        tbBody.setRowHeight(32);
        tbBody.setIntercellSpacing(new Dimension(0, 1));
        tbBody.setShowGrid(true);
        tbBody.setShowVerticalLines(false);
        tbBody.setShowHorizontalLines(true);
        tbBody.setGridColor(JBColor.namedColor("Table.gridColor", JBColor.border()));
        tbBody.setFillsViewportHeight(true);
        tbBody.getColumnModel().setColumnMargin(0);

        // Use IDE theme colors for selection
        tbBody.setSelectionBackground(JBColor.namedColor("Table.selectionBackground", UIManager.getColor("Table.selectionBackground")));
        tbBody.setSelectionForeground(JBColor.namedColor("Table.selectionForeground", UIManager.getColor("Table.selectionForeground")));

        // Avoid extra separator lines from custom LAF header UI; renderer will own divider painting.
        tbBody.getTableHeader().setUI(new javax.swing.plaf.basic.BasicTableHeaderUI());
        tbBody.getTableHeader().setReorderingAllowed(false);
        tbBody.getTableHeader().setPreferredSize(new Dimension(tbBody.getTableHeader().getWidth(), 30)); // Compact header to match rows
        tbBody.getTableHeader().setBorder(BorderFactory.createEmptyBorder());
        headerRenderer = new StockerTableHeaderRender();
        tbBody.getTableHeader().setDefaultRenderer(headerRenderer);

        // Add header click listener for sorting with visual feedback
        tbBody.getTableHeader().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int column = tbBody.getTableHeader().columnAtPoint(e.getPoint());
                if (column != -1) {
                    sortByColumn(column);
                }
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                tbBody.getTableHeader().setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                tbBody.getTableHeader().setCursor(Cursor.getDefaultCursor());
            }
        });

        applyColumnVisibility();
        tbPane.setViewportView(tbBody);
    }

    private void handleTableMouseEvent(MouseEvent event, JPopupMenu rowPopupMenu) {
        int row = tbBody.rowAtPoint(event.getPoint());
        if (tbBody.isFocusOwner() && row == -1 && !event.isPopupTrigger()) {
            tbBody.clearSelection();
            return;
        }

        if (row != -1 && (event.isPopupTrigger() || SwingUtilities.isRightMouseButton(event))) {
            tbBody.setRowSelectionInterval(row, row);
            popupTargetCode = getStringValueAt(row, 0);
            popupTargetName = getStringValueAt(row, 1);
        }

        if (event.isPopupTrigger() && row != -1) {
            rowPopupMenu.show(tbBody, event.getX(), event.getY());
        }
    }

    private JPopupMenu createRowPopupMenu() {
        JPopupMenu popupMenu = new JPopupMenu();

        // Focus toggle menu item
        JMenuItem focusMenuItem = new JMenuItem();
        focusMenuItem.setOpaque(true);
        focusMenuItem.setRolloverEnabled(true);
        focusMenuItem.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        focusMenuItem.addActionListener(e -> toggleFocusSelectedStock());

        // Add-to-Claude-watchlist menu item (writes ~/Claude/finance/watchlist.json)
        JMenuItem addWatchlistItem = new JMenuItem(StockerBundle.message("menu.add.to.claude.watchlist"));
        addWatchlistItem.setOpaque(true);
        addWatchlistItem.setRolloverEnabled(true);
        addWatchlistItem.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        addWatchlistItem.addActionListener(e -> addSelectedToClaudeWatchlist());

        // View entry-timing buy plan (reads ~/Claude/finance/reports/<today>/entry-timing.md)
        JMenuItem entryTimingItem = new JMenuItem(StockerBundle.message("menu.view.entry.timing"));
        entryTimingItem.setOpaque(true);
        entryTimingItem.setRolloverEnabled(true);
        entryTimingItem.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        entryTimingItem.addActionListener(e -> showSelectedEntryTimingPlan());

        // View bull-bear debate (reads ~/Claude/finance/reports/<today>/bull-bear-<symbol>.md)
        JMenuItem bullBearItem = new JMenuItem(StockerBundle.message("menu.view.bull.bear"));
        bullBearItem.setOpaque(true);
        bullBearItem.setRolloverEnabled(true);
        bullBearItem.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        bullBearItem.addActionListener(e -> showSelectedBullBear());

        // View style jury (reads ~/Claude/finance/reports/<today>/style-jury-<symbol>.md)
        JMenuItem styleJuryItem = new JMenuItem(StockerBundle.message("menu.view.style.jury"));
        styleJuryItem.setOpaque(true);
        styleJuryItem.setRolloverEnabled(true);
        styleJuryItem.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        styleJuryItem.addActionListener(e -> showSelectedStyleJury());

        // Delete menu item
        JMenuItem deleteMenuItem = new JMenuItem(StockerBundle.message("menu.delete"));
        deleteMenuItem.setOpaque(true);
        deleteMenuItem.setRolloverEnabled(true);
        deleteMenuItem.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));

        Color defaultBackground = JBColor.namedColor("MenuItem.background", UIManager.getColor("MenuItem.background"));
        Color defaultForeground = JBColor.namedColor("MenuItem.foreground", UIManager.getColor("MenuItem.foreground"));
        Color hoverBackground = JBColor.namedColor(
                "MenuItem.selectionBackground",
                JBColor.namedColor("List.selectionBackground", tbBody.getSelectionBackground())
        );
        Color hoverForeground = JBColor.namedColor(
                "MenuItem.selectionForeground",
                JBColor.namedColor("List.selectionForeground", tbBody.getSelectionForeground())
        );

        for (JMenuItem item : new JMenuItem[]{focusMenuItem, addWatchlistItem, entryTimingItem, bullBearItem, styleJuryItem, deleteMenuItem}) {
            item.setBackground(defaultBackground);
            item.setForeground(defaultForeground);
            item.getModel().addChangeListener(ev -> {
                ButtonModel model = item.getModel();
                boolean hovering = model.isArmed() || model.isRollover();
                item.setBackground(hovering ? hoverBackground : defaultBackground);
                item.setForeground(hovering ? hoverForeground : defaultForeground);
            });
        }

        deleteMenuItem.addActionListener(e -> deleteSelectedStock());
        popupMenu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                // Update focus menu item text based on current state
                String code = popupTargetCode;
                if (code != null && StockerSetting.Companion.getInstance().isStockFocused(code)) {
                    focusMenuItem.setText(StockerBundle.message("menu.unfocus"));
                } else {
                    focusMenuItem.setText(StockerBundle.message("menu.focus"));
                }
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                clearPopupStateLater(popupMenu);
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
                clearPopupStateLater(popupMenu);
            }
        });
        popupMenu.add(focusMenuItem);
        popupMenu.addSeparator();
        popupMenu.add(addWatchlistItem);
        popupMenu.add(entryTimingItem);
        popupMenu.add(bullBearItem);
        popupMenu.add(styleJuryItem);
        popupMenu.addSeparator();
        popupMenu.add(deleteMenuItem);
        return popupMenu;
    }

    /**
     * Resolve (code, name) from the popup target (if a right-click set them) or from the
     * currently selected row, then hand them to [action]. Clears popup state in all paths,
     * including when no row is resolvable. The 6 popup actions previously open-coded this.
     */
    private void withSelectedRow(java.util.function.BiConsumer<String, String> action) {
        try {
            String code = popupTargetCode;
            String name = popupTargetName;
            if (code == null) {
                int selectedRow = tbBody.getSelectedRow();
                if (selectedRow < 0) return;
                code = getStringValueAt(selectedRow, 0);
                name = getStringValueAt(selectedRow, 1);
            }
            if (code == null) return;
            action.accept(code, name);
        } finally {
            clearPopupTarget();
        }
    }

    private void showSelectedBullBear() {
        withSelectedRow((code, name) ->
            com.vermouthx.stocker.finance.FinanceReportActions.showBullBear(tbBody, code, name));
    }

    private void showSelectedStyleJury() {
        withSelectedRow((code, name) ->
            com.vermouthx.stocker.finance.FinanceReportActions.showStyleJury(tbBody, code, name));
    }

    private void showSelectedEntryTimingPlan() {
        withSelectedRow((code, name) ->
            com.vermouthx.stocker.finance.FinanceEntryTimingActions.showPopup(tbBody, code, name));
    }

    private void addSelectedToClaudeWatchlist() {
        withSelectedRow((code, name) -> {
            // Find current price from the selected row (column 2 in the model).
            int row = tbBody.getSelectedRow();
            Double refPrice = null;
            if (row >= 0) {
                Object v = tbModel.getValueAt(row, 2);
                if (v != null) refPrice = parseDouble(v);
            }
            FinanceWatchlistActions.addToWatchlist(code, name, refPrice);
        });
    }

    private void toggleFocusSelectedStock() {
        withSelectedRow((code, name) -> {
            StockerSetting.Companion.getInstance().toggleFocusStock(code);
            tbBody.repaint();
        });
    }

    private void deleteSelectedStock() {
        withSelectedRow((code, name) -> {
            StockerSetting setting = StockerSetting.Companion.getInstance();
            StockerMarketType market = setting.marketOf(code);
            if (market == null) return;
            StockerActionUtil.removeStock(market,
                new StockerSuggestion(code, name == null ? code : name, market));
        });
    }

    private String getStringValueAt(int row, int column) {
        Object value = tbModel.getValueAt(row, column);
        return value == null ? null : value.toString();
    }

    private void clearPopupTarget() {
        popupTargetCode = null;
        popupTargetName = null;
    }

    private void clearPopupStateLater(JPopupMenu popupMenu) {
        SwingUtilities.invokeLater(() -> {
            if (popupMenu.isVisible()) {
                return;
            }
            clearPopupTarget();
            if (!tbBody.isFocusOwner()) {
                tbBody.clearSelection();
            }
        });
    }

    private void applyColumnVisibility() {
        StockerSetting setting = StockerSetting.Companion.getInstance();
        List<String> visibleColumns = setting.getVisibleTableColumns();

        tbBody.createDefaultColumnsFromModel();
        updateLocalizedHeaders();

        for (String columnName : allColumnNames) {
            if (!visibleColumns.contains(columnName)) {
                TableColumn tableColumn = getColumnIfPresent(columnName);
                if (tableColumn != null) {
                    tbBody.removeColumn(tableColumn);
                }
            }
        }

        // Re-apply after column model rebuild to keep header/body cell geometry in sync.
        tbBody.getColumnModel().setColumnMargin(0);
        applyColumnRenderers();
    }

    private void updateLocalizedHeaders() {
        for (int i = 0; i < tbBody.getColumnCount(); i++) {
            TableColumn tableColumn = tbBody.getColumnModel().getColumn(i);
            int modelIndex = tableColumn.getModelIndex();
            if (modelIndex >= 0 && modelIndex < StockerTableColumn.values().length) {
                StockerTableColumn col = StockerTableColumn.values()[modelIndex];
                tableColumn.setIdentifier(col.name());
                tableColumn.setHeaderValue(col.getTitle());
            }
        }
    }

    public void refreshColumnVisibility() {
        applyColumnVisibility();
        tbBody.revalidate();
        tbBody.repaint();
    }

    public static void refreshAllColumnVisibility() {
        SwingUtilities.invokeLater(() -> {
            synchronized (tableViews) {
                for (StockerTableView view : tableViews) {
                    view.refreshColumnVisibility();
                }
            }
        });
    }

    public void refreshColorPattern() {
        syncColorPatternSetting();
        updateIndex();
        tbBody.revalidate();
        tbBody.repaint();
    }

    public static void refreshAllColorPatterns() {
        SwingUtilities.invokeLater(() -> {
            synchronized (tableViews) {
                for (StockerTableView view : tableViews) {
                    view.refreshColorPattern();
                }
            }
        });
    }

    public static void refreshAllFinancialColumns() {
        SwingUtilities.invokeLater(() -> {
            synchronized (tableViews) {
                for (StockerTableView view : tableViews) {
                    view.refreshFinancialColumns();
                }
            }
        });
    }

    /**
     * Broadcast intraday data to all active table views.
     */
    public static void syncAllIntradayData(Map<String, StockerIntradayData> intradayMap) {
        if (intradayMap.isEmpty()) return;
        synchronized (tableViews) {
            for (StockerTableView view : tableViews) {
                view.syncIntradayData(intradayMap);
            }
        }
    }

    public void refreshFinancialColumns() {
        StockerSetting setting = StockerSetting.Companion.getInstance();
        int codeColumnIndex = tbModel.findColumn(codeColumn);
        int currentColumnIndex = tbModel.findColumn(currentColumn);
        int costPriceColumnIndex = tbModel.findColumn(costPriceColumn);
        int holdingsColumnIndex = tbModel.findColumn(holdingsColumn);
        int netProfitColumnIndex = tbModel.findColumn(netProfitColumn);

        for (int row = 0; row < tbModel.getRowCount(); row++) {
            Object codeValue = tbModel.getValueAt(row, codeColumnIndex);
            if (codeValue == null) {
                continue;
            }
            String code = codeValue.toString();

            Double costPrice = setting.getCostPrice(code);
            Integer holdings = setting.getHoldings(code);
            tbModel.setValueAt(StockerNumberFormat.formatPrice(costPrice), row, costPriceColumnIndex);
            tbModel.setValueAt(StockerNumberFormat.formatHoldings(holdings), row, holdingsColumnIndex);

            Double currentPrice = parseDouble(tbModel.getValueAt(row, currentColumnIndex));
            tbModel.setValueAt(StockerNumberFormat.formatNetProfit(currentPrice, costPrice, holdings),
                row, netProfitColumnIndex);
        }

        tbModel.fireTableDataChanged();
    }

    private void applyColumnRenderers() {
        applyRenderer(codeColumn, codeRenderer);
        applyRenderer(nameColumn, defaultRenderer);
        applyRenderer(currentColumn, numericRenderer);
        applyRenderer(openingColumn, numericRenderer);
        applyRenderer(closeColumn, numericRenderer);
        applyRenderer(lowColumn, numericRenderer);
        applyRenderer(highColumn, numericRenderer);
        applyRenderer(changeColumn, changeRenderer);
        applyRenderer(percentColumn, percentRenderer);
        applyRenderer(costPriceColumn, costRenderer);
        applyRenderer(holdingsColumn, numericRenderer);
        applyRenderer(netProfitColumn, netProfitRenderer);

        TableColumn sparkline = getColumnIfPresent(sparklineColumn);
        if (sparkline != null) {
            sparkline.setCellRenderer(sparklineRenderer);
            sparkline.setPreferredWidth(120);
            sparkline.setMinWidth(80);
        }
        TableColumn health = getColumnIfPresent(healthColumn);
        if (health != null) {
            health.setCellRenderer(healthRenderer);
            health.setPreferredWidth(50);
            health.setMinWidth(36);
            health.setMaxWidth(72);
        }
        TableColumn distance = getColumnIfPresent(distanceColumn);
        if (distance != null) {
            distance.setCellRenderer(distanceRenderer);
            distance.setPreferredWidth(180);
            distance.setMinWidth(110);
        }
    }

    private void applyRenderer(String columnIdentifier, javax.swing.table.TableCellRenderer renderer) {
        TableColumn col = getColumnIfPresent(columnIdentifier);
        if (col != null) col.setCellRenderer(renderer);
    }

    private TableColumn getColumnIfPresent(String identifier) {
        try {
            return tbBody.getColumn(identifier);
        } catch (IllegalArgumentException e) {
            // JTable.getColumn searches by identifier first, fall back to manual search
            for (int i = 0; i < tbBody.getColumnCount(); i++) {
                TableColumn col = tbBody.getColumnModel().getColumn(i);
                if (identifier.equals(col.getIdentifier())) {
                    return col;
                }
            }
            return null;
        }
    }

    private Double parsePercentage(String percentStr) {
        if (percentStr == null || percentStr.isEmpty()) {
            return null;
        }
        try {
            int percentIndex = percentStr.indexOf("%");
            if (percentIndex > 0) {
                return Double.parseDouble(percentStr.substring(0, percentIndex));
            }
            return Double.parseDouble(percentStr);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public JComponent getComponent() {
        return mPane;
    }

    public JBTable getTableBody() {
        return tbBody;
    }

    public DefaultTableModel getTableModel() {
        return tbModel;
    }

    /**
     * Clears the sort state and resets to unsorted view.
     * Should be called when table data is externally modified.
     */
    public void clearSortState() {
        currentSortState = StockerSortState.NONE;
        lastSortColumn = -1;
        // Clear backup data when sort is cleared externally
        if (sortBackupData != null) {
            sortBackupData.clear();
            sortBackupData = null;
        }
        if (headerRenderer != null) {
            headerRenderer.setSortState(-1, StockerSortState.NONE);
            if (tbBody != null && tbBody.getTableHeader() != null) {
                tbBody.getTableHeader().repaint();
            }
        }
    }

    private void sortByColumn(int column) {
        String columnName = tbBody.getColumnName(column);

        // Cycle through sort states: NONE -> ASCENDING -> DESCENDING -> NONE
        if (column == lastSortColumn) {
            // Same column clicked, cycle to next state
            switch (currentSortState) {
                case NONE:
                    currentSortState = StockerSortState.ASCENDING;
                    break;
                case ASCENDING:
                    currentSortState = StockerSortState.DESCENDING;
                    break;
                case DESCENDING:
                    currentSortState = StockerSortState.NONE;
                    break;
            }
        } else {
            // Different column clicked, start with ASCENDING
            lastSortColumn = column;
            currentSortState = StockerSortState.ASCENDING;
        }

        // Update header renderer
        headerRenderer.setSortState(column, currentSortState);
        tbBody.getTableHeader().repaint();

        sortTableData(columnName, currentSortState);
    }

    /**
     * Sort visible rows by `columnName` in the given direction, or restore the original order
     * when state == NONE. Keeps a single backup of the unsorted row data while sorting is
     * active so the user can cycle ASC→DESC→NONE without losing original order. Note: this
     * does a full row-copy on each call; for very large tables consider a TableRowSorter.
     */
    private void sortTableData(String columnName, StockerSortState sortState) {
        int rowCount = tbModel.getRowCount();
        if (rowCount == 0) {
            return;
        }

        // For NONE state, restore original data and clear backup
        if (sortState == StockerSortState.NONE) {
            if (sortBackupData != null && !sortBackupData.isEmpty()) {
                tbModel.setRowCount(0);
                for (Object[] row : sortBackupData) {
                    tbModel.addRow(row);
                }
                sortBackupData.clear();
                sortBackupData = null;
            }
            return;
        }

        // Capture original data before first sort (only once)
        if (sortBackupData == null) {
            sortBackupData = new ArrayList<>(rowCount);
            for (int i = 0; i < rowCount; i++) {
                Object[] row = new Object[tbModel.getColumnCount()];
                for (int j = 0; j < tbModel.getColumnCount(); j++) {
                    row[j] = tbModel.getValueAt(i, j);
                }
                sortBackupData.add(row);
            }
        }

        // Get the column index in the model
        int columnIndex = -1;
        for (int i = 0; i < tbModel.getColumnCount(); i++) {
            if (tbModel.getColumnName(i).equals(columnName)) {
                columnIndex = i;
                break;
            }
        }

        if (columnIndex == -1) {
            return;
        }

        // Skip sorting for non-sortable columns (sparkline, health, distance)
        if (columnName.equals(sparklineColumn) || columnName.equals(healthColumn) || columnName.equals(distanceColumn)) {
            return;
        }

        final int sortColumnIndex = columnIndex;
        final boolean ascending = (sortState == StockerSortState.ASCENDING);

        // Create lightweight index array instead of copying all data
        Integer[] indices = new Integer[rowCount];
        for (int i = 0; i < rowCount; i++) {
            indices[i] = i;
        }

        // Sort indices based on values - only references are sorted, not actual data
        java.util.Arrays.sort(indices, (i1, i2) -> {
            Object val1 = tbModel.getValueAt(i1, sortColumnIndex);
            Object val2 = tbModel.getValueAt(i2, sortColumnIndex);

            int result = 0;

            if (columnName.equals(codeColumn) || columnName.equals(nameColumn)) {
                // Alphabetical sorting
                String str1 = val1 != null ? val1.toString() : "";
                String str2 = val2 != null ? val2.toString() : "";
                result = str1.compareToIgnoreCase(str2);
            } else if (columnName.equals(percentColumn)) {
                // Numeric sorting for Change% column (parse percentage values)
                Double percent1 = parsePercentage(val1 != null ? val1.toString() : "");
                Double percent2 = parsePercentage(val2 != null ? val2.toString() : "");
                if (percent1 != null && percent2 != null) {
                    result = Double.compare(percent1, percent2);
                } else if (percent1 != null) {
                    result = 1;
                } else if (percent2 != null) {
                    result = -1;
                }
            } else {
                // Numeric sorting for all other columns (Current, Opening, Close, Low, High, Change)
                Double num1 = parseDouble(val1);
                Double num2 = parseDouble(val2);
                if (num1 != null && num2 != null) {
                    result = Double.compare(num1, num2);
                } else if (num1 != null) {
                    result = 1;
                } else if (num2 != null) {
                    result = -1;
                }
            }

            return ascending ? result : -result;
        });

        // Reorder rows based on sorted indices - minimal memory footprint
        java.util.List<Object[]> sortedRows = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            int sourceIndex = indices[i];
            Object[] row = new Object[tbModel.getColumnCount()];
            for (int j = 0; j < tbModel.getColumnCount(); j++) {
                row[j] = tbModel.getValueAt(sourceIndex, j);
            }
            sortedRows.add(row);
        }

        // Update table with sorted data
        tbModel.setRowCount(0);
        for (Object[] row : sortedRows) {
            tbModel.addRow(row);
        }
    }

    private Double parseDouble(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // Inner class for Code column renderer that strips BTC prefix from crypto codes
    private class CodeCellRenderer extends StockerDefaultTableCellRender {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            setHorizontalAlignment(DefaultTableCellRenderer.CENTER);

            // Strip BTC prefix from crypto codes for display
            if (value != null) {
                String code = value.toString();
                if (code.startsWith("BTC") && code.length() > 3) {
                    // Remove the "BTC" prefix for display
                    value = code.substring(3);
                }
            }

            return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
        }
    }

    /** Pick up/down/zero/default color for a possibly-null direction value. */
    private Color signColor(Double value, Color fallback) {
        if (value == null) return fallback;
        if (value > 0) return upColor;
        if (value < 0) return downColor;
        return zeroColor;
    }

    /** Look up the (sibling) column's value on the same row, returns null on any miss. */
    private Object siblingValue(JTable table, int row, String columnName) {
        if (!(table.getModel() instanceof DefaultTableModel)) return null;
        DefaultTableModel m = (DefaultTableModel) table.getModel();
        int idx = m.findColumn(columnName);
        if (idx < 0 || row < 0 || row >= m.getRowCount()) return null;
        return m.getValueAt(row, idx);
    }

    // Numeric (Current, Opening, Close, Low, High) — color tracks the row's Change% column.
    private class NumericCellRenderer extends StockerDefaultTableCellRender {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (shouldSkipColoring(table, isSelected, row)) return c;
            Object pct = siblingValue(table, row, percentColumn);
            setForeground(signColor(pct == null ? null : parsePercentage(pct.toString()), table.getForeground()));
            return c;
        }
    }

    // Change column — color follows the value's sign.
    private class ChangeCellRenderer extends StockerDefaultTableCellRender {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (shouldSkipColoring(table, isSelected, row)) return c;
            setForeground(signColor(value == null ? null : parseDouble(value), table.getForeground()));
            return c;
        }
    }

    // Change% column — parse the "%" suffix and color by sign.
    private class PercentCellRenderer extends StockerDefaultTableCellRender {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (shouldSkipColoring(table, isSelected, row)) return c;
            setForeground(signColor(value == null ? null : parsePercentage(value.toString()), table.getForeground()));
            return c;
        }
    }

    // Cost column — inverted: cost > current → down color (you're underwater on a position).
    private class CostCellRenderer extends StockerDefaultTableCellRender {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (shouldSkipColoring(table, isSelected, row)) return c;
            Double cost = value == null ? null : parseDouble(value);
            Object cur = siblingValue(table, row, currentColumn);
            Double curPrice = cur == null ? null : parseDouble(cur);
            if (cost == null || curPrice == null) {
                setForeground(table.getForeground());
            } else {
                // negate so positive ⇒ down color (cost above current = position is losing)
                setForeground(signColor(curPrice - cost, table.getForeground()));
            }
            return c;
        }
    }

    private class NetProfitCellRenderer extends StockerDefaultTableCellRender {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (shouldSkipColoring(table, isSelected, row)) return c;
            setForeground(signColor(parseDouble(value), table.getForeground()));
            return c;
        }
    }

    /**
     * Renderer for the Health column. The value is expected to be a single-character glyph
     * supplied by {@link com.vermouthx.stocker.listeners.StockerQuoteUpdateListener} based on
     * {@link com.vermouthx.stocker.finance.FinanceBridgeService}. Color reflects the status,
     * not the up/down direction.
     *   ● green   – position thesis is intact (drift_score 0 or watchlist trigger not hit)
     *   ● yellow  – near a trigger, or thesis drift 1–2
     *   ● red     – triggered (stop loss / invalidation) or thesis drift ≥ 3
     *   ● gray    – no finance/ report available for this symbol
     */
    private class HealthCellRenderer extends StockerDefaultTableCellRender {
        private final Color green = new JBColor(new Color(0x2E7D32), new Color(0x66BB6A));
        private final Color yellow = new JBColor(new Color(0xC78A00), new Color(0xFFCA28));
        private final Color red = new JBColor(new Color(0xC62828), new Color(0xEF5350));
        private final Color gray = JBColor.GRAY;

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            Component component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setHorizontalAlignment(DefaultTableCellRenderer.CENTER);
            if (isSelected) {
                return component;
            }
            if (value == null) {
                setForeground(gray);
                setText("●");
                setToolTipText(null);
                return component;
            }
            String s = value.toString();
            // value format is "<glyph>|<tooltip>" so we can carry status text without an extra column
            String glyph = s;
            String tip = null;
            int sep = s.indexOf('|');
            if (sep >= 0) {
                glyph = s.substring(0, sep);
                tip = s.substring(sep + 1);
            }
            setText(glyph);
            setToolTipText(tip);
            switch (glyph) {
                case "G":
                    setForeground(green);
                    setText("●");
                    break;
                case "Y":
                    setForeground(yellow);
                    setText("●");
                    break;
                case "R":
                    setForeground(red);
                    setText("●");
                    break;
                default:
                    setForeground(gray);
                    setText("●");
                    break;
            }
            return component;
        }
    }

    /**
     * Renderer for the DISTANCE column. Cell value format: "<level>|<text>|<tooltip>",
     * produced by {@link com.vermouthx.stocker.finance.FinanceDistanceAnnotator}.
     *
     * Level mapping:
     *   A (ALERT)  red background  → invalidation breached
     *   W (WARN)   amber background → inside trigger ±1.5% OR within +1.5% of invalidation
     *   I (INFO)   default bg, plain text → within ±5% of trigger
     *   N (NONE)   muted gray text → passive distance (>5% away)
     *
     * WARN/ALERT colors only apply when the corresponding setting is enabled — turning
     * `financeNotifyTriggers` / `financeNotifyEntryTiming` off downgrades all WARN/ALERT
     * to neutral display (still shows the text, just without urgent color).
     */
    private class DistanceCellRenderer extends StockerDefaultTableCellRender {
        private final Color alertBg = new JBColor(new Color(0xC62828), new Color(0xB71C1C));
        private final Color alertFg = new JBColor(Color.WHITE, Color.WHITE);
        private final Color warnBg  = new JBColor(new Color(0xFFF1B8), new Color(0x5A4B1A));
        private final Color warnFg  = new JBColor(new Color(0x6B4E00), new Color(0xFFD54F));
        private final Color infoFg  = new JBColor(new Color(0x37474F), new Color(0xCFD8DC));
        private final Color muted   = JBColor.GRAY;

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            Component component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setHorizontalAlignment(DefaultTableCellRenderer.CENTER);
            // Reset background — super-class may have set selection or zebra background
            if (!isSelected) {
                setBackground(table.getBackground());
                setOpaque(false);
            }
            if (value == null) {
                setText("");
                setToolTipText(null);
                return component;
            }
            String s = value.toString();
            String level = "N";
            String text = s;
            String tip = null;
            int sep1 = s.indexOf('|');
            if (sep1 >= 0) {
                level = s.substring(0, sep1);
                String rest = s.substring(sep1 + 1);
                int sep2 = rest.indexOf('|');
                if (sep2 >= 0) {
                    text = rest.substring(0, sep2);
                    tip = rest.substring(sep2 + 1);
                    if (tip.isEmpty()) tip = null;
                } else {
                    text = rest;
                }
            }
            setText(text);
            setToolTipText(tip);
            if (isSelected) return component;

            com.vermouthx.stocker.settings.StockerSetting setting = com.vermouthx.stocker.settings.StockerSetting.Companion.getInstance();
            // Determine if WARN/ALERT colors should be suppressed (user disabled both flags
            // → user explicitly opted out of urgent visuals; still show plain text).
            boolean suppressUrgent = !setting.getFinanceNotifyTriggers() && !setting.getFinanceNotifyEntryTiming();

            if (!suppressUrgent && "A".equals(level)) {
                setBackground(alertBg);
                setForeground(alertFg);
                setOpaque(true);
            } else if (!suppressUrgent && "W".equals(level)) {
                setBackground(warnBg);
                setForeground(warnFg);
                setOpaque(true);
            } else if ("I".equals(level)) {
                setForeground(infoFg);
            } else {
                setForeground(muted);
            }
            return component;
        }
    }

}
