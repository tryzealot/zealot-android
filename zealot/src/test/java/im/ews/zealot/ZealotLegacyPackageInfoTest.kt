package im.ews.zealot

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
class ZealotLegacyPackageInfoTest {
    @Test
    fun buildsRequestFromLegacyPackageInfo() {
        val context = RuntimeEnvironment.getApplication()
        val request = Zealot.create(context)
            .setEndpoint("https://zealot.example.com")
            .setChannelKey("channel")
            .createRequest()

        assertEquals("/api/apps/latest", request.url.encodedPath)
        assertEquals(context.packageName, request.url.queryParameter("bundle_id"))
        assertEquals("channel", request.url.queryParameter("channel_key"))
    }
}
