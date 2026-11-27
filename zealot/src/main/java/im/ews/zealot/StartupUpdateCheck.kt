package im.ews.zealot

import android.os.Looper
import okhttp3.Call

/**
 * Checks for an update once per Activity instance. Keep one check on the Activity and forward
 * its onPostResume and onPause events. A cancelled or failed check can retry on the next resume.
 */
class StartupUpdateCheck private constructor(
    private val callback: UpdateResultCallback?,
    private val start: (UpdateResultCallback) -> Call
) {
    companion object {
        @JvmSynthetic
        internal fun create(
            callback: UpdateResultCallback?,
            start: (UpdateResultCallback) -> Call
        ): StartupUpdateCheck = StartupUpdateCheck(callback, start)
    }

    private var activeCall: Call? = null
    private var resumed = false
    private var completed = false

    /** Call from the Activity's onPostResume on the main thread. */
    fun onResume() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "call onResume on the main thread" }
        if (resumed || completed) return

        activeCall = start(UpdateResultCallback { result ->
            activeCall = null
            completed = result !is UpdateResult.Error
            callback?.onResult(result)
        })
        resumed = true
    }

    /** Call from the Activity's onPause on the main thread to cancel pending results and UI. */
    fun onPause() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "call onPause on the main thread" }
        resumed = false
        activeCall?.cancel()
        activeCall = null
    }
}
