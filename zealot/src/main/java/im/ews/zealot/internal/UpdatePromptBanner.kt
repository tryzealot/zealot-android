package im.ews.zealot.internal

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.BidiFormatter
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import im.ews.zealot.R
import im.ews.zealot.ReleaseInfo
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.math.roundToInt

internal sealed interface UpdateNotice {
    data class Available(
        val release: ReleaseInfo,
        val linkFailed: Boolean = false,
        val manual: Boolean = false
    ) : UpdateNotice
    data object Checking : UpdateNotice
    data object Current : UpdateNotice
    data object CheckFailed : UpdateNotice
}

internal class UpdatePromptBanner(activity: Activity, private val locale: Locale?) {
    private val activityReference = WeakReference(activity)
    private var shown: UpdateNotice? = null
    private var card: BannerContent? = null
    private var removeLayoutListener: (() -> Unit)? = null

    fun render(
        notice: UpdateNotice?,
        open: (ReleaseInfo) -> Unit,
        later: (ReleaseInfo) -> Unit,
        retry: () -> Unit,
        dismiss: () -> Unit
    ) {
        if (notice == shown && card?.parent != null) return
        if (notice == null) {
            dismissCard()
            return
        }
        val replacing = card != null
        hide()
        val activity = activityReference.get() ?: return
        val content = activity.findViewById<View>(android.R.id.content) as? FrameLayout ?: return
        val builder = BannerContent(activity).apply { setUpdateLocale(locale) }
        when (notice) {
            is UpdateNotice.Available -> with(builder) {
                val release = notice.release
                releaseHeader(release)
                if (notice.linkFailed) message(R.string.zealot_prompt_open_failed)
                if (release.changelog.isNotBlank()) releaseNotes(release.changelog)
                actions(
                    button(R.string.zealot_update_later) { later(release) },
                    button(R.string.zealot_prompt_open, primary = true) { open(release) }
                )
            }
            UpdateNotice.Checking -> builder.title(R.string.zealot_prompt_checking, busy = true)
            UpdateNotice.Current -> with(builder) {
                title(R.string.zealot_prompt_current)
                actions(button(R.string.zealot_prompt_dismiss) { dismiss() })
            }
            UpdateNotice.CheckFailed -> with(builder) {
                title(R.string.zealot_prompt_check_failed)
                message(R.string.zealot_prompt_check_hint)
                actions(
                    button(R.string.zealot_prompt_dismiss) { dismiss() },
                    button(R.string.zealot_prompt_retry, primary = true) { retry() }
                )
            }
        }

        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        )
        val animated = content.isAttachedToWindow && motionEnabled()
        if (animated) {
            builder.alpha = 0f
            builder.translationY = builder.dp(if (replacing) 4 else 16).toFloat()
        }
        content.addView(builder, params)
        card = builder
        shown = notice
        val frame = Rect()
        val location = IntArray(2)
        val place = ViewTreeObserver.OnGlobalLayoutListener {
            if (content.width <= 0) return@OnGlobalLayoutListener
            content.getWindowVisibleDisplayFrame(frame)
            content.getLocationOnScreen(location)
            val safeSide = maxOf(0, frame.left - location[0], location[0] + content.width - frame.right)
            val safeBottom = maxOf(0, location[1] + content.height - frame.bottom)
            val margin = builder.dp(16)
            val width = (content.width - 2 * (safeSide + margin)).coerceIn(1, builder.dp(440))
            val bottom = safeBottom + margin
            builder.fitNotes(minOf(content.height, frame.height()))
            if (params.width != width || params.bottomMargin != bottom) {
                params.width = width
                params.bottomMargin = bottom
                builder.layoutParams = params
                builder.fitActions(width)
            }
        }
        content.viewTreeObserver.addOnGlobalLayoutListener(place)
        removeLayoutListener = { content.viewTreeObserver.removeOnGlobalLayoutListener(place) }
        place.onGlobalLayout()
        if (animated) {
            builder.animate().alpha(1f).translationY(0f)
                .setDuration(if (replacing) 160 else 240).setInterpolator(slideIn).start()
        }
    }

    fun hide() {
        removeLayoutListener?.invoke()
        removeLayoutListener = null
        val view = card
        card = null
        shown = null
        view?.animate()?.cancel()
        (view?.parent as? ViewGroup)?.removeView(view)
    }

    private fun dismissCard() {
        val view = card ?: return
        shown = null
        if (!view.isAttachedToWindow || !motionEnabled() || view.alpha == 0f) {
            hide()
            return
        }
        view.deactivate()
        view.animate().alpha(0f).translationY(view.dp(12).toFloat())
            .setDuration(150).setInterpolator(slideOut).withEndAction {
                if (card === view) hide()
            }.start()
    }
}

