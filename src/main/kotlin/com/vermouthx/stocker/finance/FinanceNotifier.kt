package com.vermouthx.stocker.finance

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Throttled notifier — reuses the existing "Stocker" notification group declared in plugin.xml.
 *
 * Throttle key is (symbol, signalKind, day). Once a signal of a given kind has fired for a
 * symbol in the same calendar day, additional same-kind fires are suppressed until the
 * configured cool-down expires. This keeps the IDE from being spammed when a stock
 * oscillates around a threshold during the trading session.
 */
internal class FinanceNotifier(
    private val project: Project,
    private val coolDownMillis: Long = 10 * 60 * 1000L,
) {
    private val lastFiredAt = ConcurrentHashMap<String, Long>()
    private val groupId = "Stocker"

    enum class Kind(val tagZh: String, val tagEn: String, val type: NotificationType) {
        // Note: ANOMALY_UP/DOWN, STRONG_ANOMALY_UP/DOWN, LIMIT_UP/DOWN — the ±%/涨跌停
        // popups — were removed; the DISTANCE column carries that signal inline now.
        // TRIGGER_HIT / INVALIDATION_HIT / ENTRY_TRIGGER / ENTRY_INVALIDATION were
        // deprecated earlier when the DISTANCE column shipped.
        THREAD_BRANCH_FLIP("主线分支切换", "Scenario branch flip", NotificationType.WARNING),
        THREAD_OUT_OF_SCOPE("主线超预案", "Scenario out of scope", NotificationType.ERROR),
    }

    fun fire(symbol: String, name: String?, kind: Kind, currentPrice: Double, percent: Double, detail: String?) {
        val today = java.time.LocalDate.now().toString()
        val tkey = "$symbol|${kind.name}|$today"
        val now = System.currentTimeMillis()
        val prev = lastFiredAt[tkey]
        if (prev != null && now - prev < coolDownMillis) return
        lastFiredAt[tkey] = now

        val zh = isChinese()
        val displayName = name ?: symbol
        val pctStr = String.format(Locale.US, "%.2f%%", percent)
        val priceStr = String.format(Locale.US, "%.3f", currentPrice)
        val title = if (zh) "[${kind.tagZh}] $displayName ($symbol)"
        else "[${kind.tagEn}] $displayName ($symbol)"
        val body = buildString {
            append(if (zh) "现价 " else "Price ")
            append(priceStr)
            append("  ")
            append(if (zh) "涨跌幅 " else "Δ% ")
            append(pctStr)
            if (!detail.isNullOrBlank()) {
                append("\n")
                append(detail)
            }
        }

        val group = NotificationGroupManager.getInstance().getNotificationGroup(groupId) ?: return
        val n = group.createNotification(title, body, kind.type)
        n.notify(project)
    }

    fun reset() {
        lastFiredAt.clear()
    }

    private fun isChinese(): Boolean {
        return Locale.getDefault().language == "zh"
    }
}
