package com.hamhuo.tplanner.designsystem

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.TextView
import com.hamhuo.tplanner.designsystem.TPlannerLightTokens.Semantic

enum class TPlannerSyncFeedbackTone {
    ACCENT,
    SUCCESS,
    ERROR,
}

/**
 * Compact top-of-screen sync feedback shared by the phone and Wear launchers.
 *
 * Deliberately extends the framework [TextView] rather than AppCompatTextView:
 * this design-system primitive sets its own colors/background/typeface and uses no
 * AppCompat theming, and the :wear module (which also compiles shared sources)
 * does not depend on androidx.appcompat.
 */
@SuppressLint("AppCompatCustomView")
class TPlannerSyncFeedbackView(context: Context) : TextView(context) {
    private val hideFeedback = Runnable {
        animate()
            .alpha(0f)
            .setDuration(FADE_OUT_DURATION_MILLIS)
            .withEndAction { visibility = INVISIBLE }
            .start()
    }

    init {
        gravity = Gravity.CENTER
        letterSpacing = 0f
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        updateMetrics()
        setPadding(dp(HORIZONTAL_PADDING_DP), dp(VERTICAL_PADDING_DP), dp(HORIZONTAL_PADDING_DP), dp(VERTICAL_PADDING_DP))
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(Semantic.Radius.Card.toInt()).toFloat()
            setColor(Semantic.Color.Raised)
            setStroke(dp(1), Semantic.Color.BorderSubtle)
        }
        alpha = 0f
        visibility = INVISIBLE
        elevation = dp(ELEVATION_DP).toFloat()
        isClickable = false
        isFocusable = false
    }

    fun show(message: String, tone: TPlannerSyncFeedbackTone, autoHide: Boolean) {
        updateMetrics()
        removeCallbacks(hideFeedback)
        animate().cancel()
        text = message
        setTextColor(
            when (tone) {
                TPlannerSyncFeedbackTone.ACCENT -> Semantic.Color.AccentText
                TPlannerSyncFeedbackTone.SUCCESS -> Semantic.Color.Success
                TPlannerSyncFeedbackTone.ERROR -> Semantic.Color.Error
            },
        )
        contentDescription = message
        visibility = VISIBLE
        alpha = 1f
        announceForAccessibility(message)
        if (autoHide) postDelayed(hideFeedback, RESULT_VISIBLE_DURATION_MILLIS)
    }

    fun hide() {
        removeCallbacks(hideFeedback)
        animate().cancel()
        alpha = 0f
        visibility = INVISIBLE
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hideFeedback)
        animate().cancel()
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun updateMetrics() {
        val watch = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_WATCH
        textSize = if (watch) TPlannerLightTokens.Platform.Wear.Typography.Meta.FontSize
            else TPlannerLightTokens.Platform.Phone.Typography.Meta.FontSize
        val screenWidth = resources.configuration.screenWidthDp
        // The Wear host places round-screen feedback below the upper curved edge.
        maxWidth = dp(if (watch && resources.configuration.isScreenRound) {
            (screenWidth * 0.7f).toInt()
        } else (screenWidth - 24).coerceIn(120, 360))
    }

    private companion object {
        const val HORIZONTAL_PADDING_DP = 12
        const val VERTICAL_PADDING_DP = 6
        const val ELEVATION_DP = 8
        const val RESULT_VISIBLE_DURATION_MILLIS = 1_600L
        const val FADE_OUT_DURATION_MILLIS = TPlannerLightTokens.Semantic.Motion.Standard
    }
}
