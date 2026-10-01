package im.ews.zealot.internal

import okhttp3.HttpUrl
import okhttp3.Request

internal data class InstalledAppVersion(
    val packageName: String,
    val releaseVersion: String,
    val buildVersion: Long
)

internal object UpdateRequestFactory {
    fun create(
        endpoint: HttpUrl,
        channelKey: String,
        installed: InstalledAppVersion,
        sdkVersion: String
    ): Request {
        val url = endpoint.newBuilder()
            .addPathSegments("api/apps/latest")
            .addQueryParameter("channel_key", channelKey)
            .addQueryParameter("release_version", installed.releaseVersion)
            .addQueryParameter("build_version", installed.buildVersion.toString())
            .addQueryParameter("bundle_id", installed.packageName)
            .addQueryParameter("sdk", "android-$sdkVersion")
            .build()
        return Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()
    }
}
