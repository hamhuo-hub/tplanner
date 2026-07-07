package com.hamhuo.tplanner

import android.graphics.Paint
import android.graphics.Typeface

enum class FaceDesign(val interactiveDelayMs: Long) {
    RING(1000L),
    ORBIT(1000L),
    EMBER(100L),
    TIDE(100L),
    PULSE(100L),
    LUMINA(80L),
    MOON(100L),
}

// ═══════════════════════════════════════════════════════════════════════════
// 颜色常量 & 动画时长
// 暗底 #0D0D0D · 金 #C9A84C · 米白衬线数字 · 青色事件点
// ═══════════════════════════════════════════════════════════════════════════

const val BOOT_MS = 800L
const val TAP_MS  = 600L

const val MOON_CLR  = 0xFFDDD6C5.toInt()
const val BG        = 0xFF0D0D0D.toInt()
const val GOLD      = 0xFFC9A84C.toInt()
const val CREAM     = 0xFFE8E0D0.toInt()
const val DIM       = 0xFF857F6E.toInt()
const val TEAL      = 0xFF4A9DA8.toInt()
const val TRACK     = 0xFF232323.toInt()
const val TICK      = 0xFF2E2E2E.toInt()
const val LINE      = 0xFF3A362B.toInt()
const val BTN_FILL  = 0xFF161410.toInt()
const val AMB_TEXT  = 0xFF8A857A.toInt()
const val AMB_GOLD  = 0xFF55503F.toInt()
const val AMB_TRACK = 0xFF1A1A1A.toInt()

// ── Paint 快捷函数 ──────────────────────────────────────────────────────
// 每个方法先清掉上一次的状态再设置新的，避免 PathEffect / Typeface / textAlign 残留。

fun Paint.setFill(c: Int, alpha: Float = 1f) {
    pathEffect = null; typeface = Typeface.DEFAULT
    style = Paint.Style.FILL; color = c; this.alpha = (255 * alpha).toInt().coerceIn(0, 255)
}

fun Paint.setStroke(c: Int, w: Float, cap: Paint.Cap = Paint.Cap.BUTT) {
    pathEffect = null; typeface = Typeface.DEFAULT
    style = Paint.Style.STROKE; color = c; strokeWidth = w; strokeCap = cap
}

fun Paint.setText(c: Int, size: Float, tf: Typeface = Typeface.DEFAULT) {
    pathEffect = null; style = Paint.Style.FILL; color = c
    textSize = size; textAlign = Paint.Align.CENTER; typeface = tf
}

fun easeOutCubic(x: Float): Float { val v = 1f - x; return 1f - v * v * v }