private class BannerContent(context: Context) : LinearLayout(context) {
    private val actionRow = LinearLayout(context).apply { gravity = Gravity.END }
    private var strings = context
    private var notes: ReleaseNotesScrollView? = null
    private var active = true

    init {
        id = R.id.zealot_update_banner
        orientation = VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(12))
        background = shape(color(R.color.zealot_prompt_surface), 24).apply {
            setStroke(dp(1), color(R.color.zealot_prompt_outline))
        }
        elevation = dp(5).toFloat()
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setUpdateLocale(locale: Locale?) {
        if (locale == null) return
        strings = context.withUpdateLocale(locale)
        layoutDirection = strings.resources.configuration.layoutDirection
    }

    fun deactivate() {
        active = false
        notes?.cancelMotion()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        findFocus()?.clearFocus()
    }

    fun releaseHeader(release: ReleaseInfo) {
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val info = context.applicationInfo
        if (info.icon != 0) {
            val icon = ImageView(context).apply {
                setImageDrawable(info.loadIcon(context.packageManager))
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            row.addView(icon, LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
        }
        val labels = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(heading(R.string.zealot_prompt_available))
            val bidi = BidiFormatter.getInstance(strings.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)
            val appName = bidi.unicodeWrap(info.loadLabel(context.packageManager).toString())
            val version = bidi.unicodeWrap(release.releaseVersion)
            addView(body(strings.getString(R.string.zealot_prompt_app_version, appName, version), lines = 2))
        }
        row.addView(labels, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun releaseNotes(changelog: String) {
        val panel = ReleaseNotesScrollView(context).apply {
            heightLimit = dp(152)
            visibility = GONE
            background = shape(color(R.color.zealot_prompt_notes_surface), 12)
            clipToOutline = true
            addView(body(changelog).apply {
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setLineSpacing(dp(3).toFloat(), 1f)
            })
        }
        val chevron = context.getDrawable(R.drawable.zealot_prompt_chevron)?.mutate()?.apply {
            setTint(color(R.color.zealot_prompt_accent))
        }
        val toggle = button(R.string.zealot_prompt_notes) {
            val expanded = !panel.expanded
            text = strings.getText(if (expanded) R.string.zealot_prompt_hide_notes else R.string.zealot_prompt_notes)
            panel.setExpanded(expanded) { fraction -> chevron?.level = (fraction * 10_000).roundToInt() }
        }.apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, chevron, null)
            compoundDrawablePadding = dp(6)
            setPaddingRelative(dp(8), 0, dp(10), 0)
        }
        addView(toggle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.START
        })
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        notes = panel
    }

    fun title(label: Int, busy: Boolean = false) {
        val line = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        if (busy) {
            val progress = ProgressBar(context).apply {
                indeterminateTintList = ColorStateList.valueOf(color(R.color.zealot_prompt_accent))
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            line.addView(progress, LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) })
        }
        line.addView(heading(label), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(line)
    }

