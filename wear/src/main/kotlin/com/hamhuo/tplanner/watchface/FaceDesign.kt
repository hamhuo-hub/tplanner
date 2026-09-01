package com.hamhuo.tplanner

import android.graphics.Paint
import android.graphics.Typeface
import com.hamhuo.tplanner.designsystem.TPlannerColors

enum class FaceDesign(val interactiveDelayMs: Long) {
    TIDE(100L),
    NEXT(1_000L),
}

// ═══════════════════════════════════════════════════════════════════════════
// 颜色常量 & 动画时长
// 暗底 #0D0D0D · 金 #C9A84C · 米白衬线数字 · 青色事件点
// ═══════════════════════════════════════════════════════════════════════════

const val BOOT_MS = 800L

const val BG        = TPlannerColors.WatchFaceBackground
const val GOLD      = TPlannerColors.Gold
const val CREAM     = TPlannerColors.TextEditor
const val DIM       = TPlannerColors.WatchTextSecondary
const val EVENT_DOT = TPlannerColors.WatchEventDot // 日程事件：蓝色半透明小点
const val TRACK     = TPlannerColors.WatchTrack
const val AMB_TEXT  = TPlannerColors.WatchAmbientText
const val AMB_GOLD  = TPlannerColors.WatchAmbientGold
const val AMB_TRACK = TPlannerColors.Surface

// ── Paint 快捷函数 ──────────────────────────────────────────────────────
// 每个方法先清掉上一次的状态再设置新的，避免 PathEffect / Typeface / textAlign 残留。

fun Paint.setFill(c: Int, alpha: Float = 1f) {
    pathEffect = null; typeface = Typeface.DEFAULT; textSkewX = 0f
    style = Paint.Style.FILL; color = c; this.alpha = (255 * alpha).toInt().coerceIn(0, 255)
}

fun Paint.setStroke(c: Int, w: Float, cap: Paint.Cap = Paint.Cap.BUTT) {
    pathEffect = null; typeface = Typeface.DEFAULT; textSkewX = 0f
    style = Paint.Style.STROKE; color = c; strokeWidth = w; strokeCap = cap
}

fun Paint.setText(c: Int, size: Float, tf: Typeface = Typeface.DEFAULT) {
    pathEffect = null; style = Paint.Style.FILL; color = c; textSkewX = 0f
    textSize = size; textAlign = Paint.Align.CENTER; typeface = tf
}

fun easeOutCubic(x: Float): Float { val v = 1f - x; return 1f - v * v * v }
