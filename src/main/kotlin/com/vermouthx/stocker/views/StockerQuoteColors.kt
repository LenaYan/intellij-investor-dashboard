package com.vermouthx.stocker.views

import com.intellij.ui.JBColor
import com.vermouthx.stocker.enums.StockerQuoteColorPattern
import com.vermouthx.stocker.settings.StockerSetting
import java.awt.Color

/**
 * Mutable up/down/flat color state derived from the user's
 * [StockerQuoteColorPattern]. Shared by the cell renderers and the index panel
 * so a settings change recolors everything through one `syncFromSettings()` call.
 */
class StockerQuoteColors {

    var up: Color = JBColor.foreground()
        private set
    var down: Color = JBColor.foreground()
        private set
    var zero: Color = JBColor.foreground()
        private set

    /** Whether sparklines should paint gains in red (CN convention). */
    var redUp: Boolean = true
        private set

    fun syncFromSettings() {
        when (StockerSetting.instance.quoteColorPattern) {
            StockerQuoteColorPattern.RED_UP_GREEN_DOWN -> {
                up = JBColor.RED
                down = JBColor.GREEN
                zero = JBColor.GRAY
                redUp = true
            }
            StockerQuoteColorPattern.GREEN_UP_RED_DOWN -> {
                up = JBColor.GREEN
                down = JBColor.RED
                zero = JBColor.GRAY
                redUp = false
            }
            else -> {
                up = JBColor.foreground()
                down = JBColor.foreground()
                zero = JBColor.foreground()
                redUp = true
            }
        }
    }

    /** Pick up/down/zero/default color for a possibly-null direction value. */
    fun signColor(value: Double?, fallback: Color): Color = when {
        value == null -> fallback
        value > 0 -> up
        value < 0 -> down
        else -> zero
    }
}
