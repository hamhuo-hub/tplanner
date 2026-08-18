package com.hamhuo.tplanner
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalTime

/** Third destination: compact, fixed full-screen time selector for round watches. */
class CreateTimeActivity : WearPageActivity() {
    private var destinationOpened = false
    private var selectedHour = true
    private var hour = 0
    private var minute = 0
    private lateinit var hourText: TextView
    private lateinit var minuteText: TextView
    private lateinit var colonText: TextView
    private lateinit var hourButton: TextView
    private lateinit var minuteButton: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.task_create_time_page)

        val route = intent.creationRouteOrNull()?.takeIf { !it.type.isNullOrBlank() } ?: run {
            finish()
            return
        }

        val now = LocalTime.now(CREATION_ZONE)
        hour = now.hour
        minute = now.minute

        // This page deliberately does NOT use creationScrollPage().
        // Everything is laid out inside one fixed screen so the Next button is always visible.
        val root = FrameLayout(this).apply {
            setBackgroundColor(WEAR_BG)
        }

        val heading = creationHeading(getString(R.string.task_create_time_page)).apply {
            textSize = 20f
            setPadding(dp(8), 0, dp(8), 0)
        }
        root.addView(
            heading,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                leftMargin = dp(34)
                rightMargin = dp(34)
                topMargin = dp(9)
            },
        )

        // One compact centered block. Hour / colon / minute use fixed column widths,
        // so the visual centre does not move with proportional glyph widths.
        val timeBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        hourText = timeDigit(hour).apply {
            setOnClickListener {
                if (!selectedHour) {
                    selectedHour = true
                    renderSelection()
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
        }
        colonText = timeColon()
        minuteText = timeDigit(minute).apply {
            setOnClickListener {
                if (selectedHour) {
                    selectedHour = false
                    renderSelection()
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
        }

        timeRow.addView(hourText, LinearLayout.LayoutParams(dp(70), dp(52)))
        timeRow.addView(colonText, LinearLayout.LayoutParams(dp(16), dp(52)))
        timeRow.addView(minuteText, LinearLayout.LayoutParams(dp(70), dp(52)))
        timeBlock.addView(timeRow)

        // Labels use exactly the same 70 / 16 / 70 geometry as the digits above.
        val selectRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        hourButton = fieldToggle("HOUR") {
            if (!selectedHour) {
                selectedHour = true
                renderSelection()
            }
        }
        minuteButton = fieldToggle("MIN") {
            if (selectedHour) {
                selectedHour = false
                renderSelection()
            }
        }

        selectRow.addView(hourButton, LinearLayout.LayoutParams(dp(70), dp(22)))
        selectRow.addView(View(this), LinearLayout.LayoutParams(dp(16), dp(22)))
        selectRow.addView(minuteButton, LinearLayout.LayoutParams(dp(70), dp(22)))
        timeBlock.addView(
            selectRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(1)
            },
        )

        // Symmetric stepper pair. No trailing margin on the final button.
        val stepRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        stepRow.addView(
            stepperButton("-") { adjust(-1) },
            LinearLayout.LayoutParams(dp(62), dp(40)),
        )
        stepRow.addView(View(this), LinearLayout.LayoutParams(dp(12), dp(40)))
        stepRow.addView(
            stepperButton("+") { adjust(+1) },
            LinearLayout.LayoutParams(dp(62), dp(40)),
        )
        timeBlock.addView(
            stepRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(6)
            },
        )

        root.addView(
            timeBlock,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(41)
            },
        )

        // Fixed bottom action. It never participates in scrolling and stays inside
        // the narrow safe chord near the bottom of a round display.
        val confirmButton = creationActionRow(R.string.task_create_next) {
            if (destinationOpened) return@creationActionRow
            destinationOpened = true
            @Suppress("DEPRECATION")
            startActivityForResult(
                CreateDateActivity.createIntent(
                    this@CreateTimeActivity,
                    route.copy(hour = hour, minute = minute),
                ),
                REQUEST_CREATION_NEXT,
            )
        }.apply {
            setTextColor(WEAR_BG)
            textSize = 15f
            setPadding(0, 0, 0, 0)
            background = creationRippleRounded(
                CREATION_ACCENT,
                WEAR_GOLD_PRESSED,
                dp(20).toFloat(),
            )
        }

        root.addView(
            confirmButton,
            FrameLayout.LayoutParams(dp(132), dp(40)).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(9)
            },
        )

        setContentView(root)
        renderSelection()
    }

    // ── rotary ────────────────────────────────────────────────────────
    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        val crownAxis = event?.rotaryScrollAxisOrNull()
            ?: return super.onGenericMotionEvent(event)
        adjust(if (crownAxis > 0f) 1 else -1)
        (if (selectedHour) hourText else minuteText)
            .performCrownItemFocusFeedback(event)
        return true
    }

    override fun onResume() {
        super.onResume()
        destinationOpened = false
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        propagateCreationResult(requestCode, resultCode)
    }

    private fun adjust(delta: Int) {
        if (selectedHour) {
            hour = ((hour + delta) % 24 + 24) % 24
        } else {
            minute = ((minute + delta) % 60 + 60) % 60
        }
        renderTime()
    }

    private fun renderTime() {
        hourText.text = "%02d".format(hour)
        minuteText.text = "%02d".format(minute)
    }

    private fun renderSelection() {
        hourText.setTextColor(if (selectedHour) CREATION_ACCENT else CREATION_PRIMARY)
        minuteText.setTextColor(if (selectedHour) CREATION_PRIMARY else CREATION_ACCENT)

        // Keep the separator neutral; colouring it with the selected side makes
        // the whole time readout look optically off-centre.
        colonText.setTextColor(CREATION_DIM)

        hourButton.setTextColor(if (selectedHour) CREATION_PRIMARY else CREATION_DIM)
        minuteButton.setTextColor(if (selectedHour) CREATION_DIM else CREATION_PRIMARY)
        hourButton.alpha = 1f
        minuteButton.alpha = 1f
    }

    private fun timeDigit(value: Int): TextView = TextView(this).apply {
        text = "%02d".format(value)
        setTextColor(CREATION_PRIMARY)
        textSize = 42f
        typeface = CREATION_BOLD
        includeFontPadding = false
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
    }

    private fun timeColon(): TextView = TextView(this).apply {
        text = ":"
        setTextColor(CREATION_DIM)
        textSize = 34f
        typeface = CREATION_BOLD
        includeFontPadding = false
        gravity = Gravity.CENTER
    }

    private fun fieldToggle(
        label: String,
        onClick: () -> Unit,
    ): TextView = TextView(this).apply {
        text = label
        setTextColor(CREATION_PRIMARY)
        textSize = 11.5f
        typeface = CREATION_MEDIUM
        includeFontPadding = false
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onClick()
        }
    }

    private fun stepperButton(
        label: String,
        onClick: () -> Unit,
    ): TextView = TextView(this).apply {
        text = label
        setTextColor(CREATION_PRIMARY)
        textSize = 20f
        typeface = CREATION_BOLD
        includeFontPadding = false
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, 0)
        background = creationRippleRounded(
            CREATION_CARD,
            CREATION_CARD_PRESSED,
            dp(14).toFloat(),
        )
        isClickable = true
        isFocusable = true
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onClick()
        }
    }

    companion object {
        fun createIntent(context: Context, route: CreationRoute): Intent =
            Intent(context, CreateTimeActivity::class.java).putCreationRoute(route)
    }
}
