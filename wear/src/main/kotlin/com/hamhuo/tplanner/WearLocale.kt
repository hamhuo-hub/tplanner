package com.hamhuo.tplanner

import android.content.Context
import androidx.annotation.StringRes
import java.time.format.DecimalStyle
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor
import java.util.Locale

/** The locale selected by Wear OS for this application's resources. */
internal fun Context.currentWatchLocale(): Locale {
    return resources.configuration.locales[0]
}

/**
 * A formatter that follows live Wear OS language changes, including while a watch face service
 * remains alive. Activities normally recreate for a locale change; watch face renderers do not.
 */
internal class LocalizedDateTimeFormatter(
    context: Context,
    @StringRes private val patternResource: Int,
) {
    private val appContext = context.applicationContext
    @Volatile private var cachedState: State? = null

    fun format(value: TemporalAccessor): String {
        val locale = appContext.currentWatchLocale()
        val pattern = appContext.getString(patternResource)
        val key = "${locale.toLanguageTag()}\u0000$pattern"
        val current = cachedState
        if (current?.key == key) return current.formatter.format(value)

        val refreshed = synchronized(this) {
            cachedState?.takeIf { it.key == key } ?: State(
                key = key,
                formatter = DateTimeFormatter.ofPattern(pattern, locale)
                    .withDecimalStyle(DecimalStyle.of(locale)),
            ).also { cachedState = it }
        }
        return refreshed.formatter.format(value)
    }

    private data class State(
        val key: String,
        val formatter: DateTimeFormatter,
    )
}
