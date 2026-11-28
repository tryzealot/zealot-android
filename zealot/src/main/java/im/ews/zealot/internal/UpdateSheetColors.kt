package im.ews.zealot.internal

import android.content.Context
import android.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import im.ews.zealot.R

internal class UpdateSheetColors(private val context: Context, theme: Int) {
    private val overlay = if (theme == 0) null else ContextThemeWrapper(context, theme)
    private val overrides = mutableMapOf<Int, Int>()

    fun color(fallback: Int): Int {
        val default = ContextCompat.getColor(context, fallback)
        val attribute = attributes[fallback] ?: return default
        val themedContext = overlay ?: return default
        return overrides.getOrPut(fallback) {
            val values = themedContext.obtainStyledAttributes(intArrayOf(attribute))
            try {
                values.getColor(0, default)
            } finally {
                values.recycle()
            }
        }
    }

    private companion object {
        val attributes = mapOf(
            R.color.zealot_prompt_surface to R.attr.zealotSheetSurface,
            R.color.zealot_prompt_notes_surface to R.attr.zealotSheetNotesSurface,
            R.color.zealot_prompt_text to R.attr.zealotSheetOnSurface,
            R.color.zealot_prompt_secondary to R.attr.zealotSheetOnSurfaceVariant,
            R.color.zealot_prompt_accent to R.attr.zealotSheetAccent,
            R.color.zealot_prompt_on_accent to R.attr.zealotSheetOnAccent,
            R.color.zealot_prompt_outline to R.attr.zealotSheetProgressTrack,
            R.color.zealot_prompt_ripple to R.attr.zealotSheetRipple
        )
    }
}
