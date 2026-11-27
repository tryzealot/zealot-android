package im.ews.zealot

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.ViewTreeObserver
import im.ews.zealot.internal.UpdateNotice
import im.ews.zealot.internal.UpdatePromptBanner
import im.ews.zealot.internal.openUpdateLink
import okhttp3.Call
import okhttp3.HttpUrl
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit

/** Automatic, non-modal prompts for one Activity. Call its methods on the main thread. */
class UpdatePrompts private constructor(
    activity: Activity,
    private val channel: Channel,
    locale: Locale?,
    private val startCheck: (UpdateResultCallback) -> Call
) : AutoCloseable {
    private data class Channel(val endpoint: HttpUrl, val key: String)

    companion object {
        private val checkIntervalMillis = TimeUnit.HOURS.toMillis(1)
        private val deferredByApplication = WeakHashMap<Application, MutableSet<Pair<Channel, String>>>()

        @JvmSynthetic
        internal fun attach(
            activity: Activity,
            endpoint: HttpUrl,
            channelKey: String,
            locale: Locale?,
            startCheck: (UpdateResultCallback) -> Call
        ): UpdatePrompts {
            check(!activity.isFinishing && !activity.isDestroyed) { "update prompts require a live Activity" }
            val window = activity.window.decorView
            (window.getTag(R.id.zealot_update_prompts) as? UpdatePrompts)?.let { return it }

            val prompts = UpdatePrompts(activity, Channel(endpoint, channelKey), locale, startCheck)
            window.setTag(R.id.zealot_update_prompts, prompts)
            activity.application.registerActivityLifecycleCallbacks(prompts.lifecycle)
            window.viewTreeObserver.addOnWindowFocusChangeListener(prompts.focusListener)
            prompts.refresh()
            return prompts
        }
    }

    private val activityReference = WeakReference(activity)
    private val application = activity.application
    private val deferredReleases = deferredByApplication.getOrPut(application) { mutableSetOf() }
    private val banner = UpdatePromptBanner(activity, locale)
    private var focused = activity.hasWindowFocus()
    private var resumed = true.takeIf { focused }
    private var closed = false
    private var attempted = false
    private var lastSuccessfulCheck: Long? = null
    private var manual = false
    private var call: Call? = null
    private var openedBuild: String? = null
    private var notice: UpdateNotice? = null

    private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
        focused = hasFocus
        if (hasFocus) {
            if (resumed == null) resumed = true
            refresh()
        }
    }

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            if (activity !== activityReference.get()) return
            resumed = true
            focused = activity.hasWindowFocus()
            refresh()
        }

        override fun onActivityPaused(activity: Activity) {
            if (activity !== activityReference.get()) return
            resumed = false
            focused = false
            attempted = false
            cancelRequest()
            banner.hide()
        }

        override fun onActivityDestroyed(activity: Activity) {
            if (activity === activityReference.get()) close()
        }

        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    }

    /** Checks again with visible feedback, including for a release previously deferred with Later. */
    fun checkNow() {
        requireMainThread()
        if (closed) return
        manual = true
        refresh()
    }

    /** Cancels requests and removes the prompts. Safe to call more than once. */
    override fun close() {
        requireMainThread()
        if (closed) return
        closed = true
        cancelRequest()
        banner.hide()
        application.unregisterActivityLifecycleCallbacks(lifecycle)
        activityReference.get()?.window?.decorView?.let { window ->
            window.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
            window.setTag(R.id.zealot_update_prompts, null)
        }
    }

    private fun refresh() {
        val activity = activityReference.get() ?: return
        if (closed || resumed != true || !focused || activity.isFinishing || activity.isDestroyed) return
        val elapsed = lastSuccessfulCheck?.let { SystemClock.elapsedRealtime() - it }
        if (call == null && (manual || !attempted && (elapsed == null || elapsed >= checkIntervalMillis))) {
            attempted = true
            call = startCheck(UpdateResultCallback(::receive))
        }
        val current = notice
        val visible = when {
            manual && call != null -> UpdateNotice.Checking
            current is UpdateNotice.Available && !current.manual && isDeferred(current.release) -> null
            else -> current
        }
        banner.render(visible, ::open, ::later, ::checkNow, ::dismiss)
    }

    private fun receive(result: UpdateResult) {
        call = null
        if (result !is UpdateResult.Error) lastSuccessfulCheck = SystemClock.elapsedRealtime()
        notice = when (result) {
            is UpdateResult.UpdateAvailable -> result.release
                .takeIf { manual || (it.buildVersion != openedBuild && !isDeferred(it)) }
                ?.let { UpdateNotice.Available(it, manual = manual) }
            UpdateResult.UpToDate -> UpdateNotice.Current.takeIf { manual }
            is UpdateResult.Error -> UpdateNotice.CheckFailed.takeIf { manual }
        }
        manual = false
        refresh()
    }

    private fun isDeferred(release: ReleaseInfo): Boolean = channel to release.buildVersion in deferredReleases

    private fun later(release: ReleaseInfo) {
        deferredReleases.add(channel to release.buildVersion)
        dismiss()
    }

    private fun open(release: ReleaseInfo) {
        val activity = activityReference.get() ?: return
        val current = notice as? UpdateNotice.Available ?: return
        if (openUpdateLink(activity, release.installUrl)) {
            openedBuild = release.buildVersion
            lastSuccessfulCheck = null
            notice = null
        } else {
            notice = current.copy(linkFailed = true)
        }
        refresh()
    }

    private fun dismiss() {
        notice = null
        refresh()
    }

    private fun cancelRequest() {
        call?.cancel()
        call = null
        manual = false
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "use update prompts on the main thread" }
    }
}
