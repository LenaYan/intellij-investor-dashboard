package com.vermouthx.stocker.listeners;

import com.vermouthx.stocker.entities.StockerQuote;
import com.vermouthx.stocker.finance.EntryTimingRecommendation;
import com.vermouthx.stocker.finance.FinanceBridgeService;
import com.vermouthx.stocker.finance.FinanceEventCalendar;
import com.vermouthx.stocker.finance.FinanceState;
import com.vermouthx.stocker.finance.WatchlistEntry;
import com.vermouthx.stocker.settings.StockerSetting;
import com.vermouthx.stocker.utils.StockerTableModelUtil;
import com.vermouthx.stocker.views.StockerTableView;

import javax.swing.table.DefaultTableModel;
import java.util.List;
import java.util.Set;

public class StockerQuoteUpdateListener implements StockerQuoteUpdateNotifier {
    private final StockerTableView myTableView;

    private static String formatCostPrice(Double costPrice) {
        return costPrice != null ? String.format("%.3f", costPrice) : "-";
    }

    private static Object formatHoldings(Integer holdings) {
        return holdings != null ? holdings : "-";
    }

    private static Object formatNetProfit(StockerQuote quote, Double costPrice, Integer holdings) {
        if (costPrice == null || holdings == null) {
            return "-";
        }
        return String.format("%.3f", (quote.getCurrent() - costPrice) * holdings);
    }

    /**
     * Prefix the display name with calendar-event emojis (📊 earnings, 🔓 unlock) and
     * entry-timing grade (🟢A+/🟢A/🟡B/🔴C) when this symbol carries those signals.
     *
     * Returns the original name unchanged when finance/ has nothing for this code.
     */
    private static String prefixNameWithEvents(String baseName, String code) {
        try {
            FinanceBridgeService bridge = FinanceBridgeService.getInstance();
            Set<FinanceEventCalendar.EventKind> events = bridge.snapshot().getEventsBySymbol()
                .getOrDefault(com.vermouthx.stocker.finance.FinanceSymbol.normalize(code),
                              java.util.Collections.emptySet());
            EntryTimingRecommendation entryRec = bridge.snapshot().getEntryTimingBySymbol()
                .get(com.vermouthx.stocker.finance.FinanceSymbol.normalize(code));
            String gradeGlyph = entryRec == null ? null : entryRec.getGradeGlyph();

            StringBuilder sb = new StringBuilder();
            if (gradeGlyph != null) sb.append(gradeGlyph).append(' ');
            if (events.contains(FinanceEventCalendar.EventKind.EARNINGS)) sb.append("📊");
            if (events.contains(FinanceEventCalendar.EventKind.UNLOCK))   sb.append("🔓");
            if (sb.length() == 0) return baseName;
            if (sb.charAt(sb.length() - 1) != ' ') sb.append(' ');
            sb.append(baseName);
            return sb.toString();
        } catch (Throwable t) {
            return baseName;
        }
    }

