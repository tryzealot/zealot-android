package im.ews.zealot.internal

import im.ews.zealot.UpdateError
import im.ews.zealot.UpdateErrorCode
import im.ews.zealot.UpdateResult
import okhttp3.Response
import org.json.JSONException
import java.io.IOException

/** Converts one HTTP response into an update result without touching Android UI or callbacks. */
internal object UpdateResponseReader {
    private const val MAX_RESPONSE_BYTES = 1024L * 1024L

    fun read(response: Response): UpdateResult = try {
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

    fun connectionFailure(): UpdateResult.Error =
        UpdateResult.Error(UpdateError(UpdateErrorCode.NETWORK, "Could not connect to Zealot"))

    private fun invalidResponse(): UpdateResult.Error = UpdateResult.Error(
        UpdateError(
            code = UpdateErrorCode.INVALID_RESPONSE,
            message = "Could not parse the Zealot response"
        )
    )
}
