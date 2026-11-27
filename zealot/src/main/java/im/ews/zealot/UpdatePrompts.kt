package im.ews.zealot

import android.app.Activity
import android.app.Application
import android.graphics.Typeface
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.ViewTreeObserver
import im.ews.zealot.internal.UpdateNotice
import im.ews.zealot.internal.UpdatePromptBanner
import im.ews.zealot.internal.UpdatePromptSheet
import im.ews.zealot.internal.apk.ApkUpdateStatus
import im.ews.zealot.internal.apk.ApkUpdates
import im.ews.zealot.internal.openUpdateLink
import okhttp3.Call
import okhttp3.HttpUrl
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit

/** Automatic update prompts for one Activity. Call its methods on the main thread. */
class UpdatePrompts private constructor(
    activity: Activity,
    private val channel: Channel,
    locale: Locale?,
    downloadUpdates: Boolean,
    sheetTheme: Int,
    sheetTypeface: Typeface?,
    private val startCheck: (UpdateResultCallback) -> Call
) : AutoCloseable {
    private data class Channel(val endpoint: HttpUrl, val key: String)
    private enum class RetryAction { DOWNLOAD, BROWSER }

    companion object {
        private val checkIntervalMillis = TimeUnit.HOURS.toMillis(1)
        private val deferredByApplication = WeakHashMap<Application, MutableSet<Pair<Channel, String>>>()
        private val minimizedByApplication = WeakHashMap<Application, MutableMap<Channel, UUID>>()

        @JvmSynthetic
        internal fun attach(
            activity: Activity,
            endpoint: HttpUrl,
            channelKey: String,
            locale: Locale?,
            downloadUpdates: Boolean,
            sheetTheme: Int,
            sheetTypeface: Typeface?,
            startCheck: (UpdateResultCallback) -> Call
        ): UpdatePrompts {
            check(!activity.isFinishing && !activity.isDestroyed) { "update prompts require a live Activity" }
            val window = activity.window.decorView
            (window.getTag(R.id.zealot_update_prompts) as? UpdatePrompts)?.let { return it }

            val prompts = UpdatePrompts(
                activity, Channel(endpoint, channelKey), locale, downloadUpdates, sheetTheme, sheetTypeface, startCheck
            )
            window.setTag(R.id.zealot_update_prompts, prompts)
            activity.application.registerActivityLifecycleCallbacks(prompts.lifecycle)
            window.viewTreeObserver.addOnWindowFocusChangeListener(prompts.focusListener)
            prompts.apkUpdates?.observe()
            prompts.refresh()
            return prompts
        }
    }

    private val activityReference = WeakReference(activity)
    private val application = activity.application
    private val deferredReleases = deferredByApplication.getOrPut(application) { mutableSetOf() }
    private val minimizedJobs = minimizedByApplication.getOrPut(application) { mutableMapOf() }
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
    private var retryAction: RetryAction? = null
    private val apkUpdates = if (downloadUpdates && ApkUpdates.supported(activity)) {
        ApkUpdates(application, channel.endpoint, channel.key, locale, ::refresh)
    } else null
    private val sheet = apkUpdates?.let { UpdatePromptSheet(activity, locale, sheetTheme, sheetTypeface) }

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
            sheet?.hide()
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
        minimizedJobs.remove(channel)
        manual = true
        refresh()
    }

    /** Cancels requests and removes the prompts. Safe to call more than once. */
    override fun close() {
        requireMainThread()
        if (closed) return
        closed = true
        cancelRequest()
        apkUpdates?.close()
        sheet?.hide()
        banner.hide()
        application.unregisterActivityLifecycleCallbacks(lifecycle)
        activityReference.get()?.window?.decorView?.let { window ->
            window.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
            window.setTag(R.id.zealot_update_prompts, null)
        }
    }

    private fun refresh() {
        val activity = activityReference.get() ?: return
        if (closed || resumed != true || (!focused && sheet?.isShowing != true) ||
            activity.isFinishing || activity.isDestroyed
        ) return
        val elapsed = lastSuccessfulCheck?.let { SystemClock.elapsedRealtime() - it }
        if (call == null && (manual || !attempted && (elapsed == null || elapsed >= checkIntervalMillis))) {
            attempted = true
            call = startCheck(UpdateResultCallback(::receive))
        }
        val current = notice
        val transfer = apkUpdates?.status()
        val minimized = minimizedJobs[channel]?.let { it == apkUpdates?.workId } == true
        val visible = when {
            transfer != null && minimized -> UpdateNotice.Minimized(when (transfer.stage) {
                ApkUpdateStatus.Stage.READY -> R.string.zealot_apk_ready
                ApkUpdateStatus.Stage.FAILED -> R.string.zealot_apk_failed
                ApkUpdateStatus.Stage.INSTALLING -> R.string.zealot_apk_installing
                else -> R.string.zealot_apk_view_download
            })
            minimized -> null
            transfer != null && !(transfer.stage == ApkUpdateStatus.Stage.FAILED && manual && call != null) ->
                UpdateNotice.Apk(transfer, (current as? UpdateNotice.Available)?.release)
            manual && call != null -> UpdateNotice.Checking
            current is UpdateNotice.Available && !current.manual && isDeferred(current.release) -> null
            current is UpdateNotice.Available -> current.copy(download = apkUpdates?.canDownload(current.release) == true)
            else -> current
        }
        render(visible)
        apkUpdates?.launchConfirmation(activity)
    }

    private fun render(visible: UpdateNotice?) {
        if (sheet != null && (visible is UpdateNotice.Apk || visible is UpdateNotice.Available)) {
            banner.hide()
            sheet.render(visible, ::update, ::later, ::checkNow, ::dismiss, ::transferAction, ::minimize, ::cancelSheet)
        } else {
            sheet?.hide(animated = true)
            banner.render(visible, ::update, ::later, ::checkNow, ::dismiss, ::transferAction, ::expand)
        }
    }

    private fun minimize() {
        apkUpdates?.workId?.let { minimizedJobs[channel] = it }
        refresh()
    }

    private fun expand() {
        minimizedJobs.remove(channel)
        refresh()
    }

    private fun cancelSheet(visible: UpdateNotice) {
        when (visible) {
            is UpdateNotice.Available -> later(visible.release)
            is UpdateNotice.Apk -> if (visible.status.stage == ApkUpdateStatus.Stage.FAILED) dismiss() else minimize()
            else -> Unit
        }
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
        val action = retryAction
        retryAction = null
        if (result is UpdateResult.UpdateAvailable) {
            when (action) {
                RetryAction.DOWNLOAD -> update(result.release)
                RetryAction.BROWSER -> open(result.release)
                null -> Unit
            }
        }
        refresh()
    }

    private fun isDeferred(release: ReleaseInfo): Boolean = channel to release.buildVersion in deferredReleases

    private fun later(release: ReleaseInfo) {
        deferredReleases.add(channel to release.buildVersion)
        dismiss()
    }

    private fun update(release: ReleaseInfo) {
        val downloads = apkUpdates
        if (downloads?.canDownload(release) == true) {
            minimizedJobs.remove(channel)
            downloads.download(release)
        } else open(release)
    }

    private fun transferAction(status: ApkUpdateStatus, release: ReleaseInfo?) {
        val downloads = apkUpdates ?: return
        val activity = activityReference.get() ?: return
        when (status.stage) {
            ApkUpdateStatus.Stage.QUEUED, ApkUpdateStatus.Stage.DOWNLOADING, ApkUpdateStatus.Stage.VERIFYING -> downloads.cancel()
            ApkUpdateStatus.Stage.READY -> downloads.install(activity)
            ApkUpdateStatus.Stage.FAILED -> {
                downloads.hideFailure()
                if (status.reason == "browser" && release != null) open(release)
                else {
                    retryAction = if (status.reason == "browser") RetryAction.BROWSER else RetryAction.DOWNLOAD
                    checkNow()
                }
            }
            ApkUpdateStatus.Stage.INSTALLING -> Unit
        }
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
        apkUpdates?.hideFailure()
        notice = null
        refresh()
    }

    private fun cancelRequest() {
        call?.cancel()
        call = null
        manual = false
        retryAction = null
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "use update prompts on the main thread" }
    }
}
