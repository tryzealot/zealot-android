package im.ews.zealot

import im.ews.zealot.internal.UpdateResponseReader
import okhttp3.Call
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kept public for source and binary compatibility with the original SDK.
 * New integrations should use [Zealot.checkForUpdate].
 */
class Callback(val zealot: Zealot) : okhttp3.Callback {
    private var updateCallback: UpdateCallback? = null
    private var showDialog = true
    private var callbackExecutor: Executor? = null
    private var presenter: UpdatePresenter? = null
    private var maxHeight: Zealot.ScreenHeight? = null
    private var dialogOptions = UpdateDialogOptions()
    private var cancelledByUser: AtomicBoolean? = null

    @JvmSynthetic
    internal fun configure(
        updateCallback: UpdateCallback?,
        showDialog: Boolean,
        callbackExecutor: Executor?,
        presenter: UpdatePresenter?,
        maxHeight: Zealot.ScreenHeight,
        dialogOptions: UpdateDialogOptions = UpdateDialogOptions(),
        cancelledByUser: AtomicBoolean? = null
    ) {
        this.updateCallback = updateCallback
        this.showDialog = showDialog
        this.callbackExecutor = callbackExecutor
        this.presenter = presenter
        this.maxHeight = maxHeight
        this.dialogOptions = dialogOptions
        this.cancelledByUser = cancelledByUser
    }

    override fun onResponse(call: Call, response: Response) {
        if (isUserCancelled(call)) {
            response.close()
            return
        }
        val result = UpdateResponseReader.read(
            response,
            expectedBundleId = call.request().url.queryParameter("bundle_id")
        )
        zealot.dispatchResult(
            call, result, updateCallback, showDialog, callbackExecutor, presenter, maxHeight,
            dialogOptions,
            cancelledByUser
        )
    }

    override fun onFailure(call: Call, e: IOException) {
        if (isUserCancelled(call)) return
        zealot.dispatchResult(
            call,
            UpdateResponseReader.connectionFailure(),
            updateCallback,
            showDialog,
            callbackExecutor,
            presenter,
            maxHeight,
            dialogOptions,
            cancelledByUser
        )
    }

    private fun isUserCancelled(call: Call): Boolean = cancelledByUser?.get() ?: call.isCanceled()
}
