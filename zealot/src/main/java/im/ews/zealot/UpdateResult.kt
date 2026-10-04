package im.ews.zealot

/** The release returned by Zealot for the configured application and channel. */
data class ReleaseInfo(
    val releaseVersion: String,
    val buildVersion: String,
    val installUrl: String,
    val changelog: String
)

enum class UpdateErrorCode {
    NETWORK,
    HTTP,
    INVALID_RESPONSE
}

/** Details for a failed update check. SDK-generated errors omit response bodies and channel keys. */
data class UpdateError(
    val code: UpdateErrorCode,
    val message: String,
    val httpStatusCode: Int? = null
)

/** Callbacks run on the main thread unless [Zealot.setCallbackExecutor] is configured. */
interface UpdateCallback {
    fun onUpdateAvailable(release: ReleaseInfo)
    fun onUpToDate()
    fun onError(error: UpdateError)
}

/** One outcome of a Zealot update check. */
sealed class UpdateResult {
    /** The channel has no newer release for the installed app. */
    object UpToDate : UpdateResult()

    /** A newer release is available. */
    data class UpdateAvailable(val release: ReleaseInfo) : UpdateResult()

    /** The check failed; this is never reported as [UpToDate]. */
    data class Error(val error: UpdateError) : UpdateResult()
}

/**
 * Single-result callback for Kotlin-style state handling or Java lambdas.
 * Delivery uses the same executor as [UpdateCallback].
 */
fun interface UpdateResultCallback {
    fun onResult(result: UpdateResult)
}
