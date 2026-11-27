package im.ews.zealot.internal

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

internal fun Context.withUpdateLocale(locale: Locale?): Context =
    if (locale == null) this
    else createConfigurationContext(Configuration().apply { setLocale(locale) })
