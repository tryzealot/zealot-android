package im.ews.zealot

import android.app.Activity
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import im.ews.zealot.internal.DefaultUpdateDialog
import im.ews.zealot.internal.InstalledAppVersion
import im.ews.zealot.internal.UpdateRequestFactory
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.lang.ref.WeakReference
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Checks a Zealot channel for app updates. The original fluent methods remain available.
 * Configure an instance before starting checks; each check captures its own configuration.
 */
class Zealot private constructor(context: Context) {
    companion object {
        const val VERSION = BuildConfig.ZEALOT_SDK_VERSION
        const val BUILD_TYPE = "default"

        private val defaultClient by lazy {
            OkHttpClient.Builder()
                .callTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        @JvmStatic
        fun create(context: Context): Zealot = Zealot(context)
    }

    enum class ScreenHeight {
        AUTOMATIC, HALFSCREEN
    }

    private data class Configuration(
        val endpoint: HttpUrl? = null,
        val channelKeys: Map<String, String> = emptyMap(),
        val buildType: String = BUILD_TYPE,
        val maxHeight: ScreenHeight = ScreenHeight.AUTOMATIC,
        val httpClient: OkHttpClient? = null,
        val presenter: UpdatePresenter? = null,
        val callbackExecutor: Executor? = null
    )

    private val applicationContext = context.applicationContext ?: context
    private val activityReference = (context as? Activity)?.let(::WeakReference)
    val context: Context
        get() = activityReference?.get() ?: applicationContext

    private val configurationLock = Any()
    @Volatile private var configuration = Configuration()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun setEndpoint(endpoint: String): Zealot {
        val parsed = endpoint.trim().trimEnd('/').toHttpUrlOrNull()
        require(
            parsed != null &&
                parsed.encodedUsername.isEmpty() &&
                parsed.encodedPassword.isEmpty() &&
                parsed.encodedQuery == null &&
                parsed.encodedFragment == null
        ) { "endpoint must be an absolute HTTP or HTTPS base URL without credentials or query parameters" }
        updateConfiguration { it.copy(endpoint = parsed) }
        return this
    }

    fun setChannelKey(channelKey: String): Zealot = setChannelKey(channelKey, BUILD_TYPE)

    fun setChannelKey(channelKey: String, buildType: String): Zealot {
        require(channelKey.isNotBlank()) { "channelKey must not be blank" }
        require(buildType.isNotBlank()) { "buildType must not be blank" }
        updateConfiguration { it.copy(channelKeys = it.channelKeys + (buildType to channelKey)) }
        return this
    }

    fun setBuildType(buildType: String): Zealot {
        require(buildType.isNotBlank()) { "buildType must not be blank" }
        updateConfiguration { it.copy(buildType = buildType) }
        return this
    }

    fun setAlertMaxHeight(height: ScreenHeight): Zealot {
        updateConfiguration { it.copy(maxHeight = height) }
        return this
    }

    /** Uses a caller-owned client; pass null to restore the shared default client. */
    fun setHttpClient(client: OkHttpClient?): Zealot {
        updateConfiguration { it.copy(httpClient = client) }
        return this
    }

    /** Replaces the built-in dialog; pass null to restore it. The presenter runs on the main thread. */
    fun setUpdatePresenter(presenter: UpdatePresenter?): Zealot {
        updateConfiguration { it.copy(presenter = presenter) }
        return this
    }

    /** Sets callback delivery; pass null to restore the Android main thread. */
    fun setCallbackExecutor(executor: Executor?): Zealot {
        updateConfiguration { it.copy(callbackExecutor = executor) }
        return this
    }

    /** Checks for an update and returns a cancellable OkHttp call. */
    fun checkForUpdate(callback: UpdateCallback): Call = startCheck(callback, showDialog = false)

    /** Checks for an update and reports one typed result. */
    fun checkForUpdate(callback: UpdateResultCallback): Call =
        checkForUpdate(callback.asUpdateCallback())

    /** Checks, presents an available release, and returns a cancellable OkHttp call. */
    fun checkAndShowUpdate(callback: UpdateCallback): Call = startCheck(callback, showDialog = true)

    /** Checks, presents an available release, and reports one typed result. */
    fun checkAndShowUpdate(callback: UpdateResultCallback): Call =
        checkAndShowUpdate(callback.asUpdateCallback())

    /** Compatibility entry point: shows an update dialog when created from an Activity. */
    fun launch() {
        startCheck(null, showDialog = true)
    }

    /** Shows the update UI and also reports the result to the caller. */
    fun launch(callback: UpdateCallback) {
        checkAndShowUpdate(callback)
    }

    /** Shows update UI and reports one typed result. */
    fun launch(callback: UpdateResultCallback) {
        checkAndShowUpdate(callback)
    }

    private fun UpdateResultCallback.asUpdateCallback(): UpdateCallback = object : UpdateCallback {
        override fun onUpdateAvailable(release: ReleaseInfo) {
            onResult(UpdateResult.UpdateAvailable(release))
        }

        override fun onUpToDate() {
            onResult(UpdateResult.UpToDate)
        }

        override fun onError(error: UpdateError) {
            onResult(UpdateResult.Error(error))
        }
    }

    private fun startCheck(callback: UpdateCallback?, showDialog: Boolean): Call {
        val settings = configuration
        val request = createRequest(settings)
        return (settings.httpClient ?: defaultClient).newCall(request).also { call ->
            call.enqueue(Callback(this).apply {
                configure(
                    callback,
                    showDialog = showDialog,
                    callbackExecutor = settings.callbackExecutor,
                    presenter = settings.presenter,
                    maxHeight = settings.maxHeight
                )
            })
        }
    }

    @JvmSynthetic
    internal fun createRequest(): Request = createRequest(configuration)

    private fun createRequest(settings: Configuration): Request {
        val endpoint = settings.endpoint
            ?: throw IllegalStateException("endpoint must be set before checking for updates")
        val channelKey = settings.channelKeys[settings.buildType]
            ?: settings.channelKeys[BUILD_TYPE]
            ?: throw IllegalStateException(
                "channelKey is not configured for build type '${settings.buildType}'"
            )
        val info = packageInfo()
        val buildVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return UpdateRequestFactory.create(
            endpoint = endpoint,
            channelKey = channelKey,
            installed = InstalledAppVersion(
                packageName = applicationContext.packageName,
                releaseVersion = info.versionName.orEmpty(),
                buildVersion = buildVersion
            ),
            sdkVersion = VERSION
        )
    }

    private fun packageInfo(): PackageInfo {
        val manager = applicationContext.packageManager
        val name = applicationContext.packageName
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getPackageInfo(name, PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(name, 0)
        }
    }

    @JvmSynthetic
    internal fun dispatchResult(
        call: Call,
        result: UpdateResult,
        callback: UpdateCallback?,
        showDialog: Boolean,
        callbackExecutor: Executor?,
        presenter: UpdatePresenter?,
        maxHeight: ScreenHeight?
    ) {
        if (showDialog && result is UpdateResult.UpdateAvailable) {
            mainHandler.post {
                if (call.isCanceled()) return@post
                val activity = activityReference?.get() ?: return@post
                if (activity.isFinishing || activity.isDestroyed) return@post
                val release = result.release
                if (presenter != null) {
                    presenter.present(activity, release)
                } else {
                    showAlertOnMain(
                        "${release.releaseVersion} (${release.buildVersion})",
                        release.changelog,
                        release.installUrl,
                        maxHeight ?: configuration.maxHeight
                    )
                }
            }
        }

        if (callback != null) {
            val invoked = AtomicBoolean(false)
            val delivery = Runnable {
                if (!invoked.compareAndSet(false, true)) return@Runnable
                if (call.isCanceled()) return@Runnable
                when (result) {
                    UpdateResult.UpToDate -> callback.onUpToDate()
                    is UpdateResult.UpdateAvailable -> callback.onUpdateAvailable(result.release)
                    is UpdateResult.Error -> callback.onError(result.error)
                }
            }
            if (callbackExecutor == null) {
                mainHandler.post(delivery)
            } else {
                try {
                    callbackExecutor.execute(delivery)
                } catch (error: RejectedExecutionException) {
                    if (invoked.get()) throw error
                    mainHandler.post(delivery)
                }
            }
        }
    }

    /** Shows the built-in dialog. Calls from a background thread are posted to the main thread. */
    fun showAlert(version: String, changelog: String, installUrl: String) {
        val maxHeight = configuration.maxHeight
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showAlertOnMain(version, changelog, installUrl, maxHeight)
        } else {
            mainHandler.post { showAlertOnMain(version, changelog, installUrl, maxHeight) }
        }
    }

    private fun showAlertOnMain(
        version: String,
        changelog: String,
        installUrl: String,
        maxHeight: ScreenHeight
    ) {
        val activity = activityReference?.get() ?: return
        DefaultUpdateDialog.show(activity, version, changelog, installUrl, maxHeight)
    }

    private inline fun updateConfiguration(change: (Configuration) -> Configuration) {
        synchronized(configurationLock) {
            configuration = change(configuration)
        }
    }
}
