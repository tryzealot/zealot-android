package im.ews.zealot.internal

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.Insets
import androidx.core.view.OneShotPreDrawListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import im.ews.zealot.R
import im.ews.zealot.ReleaseInfo
import im.ews.zealot.internal.apk.ApkUpdateStatus
import java.lang.ref.WeakReference
import java.util.Locale

internal class UpdatePromptSheet(activity: Activity, private val locale: Locale?, private val theme: Int = 0) {
    private val owner = WeakReference(activity)
    private var dialog: Dialog? = null
    private var viewport: FrameLayout? = null
    private var scrim: View? = null
    private var panel: SheetLayout? = null
    private var content: BannerContent? = null
    private var shown: UpdateNotice? = null
    private var closing = false
    private var safeInsets = Insets.NONE

    // The Activity loses window focus while the sheet is open, including during its exit.
    val isShowing: Boolean get() = dialog?.isShowing == true

    fun render(
        notice: UpdateNotice,
        open: (ReleaseInfo) -> Unit,
        later: (ReleaseInfo) -> Unit,
        retry: () -> Unit,
        dismiss: () -> Unit,
        transferAction: (ApkUpdateStatus, ReleaseInfo?) -> Unit,
        minimize: () -> Unit,
        cancel: (UpdateNotice) -> Unit
    ) {
        if (!closing && isShowing && notice == shown) return
        val old = shown
        if (!closing && isShowing && old is UpdateNotice.Apk && notice is UpdateNotice.Apk &&
            old.status.stage == ApkUpdateStatus.Stage.DOWNLOADING && notice.status.stage == old.status.stage
        ) {
            content?.setDownloadProgress(notice.status.percent)
            shown = notice
            return
        }
        val activity = owner.get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val colors = UpdateSheetColors(activity, theme)
        val body = BannerContent(activity, colors).apply {
            setUpdateLocale(locale)
            populate(notice, open, later, retry, dismiss, transferAction, minimize = minimize)
        }
        val surface = colors.color(R.color.zealot_prompt_surface)
        val newPanel = SheetLayout(activity, body, surface)
        val previous = panel
        val restoring = closing || previous?.isLaidOut != true || scrim?.alpha == 0f
        closing = false
        previous?.animate()?.cancel()
        content?.deactivate()
        content = body
        panel = newPanel
        shown = notice

        if (dialog == null) {
            val shade = View(activity).apply { setBackgroundColor(0x70000000) }
            scrim = shade
            val root = FrameLayout(activity).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                addView(shade, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
            val next = object : Dialog(activity, R.style.Zealot_UpdateSheet) {
                override fun cancel() {
                    if (!closing && dialog === this) shown?.let(cancel)
                }
            }
            shade.setOnClickListener { next.cancel() }
            shade.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            shade.isFocusable = false
            viewport = FrameLayout(activity).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                // Empty space is part of the scrim. Only the sheet itself consumes taps.
                ViewCompat.getRootWindowInsets(activity.window.decorView)?.let { applySafeInsets(this, it) }
                ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                    applySafeInsets(view, insets)
                    insets
                }
            }.also { root.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
            next.setContentView(root)
            next.window?.let { configureWindow(it, activity.window, surface) }
            dialog = next
            updatePanel(previous, newPanel)
            next.show()
            next.window?.setLayout(MATCH_PARENT, MATCH_PARENT)
            viewport?.let(ViewCompat::requestApplyInsets)
        } else {
            updatePanel(previous, newPanel)
        }

        if (motionEnabled()) {
            val shade = scrim
            if (restoring) {
                shade?.animate()?.cancel()
                shade?.alpha = 0f
                newPanel.alpha = 0f
                OneShotPreDrawListener.add(newPanel) {
                    if (panel !== newPanel || closing) return@add
                    newPanel.translationY = newPanel.height.toFloat()
                    newPanel.alpha = 1f
                    newPanel.animate().translationY(0f).setDuration(260).setInterpolator(enter).start()
                    shade?.animate()?.alpha(1f)?.setDuration(220)?.start()
                }
            } else {
                newPanel.alpha = 0.86f
                newPanel.animate().alpha(1f).setDuration(150).start()
            }
        }
    }

    private fun applySafeInsets(view: View, insets: WindowInsetsCompat) {
        safeInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
            WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
        view.setPadding(0, safeInsets.top, 0, 0)
        panel?.applyContentInsets(safeInsets)
    }

