package com.hamhuo.tplanner.designsystem

/** Canonical visual tokens consumed by both the phone and Wear modules. */
object TPlannerColors {
    const val WatchFaceBackground: Int = 0xFF0D0D0D.toInt()
    const val Background: Int = 0xFF0E0E0E.toInt()
    const val InputSurface: Int = 0xFF111111.toInt()
    const val Surface: Int = 0xFF1A1A1A.toInt()
    const val SurfaceLow: Int = 0xFF1F1F1F.toInt()
    const val SurfaceRaised: Int = 0xFF222222.toInt()
    const val WatchTrack: Int = 0xFF232323.toInt()
    const val Control: Int = 0xFF252525.toInt()
    const val Border: Int = 0xFF2D2D2D.toInt()
    const val ControlStrong: Int = 0xFF2E2E2E.toInt()
    const val EmptyState: Int = 0xFF3A342A.toInt()
    const val DragHandle: Int = 0xFF444444.toInt()
    const val DividerStrong: Int = 0xFF4A4A4A.toInt()
    const val TextPrimary: Int = 0xFFE0D8C8.toInt()
    const val TextEditor: Int = 0xFFE8E0D0.toInt()
    const val TextSecondary: Int = 0xFF7A7163.toInt()
    const val WatchTextSecondary: Int = 0xFF857F6E.toInt()
    const val WatchAmbientText: Int = 0xFF8A857A.toInt()
    const val Gold: Int = 0xFFC9A84C.toInt()
    const val GoldPressed: Int = 0xFF9C823A.toInt()
    const val GoldDark: Int = 0xFF6B5928.toInt()
    const val WatchAmbientGold: Int = 0xFF55503F.toInt()
    const val Blue: Int = 0xFF5B8FCC.toInt()
    const val BlueBright: Int = 0xFF8BB8E8.toInt()
    const val Teal: Int = 0xFF4A9DA8.toInt()
    const val Green: Int = 0xFF4A7C59.toInt()
    const val Red: Int = 0xFFC0392B.toInt()
    const val GoldGhost: Int = 0x1FC9A84C
    const val BlueGhost: Int = 0x0F5B8FCC
    const val BlueBorder: Int = 0x305B8FCC
    const val GreenGhost: Int = 0x334A7C59
    const val WatchEventDot: Int = 0x604A90D0
    const val WatchDashboardScrimTop: Int = 0x24000000
    const val WatchDashboardScrimMiddle: Int = 0x16000000
    const val WatchDashboardShineStrong: Int = 0xE6FFFFFF.toInt()
    const val WatchDashboardShineSoft: Int = 0x66FFFFFF

    val EventPalette: IntArray = intArrayOf(
        0xFF5B8FCC.toInt(),
        0xFFC9A84C.toInt(),
        0xFFC0697A.toInt(),
        0xFF5B9E72.toInt(),
        0xFF8B6BAE.toInt(),
        0xFFC87D5A.toInt(),
        0xFF4A9DA8.toInt(),
        0xFF8A8A8A.toInt(),
    )
}

/**
 * Canonical art direction for the watch faces. These colors intentionally do not inherit the
 * product UI theme: a face may have its own palette, but renderers must not own raw color values.
 */
object TPlannerWatchFacePalette {
    object Tide {
        const val BootHighlight: Int = 0xFFEDD890.toInt()
    }

    object Next {
        const val Black: Int = 0xFF000000.toInt()
        const val Primary: Int = 0xFFF4F1EB.toInt()
        const val Secondary: Int = 0xFF9C9992.toInt()
        const val WheelLabel: Int = 0xFFD8D5CE.toInt()
        const val WheelMajor: Int = 0xFFC2BFB8.toInt()
        const val WheelMinor: Int = 0xFF65635F.toInt()
        const val WheelTrack: Int = 0xFF2D2C2A.toInt()
        const val TaskTrack: Int = 0xFF34312D.toInt()
        const val Frame: Int = 0xFF292826.toInt()
        const val Divider: Int = 0xFF6F665B.toInt()
        const val Accent: Int = 0xFFD9A441.toInt()
        const val AccentLight: Int = 0xFFF0C96C.toInt()
        const val AmbientPrimary: Int = 0xFFB4B1AA.toInt()
        const val AmbientText: Int = 0xFF77746E.toInt()
        const val AmbientStroke: Int = 0xFF4A4742.toInt()
    }
}

object TPlannerTypography {
    const val PhoneHeadingSp = 22f
    const val PhoneSectionSp = 20f
    const val PhoneTitleSp = 18f
    const val PhoneBodySp = 16f
    const val PhoneTaskTitleSp = 15f
    const val PhoneSupportingSp = 14f
    const val PhoneMetaSp = 13f
    const val PhoneCaptionSp = 12f
    const val PhoneBadgeSp = 11f
    const val PhoneMicroSp = 10f
    const val PhoneModalTitleSp = 19f
    const val PhoneEditorSp = 17f
    const val PhoneDisplaySp = 26f
    const val PhoneCompactLineHeightSp = 18f
    const val PhoneSupportingLineHeightSp = 20f
    const val PhoneBodyLineHeightSp = 26f
    const val PhoneEditorLineHeightSp = 28f
    const val TimelineWeekdaySp = 6.5f
    const val TimelineMonthSp = 7.5f
    const val TimelineTimeSp = 8f
    const val TimelineCompactSp = 9f
    const val TimelineCompactLineHeightSp = 10f
    const val TimelineBodySp = 11f
    const val TimelineBodyLineHeightSp = 13f
    const val TimelineDaySp = 14f
    const val TimelineDayLineHeightSp = 16f
    const val TimelineHourLineHeightSp = 9f
    const val WearTaskTitleSp = 17f
    const val WearSupportingSp = 13f
    const val WearHeadingSp = 23f
    const val WearCaptionSp = 12f
    const val WearMicroSp = 11.5f
    const val WearBodySp = 15f
    const val WearSectionSp = 18f
    const val WearDialogTitleSp = 19f
    const val WearTitleSp = 20f
    const val WearTimePrimarySp = 42f
    const val WearTimeSecondarySp = 34f
}

object TPlannerGeometry {
    const val RadiusSmallDp = 2
    const val RadiusAccentMarkerDp = 3
    const val RadiusControlDp = 4
    const val RadiusCompactDp = 5
    const val RadiusMediumDp = 9
    const val RadiusPanelDp = 10
    const val RadiusCardDp = 12
    const val RadiusWearDp = 13
    const val RadiusFieldDp = 14
    const val RadiusChipDp = 20
    const val RadiusAppFrameDp = 20
    const val RadiusNavigationItemDp = 22
    const val RadiusNavigationContainerDp = 28
    const val RadiusTimelineCanvasDp = 8
    const val RadiusPillDp = 50
}
