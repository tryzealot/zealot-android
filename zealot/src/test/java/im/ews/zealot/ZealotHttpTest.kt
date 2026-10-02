package im.ews.zealot

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZealotHttpTest {
    @Test
    fun callTimeoutIsReportedAsNetworkError() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body("""{"releases":[]}""")
                    .bodyDelay(1, TimeUnit.SECONDS)
                    .build()
            )
            server.start()

            val results = LinkedBlockingQueue<UpdateResult>()
            val client = OkHttpClient.Builder()
                .callTimeout(150, TimeUnit.MILLISECONDS)
                .build()
            Zealot.create(RuntimeEnvironment.getApplication())
                .setEndpoint(server.url("/").toString())
                .setChannelKey("channel")
                .setHttpClient(client)
                .setCallbackExecutor(Executor { it.run() })
                .checkForUpdate { result -> results.offer(result) }

            val result = results.poll(5, TimeUnit.SECONDS) as UpdateResult.Error
            assertEquals(UpdateErrorCode.NETWORK, result.error.code)
        }
    }

    @Test
    fun explicitCancellationDuringResponseReadSuppressesResult() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body("""{"releases":[]}""")
                    .bodyDelay(2, TimeUnit.SECONDS)
                    .build()
            )
            server.start()

            val results = LinkedBlockingQueue<UpdateResult>()
            val call = Zealot.create(RuntimeEnvironment.getApplication())
                .setEndpoint(server.url("/").toString())
                .setChannelKey("channel")
                .setCallbackExecutor(Executor { it.run() })
                .checkForUpdate { result -> results.offer(result) }

            assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
            call.cancel()
            assertTrue(call.isCanceled())
            assertNull(results.poll(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun typedCallbackReportsAvailableCurrentAndHttpError() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder().code(200).body(
                    """{"releases":[{"release_version":"2.0","build_version":"12",
                        "install_url":"https://zealot.example.com/install"}]}"""
                ).build()
            )
            server.enqueue(MockResponse.Builder().code(200).body("""{"releases":[]}""").build())
            server.enqueue(MockResponse.Builder().code(422).body("""{"error":"invalid"}""").build())
            server.start()

            val results = LinkedBlockingQueue<UpdateResult>()
            val zealot = Zealot.create(RuntimeEnvironment.getApplication())
                .setEndpoint(server.url("/").toString())
                .setChannelKey("channel")
                .setCallbackExecutor(Executor { it.run() })

            repeat(3) {
                zealot.checkForUpdate { result -> results.offer(result) }
                val result = results.poll(5, TimeUnit.SECONDS)
                when (it) {
                    0 -> assertEquals("2.0", (result as UpdateResult.UpdateAvailable).release.releaseVersion)
                    1 -> assertEquals(UpdateResult.UpToDate, result)
                    else -> {
                        val error = (result as UpdateResult.Error).error
                        assertEquals(UpdateErrorCode.HTTP, error.code)
                        assertEquals(422, error.httpStatusCode)
                    }
                }
            }
        }
    }

    @Test
    fun httpRequestAndReleaseResponseReachPublicCallback() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder().code(200).body(
                    """{"releases":[{"release_version":"2.0","build_version":"12",
                        "install_url":"https://zealot.example.com/install",
                        "changelog":[{"message":"New feature"}]}]}"""
                ).build()
            )
            server.start()
            val completed = CountDownLatch(1)
            val capturedRelease = AtomicReference<ReleaseInfo>()
            val capturedError = AtomicReference<UpdateError>()
            val zealot = Zealot.create(RuntimeEnvironment.getApplication())
                .setEndpoint(server.url("/base/").toString())
                .setChannelKey("my key")
                .setCallbackExecutor(Executor { it.run() })

            zealot.checkForUpdate(object : UpdateCallback {
                override fun onUpdateAvailable(release: ReleaseInfo) {
                    capturedRelease.set(release)
                    completed.countDown()
                }
                override fun onUpToDate() = completed.countDown()
                override fun onError(error: UpdateError) {
                    capturedError.set(error)
                    completed.countDown()
                }
            })

            assertTrue(completed.await(5, TimeUnit.SECONDS))
            val request = server.takeRequest(5, TimeUnit.SECONDS)
            assertNull(capturedError.get())
            assertEquals("2.0", capturedRelease.get().releaseVersion)
            assertEquals("01. New feature", capturedRelease.get().changelog)
            assertEquals("/base/api/apps/latest", request!!.url.encodedPath)
            assertEquals("my key", request.url.queryParameter("channel_key"))
            assertEquals(RuntimeEnvironment.getApplication().packageName,
                request.url.queryParameter("bundle_id"))
        }
    }

    @Test
    fun httpErrorIsDeliveredWithoutServerBody() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder().code(404)
                    .body("channel-key-super-secret").build()
            )
            server.start()
            val completed = CountDownLatch(1)
            val capturedError = AtomicReference<UpdateError>()
            Zealot.create(RuntimeEnvironment.getApplication())
                .setEndpoint(server.url("/").toString())
                .setChannelKey("my key")
                .setCallbackExecutor(Executor { it.run() })
                .checkForUpdate(object : UpdateCallback {
                    override fun onUpdateAvailable(release: ReleaseInfo) = completed.countDown()
                    override fun onUpToDate() = completed.countDown()
                    override fun onError(error: UpdateError) {
                        capturedError.set(error)
                        completed.countDown()
                    }
                })

            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertEquals(UpdateErrorCode.HTTP, capturedError.get().code)
            assertEquals(404, capturedError.get().httpStatusCode)
            assertFalse(capturedError.get().toString().contains("channel-key-super-secret"))
        }
    }

    @Test
    fun wrongAppReleaseAfterRedirectIsReportedAsInvalidResponse() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder().code(302)
                    .addHeader("Location", "/redirected")
                    .build()
            )
            server.enqueue(
                MockResponse.Builder().code(200).body(
                    """{"releases":[{"bundle_id":"com.other.app","release_version":"2.0",
                        "build_version":"12",
                        "install_url":"https://zealot.example.com/install"}]}"""
                ).build()
            )
            server.start()
            val results = LinkedBlockingQueue<UpdateResult>()
            Zealot.create(RuntimeEnvironment.getApplication())
                .setEndpoint(server.url("/").toString())
                .setChannelKey("channel")
                .setCallbackExecutor(Executor { it.run() })
                .checkForUpdate { result -> results.offer(result) }

            val result = results.poll(5, TimeUnit.SECONDS) as UpdateResult.Error
            assertEquals(UpdateErrorCode.INVALID_RESPONSE, result.error.code)
            assertEquals(
                RuntimeEnvironment.getApplication().packageName,
                server.takeRequest(5, TimeUnit.SECONDS)!!.url.queryParameter("bundle_id")
            )
            assertNull(server.takeRequest(5, TimeUnit.SECONDS)!!.url.queryParameter("bundle_id"))
        }
    }
}