    fun message(message: Int) {
        addView(body(strings.getText(message), lines = 3), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
    }

    fun button(label: Int, primary: Boolean = false, action: Button.() -> Unit): Button =
        Button(context).apply {
            text = strings.getText(label)
            isAllCaps = false
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            minHeight = dp(48)
            minimumHeight = dp(48)
            minWidth = dp(48)
            minimumWidth = dp(48)
            setPadding(dp(14), 0, dp(14), 0)
            setTextColor(color(if (primary) R.color.zealot_prompt_on_accent else R.color.zealot_prompt_accent))
            background = RippleDrawable(
                ColorStateList.valueOf(color(R.color.zealot_prompt_ripple)),
                shape(if (primary) color(R.color.zealot_prompt_accent) else 0, 24),
                shape(color(R.color.zealot_prompt_surface), 24)
            )
            stateListAnimator = null
            setOnClickListener { if (active) action() }
        }

    fun actions(vararg buttons: Button) {
        buttons.forEachIndexed { index, button ->
            actionRow.addView(button, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                if (index > 0) marginStart = dp(4)
            })
        }
        addView(actionRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
    }

    fun fitActions(width: Int) {
        val available = width - paddingLeft - paddingRight
        val unconstrained = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val total = (0 until actionRow.childCount).sumOf { index ->
            val button = actionRow.getChildAt(index)
            button.measure(unconstrained, unconstrained)
            button.measuredWidth + (button.layoutParams as LayoutParams).marginStart
        }
        actionRow.orientation = if (total > available) VERTICAL else HORIZONTAL
    }

    fun dp(value: Int): Int = (resources.displayMetrics.density * value).roundToInt()

    fun fitNotes(visibleHeight: Int) {
        notes?.let { panel ->
            val limit = (visibleHeight / 4).coerceIn(dp(48), dp(152))
            if (limit != panel.heightLimit) {
                panel.heightLimit = limit
                panel.requestLayout()
            }
        }
    }

    private fun heading(label: Int) = TextView(context).apply {
        text = strings.getText(label)
        textSize = 18f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(color(R.color.zealot_prompt_text))
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }

    private fun body(value: CharSequence, lines: Int = Int.MAX_VALUE) = TextView(context).apply {
        text = value
        textSize = 14f
        setTextColor(color(R.color.zealot_prompt_secondary))
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun shape(fill: Int, radius: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
    }

    @Suppress("DEPRECATION")
    private fun color(id: Int): Int = resources.getColor(id)
}

private class ReleaseNotesScrollView(context: Context) : ScrollView(context) {
    var heightLimit = Int.MAX_VALUE
    var expanded = false
        private set
    private var reveal = 0f
    private var motion: ValueAnimator? = null

    fun setExpanded(expanded: Boolean, onReveal: (Float) -> Unit) {
        this.expanded = expanded
        cancelMotion()
        importantForAccessibility = if (expanded) IMPORTANT_FOR_ACCESSIBILITY_AUTO
        else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

        val target = if (expanded) 1f else 0f
        if (expanded && visibility == GONE) {
            scrollTo(0, 0)
            visibility = VISIBLE
        }
        fun update(fraction: Float) {
            reveal = fraction
            alpha = fraction
            onReveal(fraction)
            requestLayout()
        }
        if (!isAttachedToWindow || !motionEnabled()) {
            update(target)
            if (!expanded) visibility = GONE
            return
        }
        motion = ValueAnimator.ofFloat(reveal, target).apply {
            duration = if (expanded) 210 else 170
            interpolator = expandEase
            addUpdateListener { update(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (motion !== animation) return
                    motion = null
                    if (!this@ReleaseNotesScrollView.expanded) visibility = GONE
                }
            })
        }.also { it.start() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val limit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            heightLimit
        } else {
            minOf(heightLimit, MeasureSpec.getSize(heightMeasureSpec))
        }
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
        setMeasuredDimension(measuredWidth, (measuredHeight * reveal).roundToInt())
    }

    override fun onDetachedFromWindow() {
        cancelMotion()
        super.onDetachedFromWindow()
    }

    fun cancelMotion() {
        val running = motion
        motion = null
        running?.cancel()
    }
}

private val slideIn = PathInterpolator(0.16f, 1f, 0.3f, 1f)
private val slideOut = PathInterpolator(0.4f, 0f, 1f, 1f)
private val expandEase = PathInterpolator(0.2f, 0f, 0f, 1f)

private fun motionEnabled(): Boolean = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()