    fun hide(animated: Boolean = false) {
        val current = dialog ?: return
        val currentPanel = panel
        val shade = scrim
        if (animated && closing) return
        content?.deactivate()
        currentPanel?.animate()?.cancel()
        shade?.animate()?.cancel()
        if (animated && currentPanel != null && currentPanel.isLaidOut && motionEnabled()) {
            closing = true
            shade?.animate()?.alpha(0f)?.setDuration(160)?.start()
            currentPanel.animate().translationY(currentPanel.height.toFloat()).setDuration(190)
                .setInterpolator(exit).withEndAction {
                    if (closing && dialog === current) hide()
                }.start()
            return
        }

        closing = false
        dialog = null
        shown = null
        content = null
        panel = null
        scrim = null
        viewport = null
        safeInsets = Insets.NONE
        current.dismiss()
    }

    private fun updatePanel(previous: SheetLayout?, next: SheetLayout) {
        viewport?.apply {
            if (previous != null) removeView(previous)
            next.applyContentInsets(safeInsets)
            addView(next, FrameLayout.LayoutParams(
                MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ))
        }
    }

    @Suppress("DEPRECATION")
    private fun configureWindow(window: Window, ownerWindow: Window, surface: Int) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.setWindowAnimations(0)
        window.statusBarColor = Color.TRANSPARENT
        // Dark icons have higher contrast against colors above this luminance.
        val lightSurface = ColorUtils.calculateLuminance(surface) > 0.179
        // Android 5–7.1 only supports light navigation icons.
        window.navigationBarColor = if (Build.VERSION.SDK_INT < 26 && lightSurface) 0x80000000.toInt()
        else Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        val original = WindowCompat.getInsetsController(ownerWindow, ownerWindow.decorView)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = original.isAppearanceLightStatusBars
            isAppearanceLightNavigationBars = lightSurface
        }
    }

    private companion object {
        val enter = PathInterpolator(0.16f, 1f, 0.3f, 1f)
        val exit = PathInterpolator(0.4f, 0f, 1f, 1f)
    }
}

private class SheetLayout(context: Activity, private val body: BannerContent, surface: Int) : ScrollView(context) {
    private val notesViewport = NotesViewport(context)
    private val actions = body.sheetActions
    private var contentInsets = Insets.NONE

    init {
        isClickable = true
        isVerticalScrollBarEnabled = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        val radius = body.dp(28).toFloat()
        background = GradientDrawable().apply {
            setColor(surface)
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        }
        clipToOutline = true
        notesViewport.addView(body, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(notesViewport, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            actions?.let { addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
        }
        addView(column, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    fun applyContentInsets(insets: Insets) {
        if (contentInsets == insets) return
        contentInsets = insets
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val windowWidth = MeasureSpec.getSize(widthMeasureSpec)
        val windowHeight = MeasureSpec.getSize(heightMeasureSpec)
        val maxWidth = body.dp(560)
        // Use the full surface if centering a capped sheet would leave a gap at a system edge.
        val spansWindow = windowWidth <= maxWidth + 2 * maxOf(contentInsets.left, contentInsets.right)
        val width = if (spansWindow) windowWidth else maxWidth
        val left = if (spansWindow) contentInsets.left else 0
        val right = if (spansWindow) contentInsets.right else 0
        val bottom = contentInsets.bottom.coerceAtMost(windowHeight)
        if (paddingLeft != left || paddingRight != right || paddingBottom != bottom) {
            setPadding(left, 0, right, bottom)
        }
        val contentWidth = (width - left - right).coerceAtLeast(0)
        val safeHeight = windowHeight - bottom
        val widthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        body.fitActions(contentWidth)
        actions?.measure(
            MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        val notesHeight = safeHeight - (actions?.measuredHeight ?: 0)
        // In a very short window, scroll the whole sheet so both the notes and buttons stay reachable.
        notesViewport.heightLimit = notesHeight.takeIf { it >= body.dp(72) }
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(windowHeight, MeasureSpec.AT_MOST))
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
        notesViewport.heightLimit == null && super.onInterceptTouchEvent(event)

    override fun onTouchEvent(event: MotionEvent): Boolean =
        if (notesViewport.heightLimit == null) super.onTouchEvent(event) else true
}

private class NotesViewport(context: Activity) : ScrollView(context) {
    var heightLimit: Int? = null
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = heightLimit?.let { MeasureSpec.makeMeasureSpec(it, MeasureSpec.AT_MOST) } ?: heightMeasureSpec
        super.onMeasure(widthMeasureSpec, height)
    }
}
