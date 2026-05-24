package com.vermouthx.stocker.listeners;

import com.vermouthx.stocker.entities.StockerQuote;
import com.vermouthx.stocker.enums.StockerTableColumn;
import com.vermouthx.stocker.finance.EntryTimingRecommendation;
import com.vermouthx.stocker.finance.FinanceBridgeService;
import com.vermouthx.stocker.finance.FinanceDistanceAnnotator;
import com.vermouthx.stocker.finance.FinanceEventCalendar;
import com.vermouthx.stocker.finance.FinanceState;
import com.vermouthx.stocker.finance.FinanceSymbol;
import com.vermouthx.stocker.finance.WatchlistEntry;
import com.vermouthx.stocker.settings.StockerSetting;
import com.vermouthx.stocker.utils.StockerNumberFormat;
import com.vermouthx.stocker.utils.StockerTableModelUtil;
import com.vermouthx.stocker.views.StockerTableView;

import javax.swing.table.DefaultTableModel;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class StockerQuoteUpdateListener implements StockerQuoteUpdateNotifier {
    private final StockerTableView myTableView;

    public StockerQuoteUpdateListener(StockerTableView myTableView) {
        this.myTableView = myTableView;
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
            String key = FinanceSymbol.normalize(code);
            Set<FinanceEventCalendar.EventKind> events = bridge.snapshot().getEventsBySymbol()
                .getOrDefault(key, Collections.emptySet());
            EntryTimingRecommendation entryRec = bridge.snapshot().getEntryTimingBySymbol().get(key);
            String gradeGlyph = entryRec == null ? null : entryRec.getGradeGlyph();

            StringBuilder sb = new StringBuilder();
            if (gradeGlyph != null) sb.append(gradeGlyph).append(' ');
            if (events.contains(FinanceEventCalendar.EventKind.EARNINGS)) sb.append("📊");
            if (events.contains(FinanceEventCalendar.EventKind.UNLOCK))   sb.append("🔓");
            if (sb.length() == 0) return baseName;
            if (sb.charAt(sb.length() - 1) != ' ') sb.append(' ');
            sb.append(baseName);
            return sb.toString();
        } catch (Exception t) {
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
                .get(FinanceSymbol.normalize(code));

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
        } catch (Exception t) {
            return null;
        }
    }

    @Override
    public void syncQuotes(List<StockerQuote> quotes, int size) {
        DefaultTableModel model = myTableView.getTableModel();
        StockerSetting setting = StockerSetting.Companion.getInstance();

        // Resolve column indices once — they don't change inside this batch.
        int nameCol      = StockerTableModelUtil.colOf(model, StockerTableColumn.NAME);
        int currentCol   = StockerTableModelUtil.colOf(model, StockerTableColumn.CURRENT);
        int openingCol   = StockerTableModelUtil.colOf(model, StockerTableColumn.OPENING);
        int closeCol     = StockerTableModelUtil.colOf(model, StockerTableColumn.CLOSE);
        int lowCol       = StockerTableModelUtil.colOf(model, StockerTableColumn.LOW);
        int highCol      = StockerTableModelUtil.colOf(model, StockerTableColumn.HIGH);
        int changeCol    = StockerTableModelUtil.colOf(model, StockerTableColumn.CHANGE);
        int percentCol   = StockerTableModelUtil.colOf(model, StockerTableColumn.CHANGE_PERCENT);
        int costPriceCol = StockerTableModelUtil.colOf(model, StockerTableColumn.COST_PRICE);
        int holdingsCol  = StockerTableModelUtil.colOf(model, StockerTableColumn.HOLDINGS);
        int netProfitCol = StockerTableModelUtil.colOf(model, StockerTableColumn.NET_PROFIT);
        int healthCol    = StockerTableModelUtil.colOf(model, StockerTableColumn.HEALTH);
        int distanceCol  = StockerTableModelUtil.colOf(model, StockerTableColumn.DISTANCE);

        // Hold the lock for the whole batch — previously each quote re-acquired it.
        synchronized (model) {
            for (StockerQuote quote : quotes) {
                String code = quote.getCode();
                String displayName = prefixNameWithEvents(
                    setting.getDisplayName(code, quote.getName()), code);
                Double costPrice = setting.getCostPrice(code);
                Integer holdings = setting.getHoldings(code);

                int rowIndex = StockerTableModelUtil.existAt(model, code);
                if (rowIndex != -1) {
                    StockerTableModelUtil.setIfChanged(model, rowIndex, nameCol, displayName);
                    StockerTableModelUtil.setIfChanged(model, rowIndex, currentCol, quote.getCurrent());
                    StockerTableModelUtil.setIfChanged(model, rowIndex, openingCol, quote.getOpening());
                    StockerTableModelUtil.setIfChanged(model, rowIndex, closeCol, quote.getClose());
                    StockerTableModelUtil.setIfChanged(model, rowIndex, lowCol, quote.getLow());
                    StockerTableModelUtil.setIfChanged(model, rowIndex, highCol, quote.getHigh());
                    StockerTableModelUtil.setIfChanged(model, rowIndex, changeCol, quote.getChange());
                    StockerTableModelUtil.setIfChanged(model, rowIndex, percentCol, quote.getPercentage() + "%");
                    StockerTableModelUtil.setIfChanged(model, rowIndex, costPriceCol, StockerNumberFormat.formatPrice(costPrice));
                    StockerTableModelUtil.setIfChanged(model, rowIndex, holdingsCol, StockerNumberFormat.formatHoldings(holdings));
                    StockerTableModelUtil.setIfChanged(model, rowIndex, netProfitCol,
                        StockerNumberFormat.formatNetProfit(quote.getCurrent(), costPrice, holdings));
                    StockerTableModelUtil.setIfChanged(model, rowIndex, healthCol, formatHealthBadge(code));
                    StockerTableModelUtil.setIfChanged(model, rowIndex, distanceCol,
                        FinanceDistanceAnnotator.INSTANCE.encode(
                            FinanceDistanceAnnotator.INSTANCE.annotate(code, quote.getCurrent())));
                } else if (quotes.size() <= size) {
                    Object[] row = buildRow(quote, displayName, costPrice, holdings, model.getColumnCount());
                    model.addRow(row);
                    myTableView.clearSortState();
                }
            }
        }
    }

    /**
     * Build a new row in the order declared by the model's column identifiers, so the row
     * is correct even if the user has hidden/reordered columns.
     */
    private static Object[] buildRow(StockerQuote quote, String displayName,
                                     Double costPrice, Integer holdings, int columnCount) {
        Object[] row = new Object[columnCount];
        // The model is initialized with all columns present (StockerTableView.initTable),
        // so we can assume canonical indices here. If a column is later removed from the
        // model entirely, the corresponding cell stays null.
        row[0] = quote.getCode();
        row[1] = displayName;
        row[2] = quote.getCurrent();
        row[3] = quote.getOpening();
        row[4] = quote.getClose();
        row[5] = quote.getLow();
        row[6] = quote.getHigh();
        row[7] = quote.getChange();
        row[8] = quote.getPercentage() + "%";
        row[9] = StockerNumberFormat.formatPrice(costPrice);
        row[10] = StockerNumberFormat.formatHoldings(holdings);
        row[11] = StockerNumberFormat.formatNetProfit(quote.getCurrent(), costPrice, holdings);
        // row[12] sparkline data populated by intraday fetch
        if (columnCount > 13) row[13] = formatHealthBadge(quote.getCode());
        if (columnCount > 14) {
            row[14] = FinanceDistanceAnnotator.INSTANCE.encode(
                FinanceDistanceAnnotator.INSTANCE.annotate(quote.getCode(), quote.getCurrent()));
        }
        return row;
    }

    @Override
    public void syncIndices(List<StockerQuote> indices) {
        synchronized (myTableView) {
            myTableView.syncIndices(indices);
        }
    }

}
