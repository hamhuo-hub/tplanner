package com.hamhuo.tplanner

import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.hamhuo.tplanner.designsystem.TPlannerCategories
import com.hamhuo.tplanner.designsystem.TPlannerLightTokens as Tokens

// ── Colors ────────────────────────────────────────────────────────────────────
// Compatibility names keep layout consumers stable; action text and fills have distinct roles.
val BG = Color(Tokens.Semantic.Color.Canvas)
val INPUT_SURFACE = Color(Tokens.Semantic.Color.Input)
val SURFACE = Color(Tokens.Semantic.Color.Surface)
val SURFACE_LOW = Color(Tokens.Semantic.Color.Surface)
val SURFACE2 = Color(Tokens.Semantic.Color.Raised)
val CONTROL = Color(Tokens.Semantic.Color.Surface)
val CONTROL_STRONG = Color(Tokens.Semantic.Color.HoverBackground)
val EMPTY_STATE = Color(Tokens.Semantic.Color.TextMuted)
val DRAG_HANDLE = Color(Tokens.Semantic.Color.TextMuted)
val DIVIDER_STRONG = Color(Tokens.Semantic.Color.BorderControl)
val GOLD = Color(Tokens.Semantic.Color.Accent)
val GOLD_DARK = Color(Tokens.Semantic.Color.TextMuted)
val GOLD_GHOST = Color(Tokens.Semantic.Color.SelectedBackground)
val ACCENT_TEXT = Color(Tokens.Semantic.Color.AccentText)
val ON_ACCENT = Color(Tokens.Semantic.Color.OnAccent)
val FOCUS = Color(Tokens.Semantic.Color.Focus)
val DIM = Color(Tokens.Semantic.Color.TextSecondary)
val TEXT_PRIMARY = Color(Tokens.Semantic.Color.TextPrimary)
val TEXT_EDITOR = Color(Tokens.Semantic.Color.TextPrimary)
val BLUE = Color(Tokens.Semantic.Color.Info)
val TEAL = Color(Tokens.Semantic.Color.Success)
val RED = Color(Tokens.Semantic.Color.Error)
val WARNING = Color(Tokens.Semantic.Color.Warning)
val ERROR_BACKGROUND = Color(Tokens.Semantic.Color.ErrorBackground)
val BORDER = Color(Tokens.Semantic.Color.BorderControl)
val BORDER_SUBTLE = Color(Tokens.Semantic.Color.BorderSubtle)

// Category IDs are persisted user choices, independent of item type.
val EVENT_COLORS = (0..7).map { Color(TPlannerCategories.forColorId(it).accent) }

private val phoneScheme = lightColorScheme(
    primary = ACCENT_TEXT, onPrimary = SURFACE2,
    primaryContainer = GOLD, onPrimaryContainer = ON_ACCENT,
    secondary = BLUE, onSecondary = SURFACE2,
    secondaryContainer = Color(Tokens.Semantic.Color.InfoBackground), onSecondaryContainer = BLUE,
    tertiary = TEAL, onTertiary = SURFACE2,
    tertiaryContainer = Color(Tokens.Semantic.Color.SuccessBackground), onTertiaryContainer = TEAL,
    background = BG, onBackground = TEXT_PRIMARY,
    surface = SURFACE, onSurface = TEXT_PRIMARY,
    surfaceVariant = CONTROL_STRONG, onSurfaceVariant = DIM,
    surfaceTint = Color.Transparent,
    outline = BORDER, outlineVariant = BORDER_SUBTLE,
    error = RED, onError = SURFACE2,
    errorContainer = ERROR_BACKGROUND, onErrorContainer = RED,
)

private fun phoneText(size: Float, weight: Int, lineHeight: Float, tracking: Float) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontWeight = FontWeight(weight),
    fontSize = size.sp, lineHeight = (size * lineHeight).sp, letterSpacing = tracking.sp,
)

