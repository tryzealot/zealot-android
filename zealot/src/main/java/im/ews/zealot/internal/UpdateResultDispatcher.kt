package im.ews.zealot.internal

import android.app.Activity
import android.os.Handler
import im.ews.zealot.UpdateCallback
import im.ews.zealot.UpdateDialogOptions
import im.ews.zealot.UpdatePresenter
import im.ews.zealot.UpdateResult
import im.ews.zealot.Zealot
import okhttp3.Call
import java.lang.ref.WeakReference
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Delivers results and optional UI without participating in request construction. */
internal class UpdateResultDispatcher(
    private val activityReference: WeakReference<Activity>?,
    private val mainHandler: Handler
) {
    fun dispatch(
        call: Call,
        result: UpdateResult,
        callback: UpdateCallback?,
        showDialog: Boolean,
        callbackExecutor: Executor?,
        presenter: UpdatePresenter?,
        maxHeight: Zealot.ScreenHeight,
        dialogOptions: UpdateDialogOptions,
        cancelledByUser: AtomicBoolean?
    ) {
        fun isUserCancelled(): Boolean = cancelledByUser?.get() ?: call.isCanceled()
        if (showDialog && result is UpdateResult.UpdateAvailable) {
            mainHandler.post {
                if (isUserCancelled()) return@post
                val activity = activityReference?.get() ?: return@post
                if (activity.isFinishing || activity.isDestroyed) return@post
                val release = result.release
                if (presenter != null) {
                    presenter.present(activity, release)
                } else {
                    DefaultUpdateDialog.show(
                        activity,
                        "${release.releaseVersion} (${release.buildVersion})",
                        release.changelog,
                        release.installUrl,
                        maxHeight,
                        dialogOptions
                    )
                }
            }
        }

        if (callback != null) {
            val invoked = AtomicBoolean(false)
            val callbackRejection = AtomicReference<RejectedExecutionException?>()
            val delivery = Runnable {
                if (!invoked.compareAndSet(false, true)) return@Runnable
                if (isUserCancelled()) return@Runnable
                try {
                    when (result) {
                        UpdateResult.UpToDate -> callback.onUpToDate()
                        is UpdateResult.UpdateAvailable -> callback.onUpdateAvailable(result.release)
                        is UpdateResult.Error -> callback.onError(result.error)
                    }
                } catch (error: RejectedExecutionException) {
                    callbackRejection.set(error)
                    throw error
                }
            }
            if (callbackExecutor == null) {
                mainHandler.post(delivery)
            } else {
                try {
                    callbackExecutor.execute(delivery)
                } catch (error: RejectedExecutionException) {
                    if (callbackRejection.get() === error) throw error
                    if (!invoked.get()) mainHandler.post(delivery)
                }
            }
        }
    }
}
