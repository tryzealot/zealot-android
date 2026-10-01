package im.ews.zealot

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageInfo
import im.ews.zealot.internal.InstalledAppVersion
import im.ews.zealot.internal.ReleaseResponseParser
import im.ews.zealot.internal.UpdateRequestFactory
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Optional contract check against a real Zealot instance. CI skips it unless both environment
 * variables are set; the channel key never needs to be stored in the repository.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZealotLiveApiTest {
    @Test
    fun previousReleaseFindsLatestAndCurrentReleaseIsUpToDate() {
        val endpointText = System.getenv("ZEALOT_TEST_ENDPOINT")
        val channelKey = System.getenv("ZEALOT_TEST_CHANNEL_KEY")
        assumeTrue(!endpointText.isNullOrBlank() && !channelKey.isNullOrBlank())

        val endpoint = endpointText!!.trimEnd('/').toHttpUrl()
        val client = OkHttpClient()
        val versionsUrl = endpoint.newBuilder()
            .addPathSegments("api/apps/versions")
            .addQueryParameter("channel_key", channelKey)
            .build()
        val versions = JSONObject(fetch(client, Request.Builder().url(versionsUrl).build()))
            .getJSONArray("releases")
        assumeTrue("The test channel needs at least two releases", versions.length() >= 2)

        val latest = versions.getJSONObject(0)
        val bundleId = latest.getString("bundle_id")
        val previous = (1 until versions.length())
            .map(versions::getJSONObject)
            .firstOrNull { it.optString("bundle_id") == bundleId }
        assumeTrue("The test channel needs two releases of the same app", previous != null)
        val latestVersion = installedVersion(bundleId, latest)
        val previousVersion = installedVersion(bundleId, previous!!)

        val newerReleases = fetchResult(client, endpoint, channelKey!!, previousVersion)
        assertTrue(newerReleases is UpdateResult.UpdateAvailable)
        val available = (newerReleases as UpdateResult.UpdateAvailable).release
        assertEquals(latestVersion.releaseVersion, available.releaseVersion)
        assertEquals(latestVersion.buildVersion.toString(), available.buildVersion)
        assertTrue(available.installUrl.startsWith("https://"))

        val app = RuntimeEnvironment.getApplication()
        val installedPackage = PackageInfo().apply {
            packageName = bundleId
            versionName = previousVersion.releaseVersion
            setLongVersionCode(previousVersion.buildVersion)
        }
        Shadows.shadowOf(app.packageManager).installPackage(installedPackage)
        val installedAppContext = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getPackageName(): String = bundleId
        }
        val callbackResults = LinkedBlockingQueue<UpdateResult>()
        Zealot.create(installedAppContext)
            .setEndpoint(endpointText)
            .setChannelKey(channelKey)
            .setCallbackExecutor(Executor { it.run() })
            .checkForUpdate { result -> callbackResults.offer(result) }
        val publicResult = callbackResults.poll(10, TimeUnit.SECONDS)
        assertEquals(newerReleases, publicResult)

        assertEquals(
            UpdateResult.UpToDate,
            fetchResult(client, endpoint, channelKey, latestVersion)
        )

        val invalidKeyRequest = UpdateRequestFactory.create(
            endpoint, "$channelKey-invalid", latestVersion, Zealot.VERSION
        )
        client.newCall(invalidKeyRequest).execute().use { response ->
            assertTrue("An unknown channel key must not look like an up-to-date app", !response.isSuccessful)
        }
    }

    private fun installedVersion(bundleId: String, release: JSONObject) = InstalledAppVersion(
        packageName = bundleId,
        releaseVersion = release.getString("release_version"),
        buildVersion = release.getString("build_version").toLong()
    )

    private fun fetchResult(
        client: OkHttpClient,
        endpoint: HttpUrl,
        channelKey: String,
        installed: InstalledAppVersion
    ): UpdateResult {
        val request = UpdateRequestFactory.create(endpoint, channelKey, installed, Zealot.VERSION)
        return ReleaseResponseParser.parse(fetch(client, request))
    }

    private fun fetch(client: OkHttpClient, request: Request): String =
        client.newCall(request).execute().use { response ->
            assertEquals("Zealot API request failed", 200, response.code)
            requireNotNull(response.body).string()
        }
}
