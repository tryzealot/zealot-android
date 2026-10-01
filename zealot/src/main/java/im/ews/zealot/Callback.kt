package im.ews.zealot

import im.ews.zealot.internal.ReleaseResponseParser
import okhttp3.Call
import okhttp3.Response
import org.json.JSONException
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
        val result = try {
            response.use { closedResponse ->
                if (!closedResponse.isSuccessful) {
                    UpdateResult.Error(
                        UpdateError(
                            code = UpdateErrorCode.HTTP,
                            message = "Zealot returned HTTP ${closedResponse.code}",
                            httpStatusCode = closedResponse.code
                        )
                    )
                } else {
                    val body = closedResponse.body
                        ?: throw JSONException("Response body is empty")
                    val source = body.source()
                    source.request(MAX_RESPONSE_BYTES + 1L)
                    if (source.buffer.size > MAX_RESPONSE_BYTES) {
                        throw JSONException("Zealot response exceeds 1 MiB")
                    }
                    val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                    ReleaseResponseParser.parse(source.buffer.clone().readString(charset))
                }
            }
        } catch (_: IOException) {
            UpdateResult.Error(UpdateError(UpdateErrorCode.NETWORK, "Could not read the Zealot response"))
        } catch (_: JSONException) {
            invalidResponse()
        } catch (_: IllegalArgumentException) {
            invalidResponse()
        }

        zealot.dispatchResult(
            call, result, updateCallback, showDialog, callbackExecutor, presenter, maxHeight
        )
    }

    override fun onFailure(call: Call, e: IOException) {
        if (call.isCanceled()) return
        zealot.dispatchResult(
            call,
            UpdateResult.Error(UpdateError(UpdateErrorCode.NETWORK, "Could not connect to Zealot")),
            updateCallback,
            showDialog,
            callbackExecutor,
            presenter,
            maxHeight
        )
    }

    private fun invalidResponse(): UpdateResult.Error = UpdateResult.Error(
        UpdateError(
            code = UpdateErrorCode.INVALID_RESPONSE,
            message = "Could not parse the Zealot response"
        )
    )

    private companion object {
        const val MAX_RESPONSE_BYTES = 1024L * 1024L
    }
}
