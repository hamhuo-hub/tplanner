package com.hamhuo.tplanner

/** Fifth destination: alarm, color, and the final save action. */
class CreateSettingsActivity : WearPageActivity() {
    private lateinit var alarmRow: LinearLayout
    private lateinit var alarmValue: TextView
    private lateinit var colorRow: LinearLayout
    private lateinit var colorValue: TextView
    private lateinit var saveButton: TextView
    private lateinit var route: CreationRoute

    private var colorId: Int = 0
    private var alarmEnabled: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.task_create_settings_page)

        route = intent.creationRouteOrNull()?.takeIf {
            !it.type.isNullOrBlank() &&
                it.hour in 0..23 &&
                it.minute in 0..59 &&
                it.dateEpochDay != DATE_EPOCH_DAY_UNSET
        } ?: run {
            finish()
            return
        }

        colorId = savedInstanceState?.getInt(STATE_COLOR_ID) ?: DEFAULT_COLOR_ID
        alarmEnabled = savedInstanceState?.getBoolean(STATE_ALARM_ENABLED)
            ?: (route.type == TYPE_EVENT)

        val content = creationContent().apply {
            addView(creationTopSpacer())
            addView(creationHeading(getString(R.string.task_create_settings_page)))
            addView(createAlarmRow())
            addView(createColorRow())
            addView(
                creationActionRow(R.string.task_create_save) {
                    saveTask()
                }.also { saveButton = it },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
                    topMargin = dp(9)
                },
            )
            addView(creationBottomSpacer())
        }

        setContentView(creationScrollPage(content))
        renderSettings()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_COLOR_ID, colorId)
        outState.putBoolean(STATE_ALARM_ENABLED, alarmEnabled)
        super.onSaveInstanceState(outState)
    }

    private fun createAlarmRow(): LinearLayout {
        alarmRow = creationSettingRow(
            titleRes = R.string.task_create_alarm,
            descriptionRes = R.string.task_create_alarm_description,
        )
        alarmValue = alarmRow.findViewWithTag(TAG_VALUE)
        alarmRow.setOnClickListener {
            alarmEnabled = !alarmEnabled
            renderAlarm()
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        return alarmRow
    }

    private fun createColorRow(): LinearLayout {
        colorRow = creationSettingRow(
            titleRes = R.string.task_create_color,
            descriptionRes = R.string.task_create_color_description,
        )
        colorValue = colorRow.findViewWithTag(TAG_VALUE)
        colorRow.setOnClickListener {
            colorId = (colorId + 1) % TASK_COLORS.size
            renderColor()
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        return colorRow
    }

    private fun renderSettings() {
        renderAlarm()
        renderColor()
    }

    private fun renderAlarm() {
        val value = getString(if (alarmEnabled) R.string.task_create_on else R.string.task_create_off)
        alarmValue.text = value
        alarmValue.setTextColor(if (alarmEnabled) CREATION_ACCENT else CREATION_DIM)
        alarmValue.contentDescription = value
        alarmRow.contentDescription = getString(
            R.string.task_create_setting_accessibility,
            getString(R.string.task_create_alarm),
            value,
        )
    }

    private fun renderColor() {
        val names = resources.getStringArray(R.array.task_create_color_names)
        val safeColorId = colorId.coerceIn(TASK_COLORS.indices)
        val value = names[safeColorId]
        colorValue.text = value
        colorValue.setTextColor(TASK_COLORS[safeColorId])
        colorRow.contentDescription = getString(
            R.string.task_create_setting_accessibility,
            getString(R.string.task_create_color),
            value,
        )
    }

    private fun saveTask() {
        if (!saveButton.isEnabled) return
        saveButton.isEnabled = false
        saveButton.text = getString(R.string.task_create_saving)

        val start = LocalDate.ofEpochDay(route.dateEpochDay)
            .atTime(route.hour, route.minute)
            .atZone(CREATION_ZONE)

        val draft = WatchTaskDraft(
            id = route.id,
            title = route.title,
            type = route.type.orEmpty(),
            startEpochMs = start.toInstant().toEpochMilli(),
            endEpochMs = start.plusHours(DEFAULT_DURATION_HOURS).toInstant().toEpochMilli(),
            alarmEnabled = alarmEnabled,
            alarmOffsetMinutes = 0,
            colorId = colorId,
            updatedAtEpochMs = route.updatedAtEpochMs,
        )

        val queued = WatchTaskOutbox.enqueue(this, draft)
        if (!queued) {
            saveButton.isEnabled = true
            saveButton.text = getString(R.string.task_create_save)
            Toast.makeText(this, R.string.task_create_failed, Toast.LENGTH_SHORT).show()
            saveButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            return
        }

        saveButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        Toast.makeText(this, R.string.task_create_queued, Toast.LENGTH_SHORT).show()
        setResult(Activity.RESULT_OK)
        finish()
    }

    companion object {
        private const val STATE_COLOR_ID = "color_id"
        private const val STATE_ALARM_ENABLED = "alarm_enabled"
        private const val DEFAULT_COLOR_ID = 0
        private const val DEFAULT_DURATION_HOURS = 1L

        fun createIntent(context: Context, route: CreationRoute): Intent =
            Intent(context, CreateSettingsActivity::class.java).putCreationRoute(route)
    }
}

