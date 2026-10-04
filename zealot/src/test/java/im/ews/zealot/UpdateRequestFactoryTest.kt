package im.ews.zealot

import im.ews.zealot.internal.InstalledAppVersion
import im.ews.zealot.internal.UpdateRequestFactory
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateRequestFactoryTest {
    @Test
    fun preservesBasePathAndEncodesChannelKey() {
        val request = UpdateRequestFactory.create(
            endpoint = "https://zealot.example.com/base".toHttpUrl(),
            channelKey = "key +/?=",
            installed = InstalledAppVersion("com.example.app", "1.2.3", 42),
            sdkVersion = "0.3.0"
        )

        assertEquals("GET", request.method)
        assertEquals("/base/api/apps/latest", request.url.encodedPath)
        assertEquals("key +/?=", request.url.queryParameter("channel_key"))
        assertEquals("com.example.app", request.url.queryParameter("bundle_id"))
        assertEquals("1.2.3", request.url.queryParameter("release_version"))
        assertEquals("42", request.url.queryParameter("build_version"))
        assertEquals("android-0.3.0", request.url.queryParameter("sdk"))
        assertEquals("application/json", request.header("Accept"))
    }
}
