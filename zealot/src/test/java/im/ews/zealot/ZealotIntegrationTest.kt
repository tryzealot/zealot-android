package im.ews.zealot

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class ZealotIntegrationTest {
    private val activities = mutableListOf<ActivityController<Activity>>()

    @After
    fun releaseActivities() {
        ShadowDialog.getShownDialogs().filter { it.isShowing }.forEach { it.dismiss() }
        activities.asReversed().forEach { it.pause().stop().destroy() }
    }

    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java)
        .setup().also(activities::add).get()

    @Test
    fun injectedTransportAndPresenterReceiveUpdate() {
        val activity = activity()
        val requestedUrl = AtomicReference<String>()
        val presented = AtomicReference<ReleaseInfo>()
        val delivered = AtomicReference<ReleaseInfo>()
        val failure = AtomicReference<UpdateError>()
        val callbackFinished = CountDownLatch(1)
        val responseJson = """{"releases":[{"release_version":"2.0","build_version":"12",
            "install_url":"https://zealot.example.com/install","text_changelog":"Changes"}]}"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requestedUrl.set(chain.request().url.toString())
            response(chain.request(), 200, responseJson)
        }.build()

        Zealot.create(activity)
            .setEndpoint("https://zealot.example.com/base")
            .setChannelKey("secret +")
            .setHttpClient(client)
            .setCallbackExecutor(Executor { it.run() })
            .setUpdatePresenter(UpdatePresenter { _, release -> presented.set(release) })
            .launch(object : UpdateCallback {
                override fun onUpdateAvailable(release: ReleaseInfo) {
                    delivered.set(release)
                    callbackFinished.countDown()
                }

                override fun onUpToDate() = callbackFinished.countDown()

                override fun onError(error: UpdateError) {
                    failure.set(error)
                    callbackFinished.countDown()
                }
            })

        assertTrue(callbackFinished.await(5, TimeUnit.SECONDS))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertNull(failure.get())
        assertEquals("2.0", delivered.get().releaseVersion)
        assertEquals(delivered.get(), presented.get())
        assertTrue(requestedUrl.get().contains("channel_key=secret%20%2B"))
    }

    @Test
    fun cancellationBeforeMainDeliverySuppressesCallback() {
        val activity = activity()
        val zealot = Zealot.create(activity)
        val request = Request.Builder().url("https://zealot.example.com/api/apps/latest").build()
        val call = OkHttpClient().newCall(request)
        val delivered = AtomicReference<ReleaseInfo>()
        val callback = Callback(zealot).apply {
            configure(object : UpdateCallback {
                override fun onUpdateAvailable(release: ReleaseInfo) {
                    delivered.set(release)
                }

                override fun onUpToDate() = Unit
                override fun onError(error: UpdateError) = Unit
            }, false, null, null, Zealot.ScreenHeight.AUTOMATIC)
        }

        callback.onResponse(
            call,
            response(request, 200, """{"releases":[{"release_version":"2.0",
                "build_version":"12","install_url":"https://zealot.example.com/install"}]}""")
        )
        call.cancel()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertNull(delivered.get())
    }

    @Test
    fun cancellableUiCheckSuppressesPresenterAndCallback() {
        val activity = activity()
        val requestStarted = CountDownLatch(1)
        val unblockRequest = CountDownLatch(1)
        val clientIdle = CountDownLatch(1)
        val presented = AtomicBoolean(false)
        val delivered = AtomicBoolean(false)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requestStarted.countDown()
            assertTrue(unblockRequest.await(5, TimeUnit.SECONDS))
            response(chain.request(), 200, """{"releases":[{"release_version":"2.0",
                "build_version":"12","install_url":"https://zealot.example.com/install"}]}""")
        }.build().also { it.dispatcher.idleCallback = Runnable { clientIdle.countDown() } }
        val call = Zealot.create(activity)
            .setEndpoint("https://zealot.example.com")
            .setChannelKey("key")
            .setHttpClient(client)
            .setUpdatePresenter(UpdatePresenter { _, _ -> presented.set(true) })
            .checkAndShowUpdate(object : UpdateCallback {
                override fun onUpdateAvailable(release: ReleaseInfo) { delivered.set(true) }
                override fun onUpToDate() { delivered.set(true) }
                override fun onError(error: UpdateError) { delivered.set(true) }
            })

        assertTrue(requestStarted.await(5, TimeUnit.SECONDS))
        call.cancel()
        unblockRequest.countDown()
        assertTrue(clientIdle.await(5, TimeUnit.SECONDS))
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(call.isCanceled())
        assertFalse(presented.get())
        assertFalse(delivered.get())
    }

    @Test
    fun inFlightCheckKeepsItsConfigurationSnapshot() {
        val activity = activity()
        val requestStarted = CountDownLatch(1)
        val unblockRequest = CountDownLatch(1)
        val resultReceived = CountDownLatch(1)
        val requestedKey = AtomicReference<String>()
        val firstPresenterCalled = AtomicBoolean(false)
        val laterPresenterCalled = AtomicBoolean(false)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requestedKey.set(chain.request().url.queryParameter("channel_key"))
            requestStarted.countDown()
            assertTrue(unblockRequest.await(5, TimeUnit.SECONDS))
            response(chain.request(), 200, """{"releases":[{"release_version":"2.0",
                "build_version":"12","install_url":"https://zealot.example.com/install"}]}""")
        }.build()
        val zealot = Zealot.create(activity)
            .setEndpoint("https://zealot.example.com")
            .setChannelKey("first-key")
            .setHttpClient(client)
            .setCallbackExecutor(Executor { it.run() })
            .setUpdatePresenter(UpdatePresenter { _, _ -> firstPresenterCalled.set(true) })

        zealot.checkAndShowUpdate(object : UpdateCallback {
            override fun onUpdateAvailable(release: ReleaseInfo) { resultReceived.countDown() }
            override fun onUpToDate() { resultReceived.countDown() }
            override fun onError(error: UpdateError) { resultReceived.countDown() }
        })
        assertTrue(requestStarted.await(5, TimeUnit.SECONDS))
        zealot.setChannelKey("later-key")
            .setUpdatePresenter(UpdatePresenter { _, _ -> laterPresenterCalled.set(true) })
        unblockRequest.countDown()
        assertTrue(resultReceived.await(5, TimeUnit.SECONDS))
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals("first-key", requestedKey.get())
        assertTrue(firstPresenterCalled.get())
        assertFalse(laterPresenterCalled.get())
    }

    @Test
    fun buildTypeSelectsSpecificKeyThenFallsBackToDefault() {
        val activity = activity()
        val zealot = Zealot.create(activity)
            .setEndpoint("https://zealot.example.com")
            .setChannelKey("default-key")
            .setChannelKey("beta-key", "beta")

        assertEquals("beta-key", zealot.setBuildType("beta").createRequest()
            .url.queryParameter("channel_key"))
        assertEquals("default-key", zealot.setBuildType("unknown").createRequest()
            .url.queryParameter("channel_key"))
    }

    @Test
    fun httpErrorIsReportedWithoutResponseBody() {
        val activity = activity()
        val zealot = Zealot.create(activity)
        val request = Request.Builder().url("https://zealot.example.com/api/apps/latest").build()
        val capturedError = AtomicReference<UpdateError>()
        val callback = Callback(zealot).apply {
            configure(object : UpdateCallback {
                override fun onUpdateAvailable(release: ReleaseInfo) = Unit
                override fun onUpToDate() = Unit
                override fun onError(error: UpdateError) { capturedError.set(error) }
            }, false, null, null, Zealot.ScreenHeight.AUTOMATIC)
        }

        callback.onResponse(
            OkHttpClient().newCall(request),
            response(request, 503, "sensitive response body")
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertNotNull(capturedError.get())
        assertEquals(UpdateErrorCode.HTTP, capturedError.get().code)
        assertEquals(503, capturedError.get().httpStatusCode)
        assertFalse(capturedError.get().message.contains("sensitive"))
    }

    @Test
    fun networkFailureIsReportedToCaller() {
        val request = Request.Builder().url("https://zealot.example.com/api/apps/latest").build()
        val capturedError = AtomicReference<UpdateError>()
        val callback = Callback(Zealot.create(activity())).apply {
            configure(object : UpdateCallback {
                override fun onUpdateAvailable(release: ReleaseInfo) = Unit
                override fun onUpToDate() = Unit
                override fun onError(error: UpdateError) { capturedError.set(error) }
            }, false, null, null, Zealot.ScreenHeight.AUTOMATIC)
        }

        callback.onFailure(OkHttpClient().newCall(request), IOException("connection refused"))
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(UpdateErrorCode.NETWORK, capturedError.get().code)
        assertFalse(capturedError.get().message.contains("connection refused"))
    }

    @Test
    fun rejectedCallbackExecutorFallsBackToMainThread() {
        val request = Request.Builder().url("https://zealot.example.com/api/apps/latest").build()
        val delivered = AtomicBoolean(false)
        val callback = Callback(Zealot.create(activity())).apply {
            configure(object : UpdateCallback {
                override fun onUpdateAvailable(release: ReleaseInfo) = Unit
                override fun onUpToDate() { delivered.set(true) }
                override fun onError(error: UpdateError) = Unit
            }, false, Executor { throw RejectedExecutionException() }, null,
                Zealot.ScreenHeight.AUTOMATIC)
        }

        callback.onResponse(
            OkHttpClient().newCall(request),
            response(request, 200, """{"releases":[]}""")
        )
        assertFalse(delivered.get())
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(delivered.get())
    }

    @Test
    fun exceptionFromCallbackIsNotTreatedAsExecutorRejection() {
        val request = Request.Builder().url("https://zealot.example.com/api/apps/latest").build()
        val invocations = AtomicInteger()
        val callback = Callback(Zealot.create(activity())).apply {
            configure(object : UpdateCallback {
                override fun onUpdateAvailable(release: ReleaseInfo) = Unit
                override fun onUpToDate() {
                    invocations.incrementAndGet()
                    throw RejectedExecutionException("callback failure")
                }
                override fun onError(error: UpdateError) = Unit
            }, false, Executor { it.run() }, null, Zealot.ScreenHeight.AUTOMATIC)
        }

        assertThrows(RejectedExecutionException::class.java) {
            callback.onResponse(
                OkHttpClient().newCall(request),
                response(request, 200, """{"releases":[]}""")
            )
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, invocations.get())
    }

    @Test
    fun executorRejectionAfterDeliveryDoesNotCrashOrRedeliver() {
        val request = Request.Builder().url("https://zealot.example.com/api/apps/latest").build()
        val invocations = AtomicInteger()
        val callback = Callback(Zealot.create(activity())).apply {
            configure(object : UpdateCallback {
                override fun onUpdateAvailable(release: ReleaseInfo) = Unit
                override fun onUpToDate() { invocations.incrementAndGet() }
                override fun onError(error: UpdateError) = Unit
            }, false, Executor {
                it.run()
                throw RejectedExecutionException("executor failed after delivery")
            }, null, Zealot.ScreenHeight.AUTOMATIC)
        }

        callback.onResponse(
            OkHttpClient().newCall(request),
            response(request, 200, """{"releases":[]}""")
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, invocations.get())
    }

    @Test
    fun executorThatQueuesThenRejectsStillDeliversOnce() {
        val request = Request.Builder().url("https://zealot.example.com/api/apps/latest").build()
        val queued = AtomicReference<Runnable>()
        val invocations = AtomicInteger()
        val callback = Callback(Zealot.create(activity())).apply {
            configure(object : UpdateCallback {
                override fun onUpdateAvailable(release: ReleaseInfo) = Unit
                override fun onUpToDate() { invocations.incrementAndGet() }
                override fun onError(error: UpdateError) = Unit
            }, false, Executor {
                queued.set(it)
                throw RejectedExecutionException("executor rejected after queuing")
            }, null, Zealot.ScreenHeight.AUTOMATIC)
        }

        callback.onResponse(
            OkHttpClient().newCall(request),
            response(request, 200, """{"releases":[]}""")
        )
        queued.get().run()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, invocations.get())
    }

    @Test
    fun oversizedResponseIsRejected() {
        val activity = activity()
        val request = Request.Builder().url("https://zealot.example.com/api/apps/latest").build()
        val capturedError = AtomicReference<UpdateError>()
        val callback = Callback(Zealot.create(activity)).apply {
            configure(object : UpdateCallback {
                override fun onUpdateAvailable(release: ReleaseInfo) = Unit
                override fun onUpToDate() = Unit
                override fun onError(error: UpdateError) { capturedError.set(error) }
            }, false, null, null, Zealot.ScreenHeight.AUTOMATIC)
        }

        callback.onResponse(
            OkHttpClient().newCall(request),
            response(request, 200, "x".repeat(1024 * 1024 + 1))
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(UpdateErrorCode.INVALID_RESPONSE, capturedError.get().code)
    }

    @Test
    fun malformedResponseDoesNotExposeItsContents() {
        val request = Request.Builder().url("https://zealot.example.com/api/apps/latest").build()
        val capturedError = AtomicReference<UpdateError>()
        val callback = Callback(Zealot.create(activity())).apply {
            configure(object : UpdateCallback {
                override fun onUpdateAvailable(release: ReleaseInfo) = Unit
                override fun onUpToDate() = Unit
                override fun onError(error: UpdateError) { capturedError.set(error) }
            }, false, null, null, Zealot.ScreenHeight.AUTOMATIC)
        }

        callback.onResponse(
            OkHttpClient().newCall(request),
            response(request, 200, """{"private":"channel-key-super-secret","releases":[""")
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(UpdateErrorCode.INVALID_RESPONSE, capturedError.get().code)
        assertFalse(capturedError.get().toString().contains("channel-key-super-secret"))
    }

    @Test
    fun repeatedUpdateDoesNotStackDialogs() {
        val activity = activity()
        val zealot = Zealot.create(activity)
        val before = ShadowDialog.getShownDialogs().size

        zealot.showAlert("2.0", "Changes", "https://zealot.example.com/install")
        Zealot.create(activity).showAlert("2.0", "Changes", "https://zealot.example.com/install")
        assertEquals(before + 1, ShadowDialog.getShownDialogs().size)
        assertEquals(
            ShadowDialog.getLatestDialog(),
            activity.window.decorView.getTag(R.id.zealot_visible_dialog)
        )

        ShadowDialog.getLatestDialog().dismiss()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertNull(activity.window.decorView.getTag(R.id.zealot_visible_dialog))
        zealot.showAlert("2.0", "Changes", "https://zealot.example.com/install")
        assertEquals(before + 2, ShadowDialog.getShownDialogs().size)
    }

    @Test
    @Suppress("DEPRECATION")
    fun builtInDialogOptionsCanBeCustomizedAndReset() {
        val activity = activity()
        val zealot = Zealot.create(activity).setDialogOptions(
            UpdateDialogOptions(
                title = "App update",
                updateButtonText = "Install",
                laterButtonText = "Not now",
                cancelable = false
            )
        )

        zealot.showAlert("2.0", "Changes", "https://zealot.example.com/install")
        val customized = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals("App update", Shadows.shadowOf(customized).title)
        assertEquals("Install", customized.getButton(AlertDialog.BUTTON_POSITIVE).text)
        assertEquals("Not now", customized.getButton(AlertDialog.BUTTON_NEGATIVE).text)
        customized.onBackPressed()
        assertTrue(customized.isShowing)

        customized.dismiss()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        zealot.setDialogOptions(null)
            .showAlert("3.0", "More changes", "https://zealot.example.com/install")
        val restored = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(activity.getString(R.string.zealot_update_title, "3.0"),
            Shadows.shadowOf(restored).title)
        assertEquals(activity.getString(R.string.zealot_update_now),
            restored.getButton(AlertDialog.BUTTON_POSITIVE).text)
        assertEquals(activity.getString(R.string.zealot_update_later),
            restored.getButton(AlertDialog.BUTTON_NEGATIVE).text)
    }

    @Test
    fun builtInDialogOptionsRejectBlankLabels() {
        assertThrows(IllegalArgumentException::class.java) {
            UpdateDialogOptions(updateButtonText = " ")
        }
    }

    @Test
    fun inFlightDialogKeepsItsOptionsSnapshot() {
        val activity = activity()
        val requestStarted = CountDownLatch(1)
        val unblockRequest = CountDownLatch(1)
        val callbackFinished = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requestStarted.countDown()
            assertTrue(unblockRequest.await(5, TimeUnit.SECONDS))
            response(chain.request(), 200, """{"releases":[{"release_version":"2.0",
                "build_version":"12","install_url":"https://zealot.example.com/install"}]}""")
        }.build()
        val zealot = Zealot.create(activity)
            .setEndpoint("https://zealot.example.com")
            .setChannelKey("channel")
            .setHttpClient(client)
            .setCallbackExecutor(Executor { it.run() })
            .setDialogOptions(UpdateDialogOptions(updateButtonText = "First label"))

        zealot.checkAndShowUpdate(UpdateResultCallback { callbackFinished.countDown() })
        assertTrue(requestStarted.await(5, TimeUnit.SECONDS))
        zealot.setDialogOptions(UpdateDialogOptions(updateButtonText = "Later label"))
        unblockRequest.countDown()
        assertTrue(callbackFinished.await(5, TimeUnit.SECONDS))
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals("First label", dialog.getButton(AlertDialog.BUTTON_POSITIVE).text)
    }

    @Test
    fun destroyingActivityDismissesUpdateDialog() {
        val activity = activity()
        Zealot.create(activity).showAlert(
            "2.0", "Changes", "https://zealot.example.com/install"
        )
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue(dialog.isShowing)

        activities.removeAt(activities.lastIndex).pause().stop().destroy()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertFalse(dialog.isShowing)
    }

    @Test
    fun delayedDismissalOfOldDialogDoesNotClearNewDialog() {
        val activity = activity()
        val zealot = Zealot.create(activity)
        zealot.showAlert("2.0", "Changes", "https://zealot.example.com/install")
        val first = ShadowDialog.getLatestDialog()
        first.dismiss()

        zealot.showAlert("3.0", "More changes", "https://zealot.example.com/install")
        val second = ShadowDialog.getLatestDialog()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertFalse(first.isShowing)
        assertTrue(second.isShowing)
        assertEquals(second, activity.window.decorView.getTag(R.id.zealot_visible_dialog))
    }

    @Test
    fun destroyedActivitySuppressesUiButStillDeliversResult() {
        val activity = activity()
        val zealot = Zealot.create(activity)
        val request = Request.Builder().url("https://zealot.example.com/api/apps/latest").build()
        val presenterCalled = AtomicBoolean(false)
        val callbackCalled = AtomicBoolean(false)
        val release = ReleaseInfo("2.0", "12", "https://zealot.example.com/install", "Changes")

        zealot.dispatchResult(
            call = OkHttpClient().newCall(request),
            result = UpdateResult.UpdateAvailable(release),
            callback = object : UpdateCallback {
                override fun onUpdateAvailable(release: ReleaseInfo) { callbackCalled.set(true) }
                override fun onUpToDate() = Unit
                override fun onError(error: UpdateError) = Unit
            },
            showDialog = true,
            callbackExecutor = null,
            presenter = UpdatePresenter { _, _ -> presenterCalled.set(true) },
            maxHeight = Zealot.ScreenHeight.AUTOMATIC
        )
        activities.removeAt(activities.lastIndex).pause().stop().destroy()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertFalse(presenterCalled.get())
        assertTrue(callbackCalled.get())
    }

    @Test
    fun updateButtonOpensInstallUrl() {
        val activity = activity()
        Zealot.create(activity).showAlert(
            "2.0 (12)",
            "Changes",
            "https://zealot.example.com/install/12"
        )

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val opened = Shadows.shadowOf(activity).nextStartedActivity

        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals("https://zealot.example.com/install/12", opened.data.toString())
    }

    @Test
    fun updateButtonOpensTheValidatedNormalizedUrl() {
        val activity = activity()
        Zealot.create(activity).showAlert(
            "2.0 (12)",
            "",
            " https://zealot.example.com/install/12 "
        )

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val opened = Shadows.shadowOf(activity).nextStartedActivity

        assertEquals("https://zealot.example.com/install/12", opened.data.toString())
    }

    private fun response(request: Request, code: Int, body: String): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code == 200) "OK" else "Error")
        .body(body.toResponseBody())
        .build()
}
