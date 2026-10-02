package im.ews.zealot.internal

import okhttp3.Call
import java.util.concurrent.atomic.AtomicBoolean

/** Distinguishes an explicit caller cancellation from OkHttp's internal timeout cancellation. */
internal class UserCancellableCall(private val delegate: Call) : Call by delegate {
    val cancelledByUser = AtomicBoolean(false)

    override fun cancel() {
        cancelledByUser.set(true)
        delegate.cancel()
    }

    override fun clone(): Call = UserCancellableCall(delegate.clone())
}
