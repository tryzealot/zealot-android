package im.ews.zealot

import im.ews.zealot.internal.UpdateResponseReader
import okhttp3.Call
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.Executor

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

    @JvmSynthetic
    internal fun configure(
        updateCallback: UpdateCallback?,
        showDialog: Boolean,
        callbackExecutor: Executor?,
        presenter: UpdatePresenter?,
        maxHeight: Zealot.ScreenHeight
    ) {
        this.updateCallback = updateCallback
        this.showDialog = showDialog
        this.callbackExecutor = callbackExecutor
        this.presenter = presenter
        this.maxHeight = maxHeight
    }

    override fun onResponse(call: Call, response: Response) {
        val result = UpdateResponseReader.read(response)
        zealot.dispatchResult(
            call, result, updateCallback, showDialog, callbackExecutor, presenter, maxHeight
        )
    }

    override fun onFailure(call: Call, e: IOException) {
        if (call.isCanceled()) return
        zealot.dispatchResult(
            call,
            UpdateResponseReader.connectionFailure(),
            updateCallback,
            showDialog,
            callbackExecutor,
            presenter,
            maxHeight
        )
    }
}
