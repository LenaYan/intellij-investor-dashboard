package com.vermouthx.stocker.notifications

import com.intellij.ide.BrowserUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.vermouthx.stocker.StockerMeta
import com.vermouthx.stocker.settings.StockerSetting
import org.intellij.lang.annotations.Language
import java.util.*

object StockerNotification {

    private object Colors {
        const val PRIMARY = "#4CAF50"
        const val BACKGROUND = "rgba(33, 150, 243, 0.08)"
        const val BORDER = "#2196F3"
    }

    private object Styles {
        const val CONTAINER = "margin: 8px 0; line-height: 1.4;"
        const val HEADING = "margin: 0 0 8px 0; color: ${Colors.PRIMARY}; font-size: 14px; font-weight: 600;"
        const val PARAGRAPH = "margin: 0 0 12px 0; font-size: 13px;"
        const val SMALL_TEXT = "margin: 12px 0 0 0; font-size: 12px; font-style: italic; opacity: 0.7;"
        const val LIST_ITEM = "margin: 6px 0;"
        const val SUB_LIST = "margin: 4px 0 0 0; padding-left: 18px; font-size: 12px;"
        const val INFO_BOX = "background: ${Colors.BACKGROUND}; border-left: 3px solid ${Colors.BORDER}; padding: 10px 12px; margin: 12px 0; border-radius: 3px;"
    }

    private val version get() = StockerMeta.currentVersion

    private fun isChinese(): Boolean {
        val override = try { StockerSetting.instance.languageOverride } catch (_: Exception) { "" }
        if (override == "zh_CN") return true
        if (override == "en") return false
        return Locale.getDefault().language == "zh"
    }

    @Language("HTML")
    private fun buildReleaseNote(): String {
        val v = version
        return if (isChinese()) """
            <div style="${Styles.CONTAINER}">
                <p style="${Styles.PARAGRAPH}">🎉 <strong>欢迎使用 Stocker v${v}！本次更新内容：</strong></p>
                <h4 style="${Styles.HEADING}">🐛 v${v} 错误修复</h4>
                <ul style="margin: 0; padding-left: 18px;">
                    <li style="${Styles.LIST_ITEM}">🐛 修复 <strong>设置 → 可见列</strong> 面板缺失"健康度"和"距阈值"复选框，导致 1.21.0 用户无法从 UI 启用新列的问题</li>
                </ul>
                <h4 style="${Styles.HEADING}">✨ v1.21 新功能回顾</h4>
                <ul style="margin: 0; padding-left: 18px;">
                    <li style="${Styles.LIST_ITEM}">✨ <strong>"距阈值"列</strong>：实时显示当前价距 finance/ watchlist 与 entry-timing trigger / invalidation 价位的距离；进入触发区 ±1.5% → 琥珀色背景；跌破失效价 → 红色背景</li>
                    <li style="${Styles.LIST_ITEM}">🔕 watchlist trigger / invalidation 命中、entry-timing 买点命中不再弹气泡通知（市场异动 ±5% / ±7% 和涨跌停通知保持不变）</li>
                </ul>
                <div style="${Styles.INFO_BOX}">
                    <p style="margin: 0; font-size: 12px;">💡 <strong>说明：</strong>升级后到 <strong>设置 → 工具 → Stocker</strong> 的可见列里勾选"距阈值"（及"健康度"）即可启用。</p>
                </div>
                <p style="${Styles.SMALL_TEXT}">💖 如果您觉得这个插件有帮助，请考虑点击下方的 <strong>Donate</strong> 按钮以支持开发。谢谢！📈</p>
            </div>
        """.trimIndent() else """
            <div style="${Styles.CONTAINER}">
                <p style="${Styles.PARAGRAPH}">🎉 <strong>Welcome to Stocker v${v}! Here's what's new in this release:</strong></p>
                <h4 style="${Styles.HEADING}">🐛 Bug Fixes in v${v}</h4>
                <ul style="margin: 0; padding-left: 18px;">
                    <li style="${Styles.LIST_ITEM}">🐛 Fixed <strong>Settings → Visible columns</strong> panel missing the Health and Trigger Distance checkboxes, so 1.21.0 users could not enable the new columns from the UI</li>
                </ul>
                <h4 style="${Styles.HEADING}">✨ v1.21 Feature Recap</h4>
                <ul style="margin: 0; padding-left: 18px;">
                    <li style="${Styles.LIST_ITEM}">✨ <strong>Trigger Distance column</strong>: live distance between current price and finance/ watchlist or entry-timing trigger / invalidation prices; amber bg inside trigger ±1.5%; red bg when invalidation is breached</li>
                    <li style="${Styles.LIST_ITEM}">🔕 Watchlist trigger / invalidation and entry-timing buy-point hits no longer surface as popups (market anomaly ±5% / ±7% and A-share limit-up / limit-down notifications still fire as before)</li>
                </ul>
                <div style="${Styles.INFO_BOX}">
                    <p style="margin: 0; font-size: 12px;">💡 <strong>Tip:</strong> After upgrade, enable the column under <strong>Settings → Tools → Stocker</strong> → Visible columns → "Trigger Dist." (and "Health").</p>
                </div>
                <p style="${Styles.SMALL_TEXT}">💖 If you find this plugin helpful, please consider clicking the <strong>Donate</strong> button below to support its development. Thank you! 📈</p>
            </div>
        """.trimIndent()
    }