    /**
     * Encode the finance/ health badge as "<glyph>|<tooltip>" so the cell renderer can show
     * the dot and the explanation in a tooltip without needing a second column.
     * Returns null when the symbol is unknown to finance/ — the renderer will paint a gray dot.
     */
    private static String formatHealthBadge(String code) {
        try {
            FinanceBridgeService bridge = FinanceBridgeService.getInstance();
            FinanceState.Health h = bridge.healthOf(code);
            WatchlistEntry entry = bridge.watchlistEntry(code);
            EntryTimingRecommendation rec = bridge.snapshot().getEntryTimingBySymbol()
                .get(com.vermouthx.stocker.finance.FinanceSymbol.normalize(code));

            StringBuilder tip = new StringBuilder();
            if (entry != null) {
                if (entry.getName() != null) {
                    tip.append(entry.getName()).append(" (").append(entry.getSymbol()).append(")\n");
                }
                if (entry.getSector() != null) {
                    tip.append("行业: ").append(entry.getSector()).append("\n");
                }
                if (entry.getTargetZoneLow() != null && entry.getTargetZoneHigh() != null) {
                    tip.append("target: ¥").append(entry.getTargetZoneLow())
                       .append(" – ¥").append(entry.getTargetZoneHigh()).append("\n");
                }
                if (entry.getTrigger() != null) {
                    tip.append("trigger: ").append(entry.getTrigger()).append("\n");
                }
            }
            if (rec != null) {
                tip.append("──── entry-timing ────\n");
                if (rec.getGrade() != null) {
                    tip.append("grade: ").append(rec.getGrade());
                    if (rec.getEntryType() != null) tip.append(" · ").append(rec.getEntryType());
                    tip.append('\n');
                }
                if (rec.getTriggerPrice() != null) {
                    tip.append("trigger: ¥").append(String.format("%.2f", rec.getTriggerPrice())).append('\n');
                }
                if (rec.getInvalidationPrice() != null) {
                    tip.append("失效价: ¥").append(String.format("%.2f", rec.getInvalidationPrice())).append('\n');
                }
                if (rec.getFirstPositionPct() != null) {
                    tip.append("首仓: ").append(rec.getFirstPositionPct()).append("%");
                    if (rec.getAddSchedule() != null) tip.append(" · 加仓 ").append(rec.getAddSchedule());
                    tip.append('\n');
                }
                if (rec.getAlignedThread() != null) {
                    tip.append("主线: ").append(rec.getAlignedThread());
                    if (rec.getThreadPhase() != null) tip.append(" (").append(rec.getThreadPhase()).append(")");
                    tip.append('\n');
                }
            }
            String glyph;
            switch (h) {
                case GREEN:
                    glyph = "G";
                    if (tip.length() == 0) tip.append("持仓 thesis 良好");
                    break;
                case YELLOW:
                    glyph = "Y";
                    if (tip.length() == 0) tip.append("接近触发 / thesis 偏离 1-2");
                    else tip.insert(0, "状态: 关注（接近触发或 thesis 偏离 1-2）\n");
                    break;
                case RED:
                    glyph = "R";
                    if (tip.length() == 0) tip.append("触发止损 / thesis 偏离 ≥3");
                    else tip.insert(0, "状态: 警戒（已触发或 thesis 偏离 ≥3）\n");
                    break;
                default:
                    if (entry == null && rec == null) return null;
                    glyph = "-";
                    if (tip.length() == 0) tip.append("已收录至 finance/，暂无 position-risk-monitor / entry-timing 报告");
                    break;
            }
            return glyph + "|" + tip.toString().trim();
        } catch (Throwable t) {
            return null;
        }
    }

    public StockerQuoteUpdateListener(StockerTableView myTableView) {
        this.myTableView = myTableView;
    }

