package com.hamhuo.tplanner



/** Fourth destination: Day / Month selector styled like the Wear date picker, without a year column. */
class CreateDateActivity : WearPageActivity() {
    private var destinationOpened = false
    private var selectedDayField = true
    private var day = 1
    private var month = 1

    private lateinit var dayText: TextView
    private lateinit var monthText: TextView
    private lateinit var dayLabel: TextView
    private lateinit var monthLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.task_create_date)

        val route = intent.creationRouteOrNull()?.takeIf {
            !it.type.isNullOrBlank() && it.hour in 0..23 && it.minute in 0..59
        } ?: run {
            finish()
            return
        }

        val today = LocalDate.now(CREATION_ZONE)
        val initialDate = route.dateEpochDay
            .takeIf { it != DATE_EPOCH_DAY_UNSET }
            ?.let { runCatching { LocalDate.ofEpochDay(it) }.getOrNull() }
            ?: today

        day = initialDate.dayOfMonth
        month = initialDate.monthValue

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        val heading = creationHeading("Set date").apply {
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

        val dateBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val labelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        dayLabel = dateFieldLabel("Day") {
            if (!selectedDayField) {
                selectedDayField = true
                renderSelection()
            }
        }
        monthLabel = dateFieldLabel("Month") {
            if (selectedDayField) {
                selectedDayField = false
                renderSelection()
            }
        }

        // Same fixed geometry for labels and values so both columns stay optically centered.
        labelRow.addView(dayLabel, LinearLayout.LayoutParams(dp(72), dp(24)))
        labelRow.addView(View(this), LinearLayout.LayoutParams(dp(14), dp(24)))
        labelRow.addView(monthLabel, LinearLayout.LayoutParams(dp(88), dp(24)))
        dateBlock.addView(labelRow)

        val valueRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        dayText = dateValueText(38f).apply {
            setOnClickListener {
                if (!selectedDayField) {
                    selectedDayField = true
                    renderSelection()
                }
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
        monthText = dateValueText(34f).apply {
            setOnClickListener {
                if (selectedDayField) {
                    selectedDayField = false
                    renderSelection()
                }
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }

        attachVerticalAdjustGesture(dayText, selectDay = true)
        attachVerticalAdjustGesture(monthText, selectDay = false)

        valueRow.addView(dayText, LinearLayout.LayoutParams(dp(72), dp(54)))
        valueRow.addView(View(this), LinearLayout.LayoutParams(dp(14), dp(54)))
        valueRow.addView(monthText, LinearLayout.LayoutParams(dp(88), dp(54)))
        dateBlock.addView(valueRow)

        root.addView(
            dateBlock,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(58)
            },
        )

        val doneButton = TextView(this).apply {
            text = "Done"
            setTextColor(Color.BLACK)
            textSize = 15f
            typeface = CREATION_BOLD
            includeFontPadding = false
            gravity = Gravity.CENTER
            background = creationRippleRounded(
                CREATION_ACCENT,
                0xFFE0B900.toInt(),
                dp(20).toFloat(),
            )
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (destinationOpened) return@setOnClickListener
                destinationOpened = true
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

                @Suppress("DEPRECATION")
                startActivityForResult(
                    CreateSettingsActivity.createIntent(
                        this@CreateDateActivity,
                        route.copy(dateEpochDay = resolveSelectedDate().toEpochDay()),
                    ),
                    REQUEST_CREATION_NEXT,
                )
            }
        }

        root.addView(
            doneButton,
            FrameLayout.LayoutParams(dp(132), dp(40)).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(9)
            },
        )

        setContentView(root)
        renderDate()
        renderSelection()
    }

    /**
     * The year is intentionally absent from the UI.
     * If the selected month/day has already passed this year, use its next occurrence.
     */
    private fun resolveSelectedDate(): LocalDate {
        val today = LocalDate.now(CREATION_ZONE)
        var year = today.year

        var safeDay = day.coerceAtMost(YearMonth.of(year, month).lengthOfMonth())
        var candidate = LocalDate.of(year, month, safeDay)

        if (candidate.isBefore(today)) {
            year += 1
            safeDay = day.coerceAtMost(YearMonth.of(year, month).lengthOfMonth())
            candidate = LocalDate.of(year, month, safeDay)
        }
        return candidate
    }

    private fun adjust(delta: Int) {
        if (selectedDayField) {
            val year = inferredYearFor(month, day)
            val maxDay = YearMonth.of(year, month).lengthOfMonth()
            day = when {
                day + delta > maxDay -> 1
                day + delta < 1 -> maxDay
                else -> day + delta
            }
        } else {
            month = ((month - 1 + delta) % 12 + 12) % 12 + 1
            val year = inferredYearFor(month, day)
            day = day.coerceAtMost(YearMonth.of(year, month).lengthOfMonth())
        }

        renderDate()
    }

    private fun inferredYearFor(monthValue: Int, dayValue: Int): Int {
        val today = LocalDate.now(CREATION_ZONE)
        val currentYearMax = YearMonth.of(today.year, monthValue).lengthOfMonth()
        val safeDay = dayValue.coerceAtMost(currentYearMax)
        val candidate = LocalDate.of(today.year, monthValue, safeDay)
        return if (candidate.isBefore(today)) today.year + 1 else today.year
    }

    private fun renderDate() {
        dayText.text = "%02d".format(day)
        monthText.text = java.time.Month.of(month)
            .getDisplayName(TextStyle.SHORT, Locale.getDefault())
            .uppercase(Locale.getDefault())
    }

    private fun renderSelection() {
        dayText.setTextColor(if (selectedDayField) CREATION_ACCENT else CREATION_PRIMARY)
        monthText.setTextColor(if (selectedDayField) CREATION_PRIMARY else CREATION_ACCENT)
        dayLabel.setTextColor(if (selectedDayField) CREATION_PRIMARY else CREATION_DIM)
        monthLabel.setTextColor(if (selectedDayField) CREATION_DIM else CREATION_PRIMARY)
    }

    private fun dateFieldLabel(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(CREATION_DIM)
            textSize = 12f
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

    private fun dateValueText(sizeSp: Float): TextView = TextView(this).apply {
        setTextColor(CREATION_PRIMARY)
        textSize = sizeSp
        typeface = CREATION_MEDIUM
        includeFontPadding = false
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
    }

    @Suppress("ClickableViewAccessibility")
    private fun attachVerticalAdjustGesture(view: View, selectDay: Boolean) {
        var downY = 0f

        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.y
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (selectedDayField != selectDay) {
                        selectedDayField = selectDay
                        renderSelection()
                    }

                    val deltaY = event.y - downY
                    if (abs(deltaY) >= dp(18)) {
                        adjust(if (deltaY < 0f) 1 else -1)
                        target.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    } else {
                        target.performClick()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event == null) return false

        val scroll = event.getAxisValue(MotionEvent.AXIS_SCROLL)
        if (scroll != 0f) {
            adjust(if (scroll > 0f) 1 else -1)
            (if (selectedDayField) dayText else monthText)
                .performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            return true
        }

        return super.onGenericMotionEvent(event)
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

    companion object {
        fun createIntent(context: Context, route: CreationRoute): Intent =
            Intent(context, CreateDateActivity::class.java).putCreationRoute(route)
    }
}