private val phoneHeading = phoneText(
    Tokens.Platform.Phone.Typography.Heading.FontSize, Tokens.Platform.Phone.Typography.Heading.FontWeight,
    Tokens.Platform.Phone.Typography.Heading.LineHeight, Tokens.Platform.Phone.Typography.Heading.LetterSpacing,
)
private val phoneTitle = phoneText(
    Tokens.Platform.Phone.Typography.Title.FontSize, Tokens.Platform.Phone.Typography.Title.FontWeight,
    Tokens.Platform.Phone.Typography.Title.LineHeight, Tokens.Platform.Phone.Typography.Title.LetterSpacing,
)
private val phoneBody = phoneText(
    Tokens.Platform.Phone.Typography.Body.FontSize, Tokens.Platform.Phone.Typography.Body.FontWeight,
    Tokens.Platform.Phone.Typography.Body.LineHeight, Tokens.Platform.Phone.Typography.Body.LetterSpacing,
)
private val phoneTask = phoneText(
    Tokens.Platform.Phone.Typography.TaskTitle.FontSize, Tokens.Platform.Phone.Typography.TaskTitle.FontWeight,
    Tokens.Platform.Phone.Typography.TaskTitle.LineHeight, Tokens.Platform.Phone.Typography.TaskTitle.LetterSpacing,
)
private val phoneMeta = phoneText(
    Tokens.Platform.Phone.Typography.Meta.FontSize, Tokens.Platform.Phone.Typography.Meta.FontWeight,
    Tokens.Platform.Phone.Typography.Meta.LineHeight, Tokens.Platform.Phone.Typography.Meta.LetterSpacing,
)

@Composable
fun TPlannerPhoneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = phoneScheme,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(Tokens.Semantic.Radius.Small.dp),
            small = RoundedCornerShape(Tokens.Semantic.Radius.Control.dp),
            medium = RoundedCornerShape(Tokens.Semantic.Radius.Card.dp),
            large = RoundedCornerShape(Tokens.Semantic.Radius.Dialog.dp),
            extraLarge = RoundedCornerShape(Tokens.Semantic.Radius.Dialog.dp),
        ),
        typography = Typography(
            displayLarge = phoneHeading, displayMedium = phoneHeading, displaySmall = phoneHeading,
            headlineLarge = phoneHeading, headlineMedium = phoneHeading, headlineSmall = phoneHeading,
            titleLarge = phoneTitle, titleMedium = phoneTask, titleSmall = phoneTask,
            bodyLarge = phoneBody, bodyMedium = phoneBody, bodySmall = phoneMeta,
            labelLarge = phoneTask, labelMedium = phoneMeta, labelSmall = phoneMeta,
        ),
        content = content,
    )
}

/** Phone-only aliases avoid changing the independently calibrated timeline/watch profiles. */
object PhoneTypography {
    const val PhoneHeadingSp = Tokens.Platform.Phone.Typography.Heading.FontSize
    const val PhoneSectionSp = Tokens.Platform.Phone.Typography.Title.FontSize
    const val PhoneTitleSp = Tokens.Platform.Phone.Typography.Title.FontSize
    const val PhoneBodySp = Tokens.Platform.Phone.Typography.Body.FontSize
    const val PhoneTaskTitleSp = Tokens.Platform.Phone.Typography.TaskTitle.FontSize
    const val PhoneSupportingSp = Tokens.Platform.Phone.Typography.Meta.FontSize
    const val PhoneMetaSp = Tokens.Platform.Phone.Typography.Meta.FontSize
    const val PhoneCaptionSp = PhoneMetaSp
    const val PhoneBadgeSp = PhoneMetaSp
    const val PhoneMicroSp = PhoneMetaSp
    const val PhoneLetterSpacingSp = Tokens.Platform.Phone.Typography.Body.LetterSpacing
    const val PhoneModalTitleSp = PhoneTitleSp
    const val PhoneEditorSp = PhoneBodySp
    const val PhoneDisplaySp = PhoneHeadingSp
    const val PhoneCompactLineHeightSp = PhoneMetaSp * Tokens.Platform.Phone.Typography.Meta.LineHeight
    const val PhoneSupportingLineHeightSp = PhoneCompactLineHeightSp
    const val PhoneBodyLineHeightSp = PhoneBodySp * Tokens.Platform.Phone.Typography.Body.LineHeight
    const val PhoneEditorLineHeightSp = PhoneBodyLineHeightSp
}

object PhoneGeometry {
    const val RadiusSmallDp = Tokens.Semantic.Radius.Small
    const val RadiusAccentMarkerDp = Tokens.Semantic.Radius.Small
    const val RadiusControlDp = Tokens.Semantic.Radius.Control
    const val RadiusCompactDp = Tokens.Semantic.Radius.Small
    const val RadiusMediumDp = Tokens.Semantic.Radius.Control
    const val RadiusPanelDp = Tokens.Semantic.Radius.Card
    const val RadiusCardDp = Tokens.Semantic.Radius.Card
    const val RadiusFieldDp = Tokens.Semantic.Radius.Control
    const val RadiusChipDp = Tokens.Semantic.Radius.Pill
    const val RadiusAppFrameDp = Tokens.Semantic.Radius.Card
    const val RadiusNavigationItemDp = Tokens.Semantic.Radius.Pill
    const val RadiusNavigationContainerDp = Tokens.Semantic.Radius.Pill
    const val RadiusPillDp = Tokens.Semantic.Radius.Pill
}
