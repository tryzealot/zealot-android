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
import im.ews.zealot.internal.apk.ApkUpdateStatus
import im.ews.zealot.internal.apk.ApkUpdateStatus.Stage
import java.lang.ref.WeakReference
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

internal sealed interface UpdateNotice {
    data class Available(
        val release: ReleaseInfo,
        val linkFailed: Boolean = false,
        val manual: Boolean = false,
        val download: Boolean = false
    ) : UpdateNotice
    data class Apk(val status: ApkUpdateStatus, val release: ReleaseInfo?) : UpdateNotice
    data class Minimized(val label: Int) : UpdateNotice
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
        dismiss: () -> Unit,
        transferAction: (ApkUpdateStatus, ReleaseInfo?) -> Unit,
        expand: () -> Unit = {}
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
        builder.populate(notice, open, later, retry, dismiss, transferAction, expand)

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
            val maxWidth = builder.dp(if (notice is UpdateNotice.Minimized) 320 else 440)
            val width = (content.width - 2 * (safeSide + margin)).coerceIn(1, maxWidth)
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

internal class BannerContent(context: Context, private val sheetColors: UpdateSheetColors? = null) : LinearLayout(context) {
    private val isSheet = sheetColors != null
    private val actionRow = LinearLayout(context).apply { gravity = Gravity.END }
    val sheetActions: View? get() = actionRow.takeIf { isSheet && it.childCount > 0 }
    private var strings = context
    private var notes: ReleaseNotesScrollView? = null
    private var downloadProgress: ProgressBar? = null
    private var percentLabel: TextView? = null
    private val percentFormat by lazy {
        val config = strings.resources.configuration
        @Suppress("DEPRECATION")
        val locale = if (Build.VERSION.SDK_INT >= 24) config.locales[0] else config.locale
        NumberFormat.getPercentInstance(locale)
    }
    private var active = true