    @Override
    public void syncQuotes(List<StockerQuote> quotes, int size) {
        DefaultTableModel tableModel = myTableView.getTableModel();
        StockerSetting setting = StockerSetting.Companion.getInstance();
        
        quotes.forEach(quote -> {
            synchronized (myTableView.getTableModel()) {
                String displayName = prefixNameWithEvents(
                    setting.getDisplayName(quote.getCode(), quote.getName()),
                    quote.getCode());
                int rowIndex = StockerTableModelUtil.existAt(tableModel, quote.getCode());
                if (rowIndex != -1) {
                    // Update existing row - check each column
                    // Column 0: Code (doesn't change)
                    // Column 1: Name
                    if (!tableModel.getValueAt(rowIndex, 1).equals(displayName)) {
                        tableModel.setValueAt(displayName, rowIndex, 1);
                        tableModel.fireTableCellUpdated(rowIndex, 1);
                    }
                    // Column 2: Current
                    if (!tableModel.getValueAt(rowIndex, 2).equals(quote.getCurrent())) {
                        tableModel.setValueAt(quote.getCurrent(), rowIndex, 2);
                        tableModel.fireTableCellUpdated(rowIndex, 2);
                    }
                    // Column 3: Opening
                    if (!tableModel.getValueAt(rowIndex, 3).equals(quote.getOpening())) {
                        tableModel.setValueAt(quote.getOpening(), rowIndex, 3);
                        tableModel.fireTableCellUpdated(rowIndex, 3);
                    }
                    // Column 4: Close
                    if (!tableModel.getValueAt(rowIndex, 4).equals(quote.getClose())) {
                        tableModel.setValueAt(quote.getClose(), rowIndex, 4);
                        tableModel.fireTableCellUpdated(rowIndex, 4);
                    }
                    // Column 5: Low
                    if (!tableModel.getValueAt(rowIndex, 5).equals(quote.getLow())) {
                        tableModel.setValueAt(quote.getLow(), rowIndex, 5);
                        tableModel.fireTableCellUpdated(rowIndex, 5);
                    }
                    // Column 6: High
                    if (!tableModel.getValueAt(rowIndex, 6).equals(quote.getHigh())) {
                        tableModel.setValueAt(quote.getHigh(), rowIndex, 6);
                        tableModel.fireTableCellUpdated(rowIndex, 6);
                    }
                    // Column 7: Change
                    if (!tableModel.getValueAt(rowIndex, 7).equals(quote.getChange())) {
                        tableModel.setValueAt(quote.getChange(), rowIndex, 7);
                        tableModel.fireTableCellUpdated(rowIndex, 7);
                    }
                    // Column 8: Change%
                    if (!tableModel.getValueAt(rowIndex, 8).equals(quote.getPercentage())) {
                        tableModel.setValueAt(quote.getPercentage() + "%", rowIndex, 8);
                        tableModel.fireTableCellUpdated(rowIndex, 8);
                    }
                    // Column 9: Cost Price (user-set, read from settings)
                    Double costPrice = setting.getCostPrice(quote.getCode());
                    String costPriceStr = formatCostPrice(costPrice);
                    if (!costPriceStr.equals(tableModel.getValueAt(rowIndex, 9))) {
                        tableModel.setValueAt(costPriceStr, rowIndex, 9);
                        tableModel.fireTableCellUpdated(rowIndex, 9);
                    }
                    // Column 10: Holdings (user-set, read from settings)
                    Integer holdings = setting.getHoldings(quote.getCode());
                    Object holdingsVal = formatHoldings(holdings);
                    if (!holdingsVal.equals(tableModel.getValueAt(rowIndex, 10))) {
                        tableModel.setValueAt(holdingsVal, rowIndex, 10);
                        tableModel.fireTableCellUpdated(rowIndex, 10);
                    }
                    // Column 11: Net Profit (derived from current, cost price, and holdings)
                    Object netProfitVal = formatNetProfit(quote, costPrice, holdings);
                    if (!netProfitVal.equals(tableModel.getValueAt(rowIndex, 11))) {
                        tableModel.setValueAt(netProfitVal, rowIndex, 11);
                        tableModel.fireTableCellUpdated(rowIndex, 11);
                    }
                    // Column 13: Health badge (column 12 is sparkline data, set elsewhere)
                    if (tableModel.getColumnCount() > 13) {
                        String healthVal = formatHealthBadge(quote.getCode());
                        Object existing = tableModel.getValueAt(rowIndex, 13);
                        if (healthVal == null) {
                            if (existing != null) {
                                tableModel.setValueAt(null, rowIndex, 13);
                                tableModel.fireTableCellUpdated(rowIndex, 13);
                            }
                        } else if (!healthVal.equals(existing)) {
                            tableModel.setValueAt(healthVal, rowIndex, 13);
                            tableModel.fireTableCellUpdated(rowIndex, 13);
                        }
                    }
                } else {
                    if (quotes.size() <= size) {
                        Double costPrice = setting.getCostPrice(quote.getCode());
                        Integer holdings = setting.getHoldings(quote.getCode());
                        tableModel.addRow(new Object[]{
                            quote.getCode(),
                            displayName,
                            quote.getCurrent(),
                            quote.getOpening(),
                            quote.getClose(),
                            quote.getLow(),
                            quote.getHigh(),
                            quote.getChange(),
                            quote.getPercentage() + "%",
                            formatCostPrice(costPrice),
                            formatHoldings(holdings),
                            formatNetProfit(quote, costPrice, holdings),
                            null, // sparkline data populated by intraday fetch
                            formatHealthBadge(quote.getCode()) // finance/ bridge health
                        });
                        // Clear sort state when new rows are added
                        myTableView.clearSortState();
                    }
                }
            }
        });
    }

    @Override
    public void syncIndices(List<StockerQuote> indices) {
        synchronized (myTableView) {
            myTableView.syncIndices(indices);
        }
    }

}
