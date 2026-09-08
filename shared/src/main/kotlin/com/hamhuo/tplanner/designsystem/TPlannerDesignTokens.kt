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
    object Hop {
        // Sampled from the supplied watch photograph; coordinates/provenance in hop-watchface.md.
        const val Paper: Int = 0xFFE5E8ED.toInt()
        const val Rim: Int = 0xFFF6F8F2.toInt()
        const val Ink: Int = 0xFF212021.toInt()
        const val Task: Int = 0xFF55565A.toInt()
        const val Track: Int = 0xFF96989C.toInt()
        const val Now: Int = 0xFFF77128.toInt()
        const val RecessShadow: Int = 0xFF30343A.toInt()
        const val AmbientBackground: Int = 0xFF000000.toInt()
        const val AmbientInk: Int = 0xFFA6A6A0.toInt()
    }

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
    // Hop's hour numerals retain the enlarged dial's scale; task copy stays readable.
    const val HopHourBaseSp = 40f
    const val HopHourMinSp = 36f
    const val HopHourMaxSp = 46f
    const val HopTaskBaseSp = 12f
    const val HopTaskMinSp = 11f
    const val HopTaskMaxSp = 14f
    const val HopNowSp = 9f
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

// BEGIN GENERATED LIGHT TOKENS
object TPlannerLightTokens {
    object Component {
        object Button {
            object Disabled {
                const val Background: Int = 0xFFDDE1E7.toInt()
                const val Foreground: Int = 0xFF606670.toInt()
            }
            object Focus {
                const val Color: Int = 0xFFA23F0A.toInt()
                const val Offset: Float = 2f
                const val Width: Float = 2f
            }
            object Primary {
                const val Background: Int = 0xFFF77128.toInt()
                const val Border: Int = 0xFF212021.toInt()
                const val Foreground: Int = 0xFF212021.toInt()
                const val HoverBackground: Int = 0xFFF98242.toInt()
                const val PressedBackground: Int = 0xFFE9631B.toInt()
            }
            const val Radius: Float = 8f
            object Secondary {
                const val Background: Int = 0xFFF6F8FA.toInt()
                const val Border: Int = 0xFF7C8087.toInt()
                const val Foreground: Int = 0xFF212021.toInt()
                const val HoverBackground: Int = 0xFFDDE1E7.toInt()
            }
        }
        object Dialog {
            const val Background: Int = 0xFFFFFFFF.toInt()
            const val Radius: Float = 16f
            const val ShadowBlur: Float = 24f
            const val ShadowColor: Int = 0xFF30343A.toInt()
            const val ShadowOpacity: Float = 0.16f
            const val ShadowSpread: Float = 0f
            const val ShadowX: Float = 0f
            const val ShadowY: Float = 8f
        }
        object Field {
            const val Background: Int = 0xFFFFFFFF.toInt()
            const val Border: Int = 0xFF7C8087.toInt()
            const val ErrorBorder: Int = 0xFFB3261E.toInt()
            const val FocusBorder: Int = 0xFFA23F0A.toInt()
            const val Foreground: Int = 0xFF212021.toInt()
            const val Placeholder: Int = 0xFF606670.toInt()
            const val Radius: Float = 8f
        }
        object Hop {
            object Color {
                const val AmbientBackground: Int = 0xFF000000.toInt()
                const val AmbientInk: Int = 0xFFA6A6A0.toInt()
                const val Ink: Int = 0xFF212021.toInt()
                const val Paper: Int = 0xFFE5E8ED.toInt()
                const val RecessShadow: Int = 0xFF30343A.toInt()
                const val Rim: Int = 0xFFF6F8F2.toInt()
                const val Task: Int = 0xFF55565A.toInt()
                const val Time: Int = 0xFFF77128.toInt()
                const val Track: Int = 0xFF96989C.toInt()
            }
            object Geometry {
                const val BaseDiameter: Float = 200f
                const val MajorTickStroke: Float = 1f
                const val MinorTickStroke: Float = 0.65f
                const val OrbitDiameterRatio: Float = 0.833333333333f
                const val RimDiameterRatio: Float = 0.016f
                const val RimMin: Float = 2.5f
                const val SafeInsetDiameterRatio: Float = 0.015f
                const val SafeInsetMin: Float = 4f
                const val TaskDiameterRatio: Float = 1.075f
                const val TaskStroke: Float = 0.55f
                const val TickDiameterRatio: Float = 1f
                const val TimeStroke: Float = 1.4f
            }
            object Recess {
                const val Alpha0: Float = 0f
                const val Alpha1: Float = 0.0352941176471f
                const val Alpha2: Float = 0.176470588235f
                const val Alpha3: Float = 0.439215686275f
                const val OffsetXDiameterRatio: Float = -0.012f
                const val OffsetYDiameterRatio: Float = 0.035f
                const val RadiusMultiplier: Float = 1.045f
                const val Stop0: Float = 0.78f
                const val Stop1: Float = 0.87f
                const val Stop2: Float = 0.96f
                const val Stop3: Float = 1f
            }
            object Typography {
                const val HourBase: Float = 40f
                val HourFamily: List<String> = listOf("Comfortaa", "sans-serif")
                const val HourMax: Float = 46f
                const val HourMin: Float = 36f
                const val HourWeight: Int = 700
                const val TaskBase: Float = 12f
                const val TaskMax: Float = 14f
                const val TaskMin: Float = 11f
                const val TaskTrackingMaxEm: Float = 0.12f
                const val Time: Float = 9f
            }
        }
        object Panel {
            const val Background: Int = 0xFFF6F8FA.toInt()
            const val Edge: Int = 0xFFF6F8F2.toInt()
            const val EdgeWidth: Float = 1f
            const val Radius: Float = 12f
            const val RaisedBackground: Int = 0xFFFFFFFF.toInt()
            const val ShadowBlur: Float = 8f
            const val ShadowColor: Int = 0xFF30343A.toInt()
            const val ShadowOpacity: Float = 0.08f
            const val ShadowSpread: Float = 0f
            const val ShadowX: Float = 0f
            const val ShadowY: Float = 2f
        }
        object Task {
            const val CompletedForeground: Int = 0xFF606670.toInt()
            const val CompletedOpacity: Float = 1f
            const val CurrentBackground: Int = 0xFFE5EEF8.toInt()
            const val CurrentForeground: Int = 0xFF235E92.toInt()
            const val Foreground: Int = 0xFF212021.toInt()
            const val NormalBackground: Int = 0xFFF6F8FA.toInt()
            const val SelectedBackground: Int = 0xFFFDE7DA.toInt()
            const val SelectedBorder: Int = 0xFFA23F0A.toInt()
            const val SupportingForeground: Int = 0xFF55565A.toInt()
        }
    }
    object Platform {
        object Desktop {
            object Geometry {
                const val ControlMinHeight: Float = 36f
                const val IconSize: Float = 20f
                const val PageInset: Float = 24f
                const val RowPaddingBlock: Float = 12f
                const val RowPaddingInline: Float = 12f
                const val TaskRowMinHeight: Float = 44f
                const val TouchTargetMin: Float = 44f
            }
            object Typography {
                object Body {
                    val FontFamily: List<String> = listOf("system-ui", "-apple-system", "BlinkMacSystemFont", "Segoe UI", "Noto Sans SC", "sans-serif")
                    const val FontSize: Float = 15f
                    const val FontWeight: Int = 400
                    const val LetterSpacing: Float = 0f
                    const val LineHeight: Float = 1.5f
                }
                object Heading {
                    val FontFamily: List<String> = listOf("system-ui", "-apple-system", "BlinkMacSystemFont", "Segoe UI", "Noto Sans SC", "sans-serif")
                    const val FontSize: Float = 24f
                    const val FontWeight: Int = 600
                    const val LetterSpacing: Float = 0f
                    const val LineHeight: Float = 1.25f
                }
                object Meta {
                    val FontFamily: List<String> = listOf("system-ui", "-apple-system", "BlinkMacSystemFont", "Segoe UI", "Noto Sans SC", "sans-serif")
                    const val FontSize: Float = 12f
                    const val FontWeight: Int = 400
                    const val LetterSpacing: Float = 0f
                    const val LineHeight: Float = 1.5f
                }
                object TaskTitle {
                    val FontFamily: List<String> = listOf("system-ui", "-apple-system", "BlinkMacSystemFont", "Segoe UI", "Noto Sans SC", "sans-serif")
                    const val FontSize: Float = 15f
                    const val FontWeight: Int = 500
                    const val LetterSpacing: Float = 0f
                    const val LineHeight: Float = 1.5f
                }
                object Title {
                    val FontFamily: List<String> = listOf("system-ui", "-apple-system", "BlinkMacSystemFont", "Segoe UI", "Noto Sans SC", "sans-serif")
                    const val FontSize: Float = 18f
                    const val FontWeight: Int = 600
                    const val LetterSpacing: Float = 0f
                    const val LineHeight: Float = 1.5f
                }
            }
        }
        object Phone {
            object Geometry {
                const val ControlMinHeight: Float = 48f
                const val IconSize: Float = 24f
                const val PageInset: Float = 16f
                const val RowPaddingBlock: Float = 12f
                const val RowPaddingInline: Float = 14f
                const val TaskRowMinHeight: Float = 52f
                const val TouchTargetMin: Float = 48f
            }
            object Typography {
                object Body {
                    val FontFamily: List<String> = listOf("system-ui", "-apple-system", "BlinkMacSystemFont", "Segoe UI", "Noto Sans SC", "sans-serif")
                    const val FontSize: Float = 16f
                    const val FontWeight: Int = 400
                    const val LetterSpacing: Float = 0f
                    const val LineHeight: Float = 1.5f
                }
                object Heading {
                    val FontFamily: List<String> = listOf("system-ui", "-apple-system", "BlinkMacSystemFont", "Segoe UI", "Noto Sans SC", "sans-serif")
                    const val FontSize: Float = 22f
                    const val FontWeight: Int = 600
                    const val LetterSpacing: Float = 0f
                    const val LineHeight: Float = 1.25f
                }
                object Meta {
                    val FontFamily: List<String> = listOf("system-ui", "-apple-system", "BlinkMacSystemFont", "Segoe UI", "Noto Sans SC", "sans-serif")
                    const val FontSize: Float = 13f
                    const val FontWeight: Int = 400
                    const val LetterSpacing: Float = 0f
                    const val LineHeight: Float = 1.5f
                }
                object TaskTitle {
                    val FontFamily: List<String> = listOf("system-ui", "-apple-system", "BlinkMacSystemFont", "Segoe UI", "Noto Sans SC", "sans-serif")
                    const val FontSize: Float = 15f
                    const val FontWeight: Int = 500
                    const val LetterSpacing: Float = 0f
                    const val LineHeight: Float = 1.5f
                }
                object Title {
                    val FontFamily: List<String> = listOf("system-ui", "-apple-system", "BlinkMacSystemFont", "Segoe UI", "Noto Sans SC", "sans-serif")
                    const val FontSize: Float = 18f
                    const val FontWeight: Int = 600
                    const val LetterSpacing: Float = 0f
                    const val LineHeight: Float = 1.5f
                }
            }
        }
        object Wear {
            object Geometry {
                const val ControlMinHeight: Float = 48f
                const val IconSize: Float = 24f
                const val PageInset: Float = 12f
                const val RowPaddingBlock: Float = 10f
                const val RowPaddingInline: Float = 13f
                const val TaskRowMinHeight: Float = 58f
                const val TouchTargetMin: Float = 48f
            }
            object Typography {
                object Body {
                    val FontFamily: List<String> = listOf("system-ui", "-apple-system", "BlinkMacSystemFont", "Segoe UI", "Noto Sans SC", "sans-serif")
                    const val FontSize: Float = 15f
                    const val FontWeight: Int = 400
                    const val LetterSpacing: Float = 0f
                    const val LineHeight: Float = 1.5f
                }
                object Heading {
                    val FontFamily: List<String> = listOf("system-ui", "-apple-system", "BlinkMacSystemFont", "Segoe UI", "Noto Sans SC", "sans-serif")
                    const val FontSize: Float = 20f
                    const val FontWeight: Int = 600
                    const val LetterSpacing: Float = 0f
                    const val LineHeight: Float = 1.25f
                }
                object Meta {
                    val FontFamily: List<String> = listOf("system-ui", "-apple-system", "BlinkMacSystemFont", "Segoe UI", "Noto Sans SC", "sans-serif")
                    const val FontSize: Float = 12f
                    const val FontWeight: Int = 400
                    const val LetterSpacing: Float = 0f
                    const val LineHeight: Float = 1.5f
                }
                object TaskTitle {
                    val FontFamily: List<String> = listOf("system-ui", "-apple-system", "BlinkMacSystemFont", "Segoe UI", "Noto Sans SC", "sans-serif")
                    const val FontSize: Float = 17f
                    const val FontWeight: Int = 500
                    const val LetterSpacing: Float = 0f
                    const val LineHeight: Float = 1.5f
                }
                object Title {
                    val FontFamily: List<String> = listOf("system-ui", "-apple-system", "BlinkMacSystemFont", "Segoe UI", "Noto Sans SC", "sans-serif")
                    const val FontSize: Float = 18f
                    const val FontWeight: Int = 600
                    const val LetterSpacing: Float = 0f
                    const val LineHeight: Float = 1.5f
                }
            }
        }
        object Web {
            object Geometry {
                const val ControlMinHeight: Float = 40f
                const val IconSize: Float = 20f
                const val PageInset: Float = 24f
                const val RowPaddingBlock: Float = 12f
                const val RowPaddingInline: Float = 12f
                const val TaskRowMinHeight: Float = 44f
                const val TouchTargetMin: Float = 44f
            }
            object Typography {
                object Body {
                    val FontFamily: List<String> = listOf("system-ui", "-apple-system", "BlinkMacSystemFont", "Segoe UI", "Noto Sans SC", "sans-serif")
                    const val FontSize: Float = 15f
                    const val FontWeight: Int = 400
                    const val LetterSpacing: Float = 0f
                    const val LineHeight: Float = 1.5f
                }
                object Heading {
                    val FontFamily: List<String> = listOf("system-ui", "-apple-system", "BlinkMacSystemFont", "Segoe UI", "Noto Sans SC", "sans-serif")
                    const val FontSize: Float = 24f
                    const val FontWeight: Int = 600
                    const val LetterSpacing: Float = 0f
                    const val LineHeight: Float = 1.25f
                }
                object Meta {
                    val FontFamily: List<String> = listOf("system-ui", "-apple-system", "BlinkMacSystemFont", "Segoe UI", "Noto Sans SC", "sans-serif")
                    const val FontSize: Float = 12f
                    const val FontWeight: Int = 400
                    const val LetterSpacing: Float = 0f
                    const val LineHeight: Float = 1.5f
                }
                object TaskTitle {
                    val FontFamily: List<String> = listOf("system-ui", "-apple-system", "BlinkMacSystemFont", "Segoe UI", "Noto Sans SC", "sans-serif")
                    const val FontSize: Float = 15f
                    const val FontWeight: Int = 500
                    const val LetterSpacing: Float = 0f
                    const val LineHeight: Float = 1.5f
                }
                object Title {
                    val FontFamily: List<String> = listOf("system-ui", "-apple-system", "BlinkMacSystemFont", "Segoe UI", "Noto Sans SC", "sans-serif")
                    const val FontSize: Float = 18f
                    const val FontWeight: Int = 600
                    const val LetterSpacing: Float = 0f
                    const val LineHeight: Float = 1.5f
                }
            }
        }
    }
    object Primitive {
        object Color {
            const val AmbientInk: Int = 0xFFA6A6A0.toInt()
            const val Black: Int = 0xFF000000.toInt()
            const val BlueCategory: Int = 0xFF5B8FCC.toInt()
            const val BlueCategoryInk: Int = 0xFF275F96.toInt()
            const val BlueCategoryTint: Int = 0xFFE5EEF8.toInt()
            const val BorderControl: Int = 0xFF7C8087.toInt()
            const val BorderSubtle: Int = 0xFFC7CBD2.toInt()
            const val ClayCategory: Int = 0xFFC87D5A.toInt()
            const val ClayCategoryInk: Int = 0xFF914D23.toInt()
            const val ClayCategoryTint: Int = 0xFFF8EBDD.toInt()
            const val Error: Int = 0xFFB3261E.toInt()
            const val ErrorTint: Int = 0xFFFCE7E5.toInt()
            const val GoldCategory: Int = 0xFFC9A84C.toInt()
            const val GoldCategoryInk: Int = 0xFF805700.toInt()
            const val GoldCategoryTint: Int = 0xFFF6EDCC.toInt()
            const val GrayCategory: Int = 0xFF8A8A8A.toInt()
            const val GrayCategoryInk: Int = 0xFF60656D.toInt()
            const val GrayCategoryTint: Int = 0xFFECEEF1.toInt()
            const val GreenCategory: Int = 0xFF5B9E72.toInt()
            const val GreenCategoryInk: Int = 0xFF27633D.toInt()
            const val GreenCategoryTint: Int = 0xFFE4F1E9.toInt()
            const val Hover: Int = 0xFFDDE1E7.toInt()
            const val Info: Int = 0xFF235E92.toInt()
            const val InfoTint: Int = 0xFFE5EEF8.toInt()
            const val Ink: Int = 0xFF212021.toInt()
            const val Muted: Int = 0xFF606670.toInt()
            const val Orange: Int = 0xFFF77128.toInt()
            const val OrangeHover: Int = 0xFFF98242.toInt()
            const val OrangePressed: Int = 0xFFE9631B.toInt()
            const val OrangeText: Int = 0xFFA23F0A.toInt()
            const val OrangeTint: Int = 0xFFFDE7DA.toInt()
            const val Paper: Int = 0xFFE5E8ED.toInt()
            const val PurpleCategory: Int = 0xFF8B6BAE.toInt()
            const val PurpleCategoryInk: Int = 0xFF684997.toInt()
            const val PurpleCategoryTint: Int = 0xFFEEE8F7.toInt()
            const val Rim: Int = 0xFFF6F8F2.toInt()
            const val RoseCategory: Int = 0xFFC0697A.toInt()
            const val RoseCategoryInk: Int = 0xFF993C55.toInt()
            const val RoseCategoryTint: Int = 0xFFF8E8EC.toInt()
            const val Secondary: Int = 0xFF55565A.toInt()
            const val Shadow: Int = 0xFF30343A.toInt()
            const val Success: Int = 0xFF256B45.toInt()
            const val SuccessTint: Int = 0xFFE4F1E9.toInt()
            const val Surface: Int = 0xFFF6F8FA.toInt()
            const val TealCategory: Int = 0xFF4A9DA8.toInt()
            const val TealCategoryInk: Int = 0xFF226A72.toInt()
            const val TealCategoryTint: Int = 0xFFE1F0F1.toInt()
            const val Track: Int = 0xFF96989C.toInt()
            const val Warning: Int = 0xFF805700.toInt()
            const val WarningTint: Int = 0xFFF6EDCC.toInt()
            const val White: Int = 0xFFFFFFFF.toInt()
        }
        object Font {
            val Body: List<String> = listOf("system-ui", "-apple-system", "BlinkMacSystemFont", "Segoe UI", "Noto Sans SC", "sans-serif")
            val HopNumeral: List<String> = listOf("Comfortaa", "sans-serif")
            val Mono: List<String> = listOf("ui-monospace", "SFMono-Regular", "Consolas", "monospace")
        }
        object Radius {
            const val Card: Float = 12f
            const val Control: Float = 8f
            const val Dialog: Float = 16f
            const val Pill: Float = 9999f
            const val Small: Float = 4f
        }
        object Space {
            const val S0: Float = 0f
            const val S12: Float = 12f
            const val S16: Float = 16f
            const val S2: Float = 2f
            const val S24: Float = 24f
            const val S32: Float = 32f
            const val S4: Float = 4f
            const val S48: Float = 48f
            const val S8: Float = 8f
        }
    }
    object Semantic {
        object Category {
            object Id0 {
                const val Accent: Int = 0xFF5B8FCC.toInt()
                const val Background: Int = 0xFFE5EEF8.toInt()
                const val Border: Int = 0xFF275F96.toInt()
                const val Foreground: Int = 0xFF275F96.toInt()
                const val Id: Float = 0f
                const val OnSolid: Int = 0xFFFFFFFF.toInt()
                const val Solid: Int = 0xFF275F96.toInt()
            }
            object Id1 {
                const val Accent: Int = 0xFFC9A84C.toInt()
                const val Background: Int = 0xFFF6EDCC.toInt()
                const val Border: Int = 0xFF805700.toInt()
                const val Foreground: Int = 0xFF805700.toInt()
                const val Id: Float = 1f
                const val OnSolid: Int = 0xFFFFFFFF.toInt()
                const val Solid: Int = 0xFF805700.toInt()
            }
            object Id2 {
                const val Accent: Int = 0xFFC0697A.toInt()
                const val Background: Int = 0xFFF8E8EC.toInt()
                const val Border: Int = 0xFF993C55.toInt()
                const val Foreground: Int = 0xFF993C55.toInt()
                const val Id: Float = 2f
                const val OnSolid: Int = 0xFFFFFFFF.toInt()
                const val Solid: Int = 0xFF993C55.toInt()
            }
            object Id3 {
                const val Accent: Int = 0xFF5B9E72.toInt()
                const val Background: Int = 0xFFE4F1E9.toInt()
                const val Border: Int = 0xFF27633D.toInt()
                const val Foreground: Int = 0xFF27633D.toInt()
                const val Id: Float = 3f
                const val OnSolid: Int = 0xFFFFFFFF.toInt()
                const val Solid: Int = 0xFF27633D.toInt()
            }
            object Id4 {
                const val Accent: Int = 0xFF8B6BAE.toInt()
                const val Background: Int = 0xFFEEE8F7.toInt()
                const val Border: Int = 0xFF684997.toInt()
                const val Foreground: Int = 0xFF684997.toInt()
                const val Id: Float = 4f
                const val OnSolid: Int = 0xFFFFFFFF.toInt()
                const val Solid: Int = 0xFF684997.toInt()
            }
            object Id5 {
                const val Accent: Int = 0xFFC87D5A.toInt()
                const val Background: Int = 0xFFF8EBDD.toInt()
                const val Border: Int = 0xFF914D23.toInt()
                const val Foreground: Int = 0xFF914D23.toInt()
                const val Id: Float = 5f
                const val OnSolid: Int = 0xFFFFFFFF.toInt()
                const val Solid: Int = 0xFF914D23.toInt()
            }
            object Id6 {
                const val Accent: Int = 0xFF4A9DA8.toInt()
                const val Background: Int = 0xFFE1F0F1.toInt()
                const val Border: Int = 0xFF226A72.toInt()
                const val Foreground: Int = 0xFF226A72.toInt()
                const val Id: Float = 6f
                const val OnSolid: Int = 0xFFFFFFFF.toInt()
                const val Solid: Int = 0xFF226A72.toInt()
            }
            object Id7 {
                const val Accent: Int = 0xFF8A8A8A.toInt()
                const val Background: Int = 0xFFECEEF1.toInt()
                const val Border: Int = 0xFF60656D.toInt()
                const val Foreground: Int = 0xFF60656D.toInt()
                const val Id: Float = 7f
                const val OnSolid: Int = 0xFFFFFFFF.toInt()
                const val Solid: Int = 0xFF60656D.toInt()
            }
        }
        object Color {
            const val Accent: Int = 0xFFF77128.toInt()
            const val AccentHover: Int = 0xFFF98242.toInt()
            const val AccentPressed: Int = 0xFFE9631B.toInt()
            const val AccentText: Int = 0xFFA23F0A.toInt()
            const val BorderControl: Int = 0xFF7C8087.toInt()
            const val BorderSubtle: Int = 0xFFC7CBD2.toInt()
            const val Canvas: Int = 0xFFE5E8ED.toInt()
            const val CurrentBackground: Int = 0xFFE5EEF8.toInt()
            const val DisabledBackground: Int = 0xFFDDE1E7.toInt()
            const val DisabledForeground: Int = 0xFF606670.toInt()
            const val EdgeHighlight: Int = 0xFFF6F8F2.toInt()
            const val Error: Int = 0xFFB3261E.toInt()
            const val ErrorBackground: Int = 0xFFFCE7E5.toInt()
            const val Focus: Int = 0xFFA23F0A.toInt()
            const val HoverBackground: Int = 0xFFDDE1E7.toInt()
            const val Info: Int = 0xFF235E92.toInt()
            const val InfoBackground: Int = 0xFFE5EEF8.toInt()
            const val Input: Int = 0xFFFFFFFF.toInt()
            const val OnAccent: Int = 0xFF212021.toInt()
            const val Raised: Int = 0xFFFFFFFF.toInt()
            const val SelectedBackground: Int = 0xFFFDE7DA.toInt()
            const val Success: Int = 0xFF256B45.toInt()
            const val SuccessBackground: Int = 0xFFE4F1E9.toInt()
            const val Surface: Int = 0xFFF6F8FA.toInt()
            const val TextMuted: Int = 0xFF606670.toInt()
            const val TextPrimary: Int = 0xFF212021.toInt()
            const val TextSecondary: Int = 0xFF55565A.toInt()
            const val Warning: Int = 0xFF805700.toInt()
            const val WarningBackground: Int = 0xFFF6EDCC.toInt()
        }
        object Motion {
            val Easing: List<Float> = listOf(0.2f, 0f, 0f, 1f)
            const val Fast: Long = 120L
            const val Instant: Long = 0L
            const val Slow: Long = 240L
            const val Standard: Long = 200L
        }
        object Radius {
            const val Card: Float = 12f
            const val Control: Float = 8f
            const val Dialog: Float = 16f
            const val Pill: Float = 9999f
            const val Small: Float = 4f
        }
        object Spacing {
            const val Block: Float = 12f
            const val Inline: Float = 8f
            const val InlineTight: Float = 4f
            const val Section: Float = 24f
        }
        object State {
            const val CompletedOpacity: Float = 1f
            const val DisabledOpacity: Float = 1f
            const val NormalOpacity: Float = 1f
        }
        object Stroke {
            const val Control: Float = 1f
            const val Focus: Float = 2f
            const val Hairline: Float = 0.5f
        }
    }
}
// END GENERATED LIGHT TOKENS