    init {
        id = R.id.zealot_update_banner
        orientation = VERTICAL
        val inset = dp(if (isSheet) 24 else 16)
        setPadding(inset, inset, inset, dp(12))
        if (!isSheet) {
            background = shape(color(R.color.zealot_prompt_surface), 24).apply {
                setStroke(dp(1), color(R.color.zealot_prompt_outline))
            }
            elevation = dp(5).toFloat()
        }
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun populate(
        notice: UpdateNotice,
        open: (ReleaseInfo) -> Unit,
        later: (ReleaseInfo) -> Unit,
        retry: () -> Unit,
        dismiss: () -> Unit,
        transferAction: (ApkUpdateStatus, ReleaseInfo?) -> Unit,
        expand: () -> Unit = {},
        minimize: (() -> Unit)? = null
    ) {
        when (notice) {
            is UpdateNotice.Available -> {
                val release = notice.release
                releaseHeader(release)
                if (notice.linkFailed) message(R.string.zealot_prompt_open_failed)
                if (release.changelog.isNotBlank()) {
                    if (isSheet) sheetNotes(release.changelog) else releaseNotes(release.changelog)
                }
                actions(
                    button(R.string.zealot_update_later) { later(release) },
                    button(
                        if (notice.download) R.string.zealot_apk_download else R.string.zealot_prompt_open,
                        primary = true
                    ) { open(release) }
                )
            }
            is UpdateNotice.Apk -> transfer(notice, dismiss, transferAction, minimize)
            is UpdateNotice.Minimized -> {
                // Keep the entire action reachable at large font sizes.
                setPadding(dp(4), dp(2), dp(4), dp(2))
                addView(button(notice.label) { expand() }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
            UpdateNotice.Checking -> title(R.string.zealot_prompt_checking, busy = true)
            UpdateNotice.Current -> {
                title(R.string.zealot_prompt_current)
                actions(button(R.string.zealot_prompt_dismiss) { dismiss() })
            }
            UpdateNotice.CheckFailed -> {
                title(R.string.zealot_prompt_check_failed)
                message(R.string.zealot_prompt_check_hint)
                actions(
                    button(R.string.zealot_prompt_dismiss) { dismiss() },
                    button(R.string.zealot_prompt_retry, primary = true) { retry() }
                )
            }
        }
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
        if (isSheet) {
            actionRow.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            actionRow.descendantFocusability = FOCUS_BLOCK_DESCENDANTS
            actionRow.findFocus()?.clearFocus()
        }
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
            val size = dp(if (isSheet) 52 else 44)
            row.addView(icon, LayoutParams(size, size).apply { marginEnd = dp(12) })
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

    private fun sheetNotes(changelog: String) {
        val panel = LinearLayout(context).apply {
            orientation = VERTICAL
            background = shape(color(R.color.zealot_prompt_notes_surface), 16)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(body(strings.getText(R.string.zealot_prompt_notes)).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(color(R.color.zealot_prompt_text))
            })
            addView(body(changelog).apply {
                ellipsize = null
                setLineSpacing(dp(3).toFloat(), 1f)
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(20)
            bottomMargin = dp(8)
        })
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
        addView(body(strings.getText(message)), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
    }

    fun transfer(
        notice: UpdateNotice.Apk,
        dismiss: () -> Unit,
        action: (ApkUpdateStatus, ReleaseInfo?) -> Unit,
        minimize: (() -> Unit)?
    ) {
        val status = notice.status
        fun perform() = action(status, notice.release)
        when (status.stage) {
            Stage.QUEUED, Stage.DOWNLOADING, Stage.VERIFYING -> {
                val label = when (status.stage) {
                    Stage.QUEUED -> R.string.zealot_apk_queued
                    Stage.VERIFYING -> R.string.zealot_apk_verifying
                    else -> R.string.zealot_apk_downloading
                }
                val header = LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(heading(label), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                    if (status.stage == Stage.DOWNLOADING) {
                        percentLabel = body("", lines = 1).apply {
                            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                            addView(this, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                                marginStart = dp(12)
                            })
                        }
                    }
                }
                addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                downloadProgress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    progressTintList = ColorStateList.valueOf(color(R.color.zealot_prompt_accent))
                    if (isSheet) progressBackgroundTintList = ColorStateList.valueOf(color(R.color.zealot_prompt_outline))
                    indeterminateTintList = progressTintList
                    addView(this, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(8)
                    })
                }
                setDownloadProgress(status.percent)
                val cancel = button(R.string.zealot_apk_cancel) { perform() }
                if (minimize == null) actions(cancel) else actions(
                    cancel,
                    button(R.string.zealot_apk_background_action, primary = true) { minimize() }
                )
            }
            Stage.READY -> {
                title(R.string.zealot_apk_ready)
                message(when (status.reason) {
                    "storage" -> R.string.zealot_apk_storage
                    "installer", "settings" -> R.string.zealot_apk_install_failed
                    "permission" -> R.string.zealot_apk_permission
                    "blocked" -> R.string.zealot_apk_blocked
                    else -> R.string.zealot_apk_install_hint
                })
                val actionLabel = if (status.reason == "permission") R.string.zealot_apk_allow else R.string.zealot_apk_install
                val install = button(actionLabel, primary = true) { perform() }
                if (minimize == null) actions(install) else actions(
                    button(R.string.zealot_apk_minimize) { minimize() },
                    install
                )
            }
            Stage.INSTALLING -> title(R.string.zealot_apk_installing, busy = true)
            Stage.FAILED -> {
                title(R.string.zealot_apk_failed)
                message(when (status.reason) {
                    "browser" -> R.string.zealot_apk_browser
                    "invalid", "version" -> R.string.zealot_apk_invalid
                    "storage" -> R.string.zealot_apk_storage
                    else -> R.string.zealot_prompt_check_hint
                })
                actions(
                    button(R.string.zealot_prompt_dismiss) { dismiss() },
                    button(
                        if (status.reason == "browser" && notice.release != null) R.string.zealot_prompt_open
                        else R.string.zealot_prompt_retry, primary = true
                    ) { perform() }
                )
            }
        }
    }

    fun setDownloadProgress(percent: Int) {
        downloadProgress?.apply {
            if (isIndeterminate != (percent < 0)) isIndeterminate = percent < 0
            if (percent >= 0) {
                if (Build.VERSION.SDK_INT >= 24 && motionEnabled()) setProgress(percent, true)
                else progress = percent
            }
        }
        percentLabel?.apply {
            visibility = if (percent < 0) GONE else VISIBLE
            if (percent >= 0) text = percentFormat.format(percent / 100.0)
        }
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
        if (isSheet) actionRow.setPadding(paddingLeft, dp(4), paddingRight, dp(16))
        else addView(actionRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
    }

    fun fitActions(width: Int) {
        val available = width - paddingLeft - paddingRight
        val unconstrained = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val total = (0 until actionRow.childCount).sumOf { index ->
            val button = actionRow.getChildAt(index)
            button.measure(unconstrained, unconstrained)
            button.measuredWidth + if (index > 0) dp(4) else 0
        }
        val stacked = total > available
        if (stacked == (actionRow.orientation == VERTICAL)) return
        actionRow.orientation = if (stacked) VERTICAL else HORIZONTAL
        for (index in 0 until actionRow.childCount) {
            val button = actionRow.getChildAt(index)
            button.layoutParams = (button.layoutParams as LayoutParams).apply {
                this.width = if (stacked && isSheet) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT
                marginStart = if (!stacked && index > 0) dp(4) else 0
            }
        }
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
        textSize = if (isSheet) 22f else 18f
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
    private fun color(id: Int): Int = sheetColors?.color(id) ?: resources.getColor(id)
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

internal fun motionEnabled(): Boolean = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()
