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
    const val PhoneCompactLineHeightSp = 18f
    const val PhoneBodyLineHeightSp = 26f
    const val WearTaskTitleSp = 17f
    const val WearSupportingSp = 13f
    const val WearHeadingSp = 23f
    const val WearCaptionSp = 12f
}

object TPlannerGeometry {
    const val RadiusSmallDp = 2
    const val RadiusControlDp = 4
    const val RadiusCompactDp = 5
    const val RadiusMediumDp = 9
    const val RadiusPanelDp = 10
    const val RadiusCardDp = 12
    const val RadiusWearDp = 13
    const val RadiusFieldDp = 14
    const val RadiusChipDp = 20
    const val RadiusPillDp = 50
}