    @Language("HTML")
    private fun buildWelcomeMessage(): String {
        return if (isChinese()) """
            <div style="${Styles.CONTAINER}">
                <p style="${Styles.PARAGRAPH}">🎉 <strong>欢迎使用 Stocker！</strong>您的投资仪表板已安装完成，可以开始跟踪您喜爱的股票了。</p>
                <div style="${Styles.INFO_BOX}">
                    <p style="margin: 0 0 8px 0; font-size: 12px;">💡 <strong>快速设置：</strong></p>
                    <ul style="margin: 0; padding-left: 16px; font-size: 12px;">
                        <li style="margin: 4px 0;">从左侧面板打开 <strong>Stocker</strong> 工具窗口</li>
                        <li style="margin: 4px 0;">点击<strong>添加自选股</strong>来搜索和添加股票</li>
                        <li style="margin: 4px 0;">在<strong>设置 → 工具 → Stocker</strong> 中配置选项</li>
                        <li style="margin: 4px 0;">开始实时跟踪您的投资！</li>
                    </ul>
                </div>
                <p style="${Styles.SMALL_TEXT}">💖 如果您觉得这个插件有帮助，请考虑点击下方的 <strong>Donate</strong> 按钮以支持开发。谢谢！📊</p>
            </div>
        """.trimIndent() else """
            <div style="${Styles.CONTAINER}">
                <p style="${Styles.PARAGRAPH}">🎉 <strong>Welcome to Stocker!</strong> Your investment dashboard is now installed and ready to track your favorite stocks.</p>
                <div style="${Styles.INFO_BOX}">
                    <p style="margin: 0 0 8px 0; font-size: 12px;">💡 <strong>Quick Setup:</strong></p>
                    <ul style="margin: 0; padding-left: 16px; font-size: 12px;">
                        <li style="margin: 4px 0;">Open the <strong>Stocker</strong> tool window from the left panel</li>
                        <li style="margin: 4px 0;">Click <strong>Add Favorite Stocks</strong> to search and add stocks</li>
                        <li style="margin: 4px 0;">Configure settings at <strong>Settings → Tools → Stocker</strong></li>
                        <li style="margin: 4px 0;">Start tracking your investments in real-time!</li>
                    </ul>
                </div>
                <p style="${Styles.SMALL_TEXT}">💖 If you find this plugin helpful, please consider clicking the <strong>Donate</strong> button below to support its development. Thank you! 📊</p>
            </div>
        """.trimIndent()
    }

    private const val NOTIFICATION_GROUP_ID = "Stocker"

    @JvmField
    val notificationIcon = IconLoader.getIcon("/icons/logo.png", javaClass)

    private const val GITHUB_LINK = "https://github.com/WhiteVermouth/intellij-investor-dashboard"
    private const val DONATE_LINK = "https://www.buymeacoffee.com/nszihan"

    fun notifyReleaseNote(project: Project) {
        val title = if (isChinese()) "Stocker v${version} - 版本说明" else "Stocker v${version} - Release Notes"
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(title, buildReleaseNote(), NotificationType.INFORMATION)
        addNotificationActions(notification)
        notification.icon = notificationIcon
        notification.notify(project)
    }

    fun notifyWelcome(project: Project) {
        val title = if (isChinese()) "Stocker 安装成功" else "Stocker Successfully Installed"
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(title, buildWelcomeMessage(), NotificationType.INFORMATION)
        addNotificationActions(notification)
        notification.icon = notificationIcon
        notification.notify(project)
    }

    private fun addNotificationActions(notification: Notification) {
        val github = NotificationAction.createSimple("📖 GitHub") {
            BrowserUtil.browse(GITHUB_LINK)
        }
        val actionDonate = NotificationAction.createSimple("☕ Donate") {
            BrowserUtil.browse(DONATE_LINK)
        }
        notification.addAction(github)
        notification.addAction(actionDonate)
    }
}
