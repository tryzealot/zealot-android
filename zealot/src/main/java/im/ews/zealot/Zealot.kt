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
import im.ews.zealot.internal.UpdateResultDispatcher
import im.ews.zealot.internal.UserCancellableCall
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.Executor
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
        val callbackExecutor: Executor? = null,
        val dialogOptions: UpdateDialogOptions = UpdateDialogOptions(),
        val locale: Locale? = null
    )

    private val applicationContext = context.applicationContext ?: context
    private val activityReference = (context as? Activity)?.let(::WeakReference)
    val context: Context
        get() = activityReference?.get() ?: applicationContext

    private val configurationLock = Any()
    @Volatile private var configuration = Configuration()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val resultDispatcher by lazy { UpdateResultDispatcher(activityReference, mainHandler) }

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

    /** Configures the built-in dialog; pass null to restore localized defaults. */
    fun setDialogOptions(options: UpdateDialogOptions?): Zealot {
        updateConfiguration { it.copy(dialogOptions = options ?: UpdateDialogOptions()) }
        return this
    }

    /**
     * Sets the language of Zealot's built-in UI. Pass null to follow the Activity's language.
     * Set this before starting a check or attaching automatic prompts.
     */
    fun setLocale(locale: Locale?): Zealot {
        updateConfiguration { it.copy(locale = locale) }
        return this
    }

    @JvmSynthetic
    internal fun currentLocale(): Locale? = configuration.locale

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

    /** Checks and presents an available release without a callback; cancel the returned call when needed. */
    fun checkAndShowUpdate(): Call = startCheck(null, showDialog = true)

    /** Checks, presents an available release, and returns a cancellable OkHttp call. */
    fun checkAndShowUpdate(callback: UpdateCallback): Call = startCheck(callback, showDialog = true)

    /** Checks, presents an available release, and reports one typed result. */
    fun checkAndShowUpdate(callback: UpdateResultCallback): Call =
        checkAndShowUpdate(callback.asUpdateCallback())

    /**
     * Creates a startup check for the Activity used to create this SDK instance.
     * The optional result callback runs on the main thread with the lifecycle methods.
     */
    @JvmOverloads
    fun createStartupCheck(callback: UpdateResultCallback? = null): StartupUpdateCheck {
        check(activityReference?.get() != null) { "a startup check requires an Activity" }
        return StartupUpdateCheck.create(callback) { resultCallback ->
            startCheck(resultCallback.asUpdateCallback(), showDialog = true, deliverOnMain = true)
        }
    }

    /**
     * Enables automatic update prompts on the Activity used to create this client.
     * Call on the main thread after configuring the endpoint and channel. The returned handle
     * supports manual checks and is closed automatically when the Activity is destroyed.
     */
    fun enableUpdatePrompts(): UpdatePrompts {
        check(Looper.myLooper() == Looper.getMainLooper()) { "enable update prompts on the main thread" }
        val activity = checkNotNull(activityReference?.get()) { "update prompts require an Activity" }
        val settings = configuration
        val endpoint = settings.requireEndpoint()
        val channelKey = settings.requireChannelKey()
        return UpdatePrompts.attach(activity, endpoint, channelKey, settings.locale) { callback ->
            startCheck(callback.asUpdateCallback(), showDialog = false, deliverOnMain = true, settings = settings)
        }
    }

    /** Compatibility entry point: shows an update dialog when created from an Activity. */
    fun launch() {
        checkAndShowUpdate()
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

    private fun startCheck(
        callback: UpdateCallback?,
        showDialog: Boolean,
        deliverOnMain: Boolean = false,
        settings: Configuration = configuration
    ): Call {
        val request = createRequest(settings)
        val delegate = (settings.httpClient ?: defaultClient).newCall(request)
        val call = UserCancellableCall(delegate)
        delegate.enqueue(Callback(this).apply {
            configure(
                callback,
                showDialog = showDialog,
                callbackExecutor = if (deliverOnMain) null else settings.callbackExecutor,
                presenter = settings.presenter,
                maxHeight = settings.maxHeight,
                dialogOptions = settings.dialogOptions,
                cancelledByUser = call.cancelledByUser,
                locale = settings.locale
            )
        })
        return call
    }

    @JvmSynthetic
    internal fun createRequest(): Request = createRequest(configuration)

    private fun Configuration.requireEndpoint(): HttpUrl =
        checkNotNull(endpoint) { "endpoint must be set before checking for updates" }

    private fun Configuration.requireChannelKey(): String =
        checkNotNull(channelKeys[buildType] ?: channelKeys[BUILD_TYPE]) {
            "channelKey is not configured for build type '$buildType'"
        }

    private fun createRequest(settings: Configuration): Request {
        val endpoint = settings.requireEndpoint()
        val channelKey = settings.requireChannelKey()
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
        maxHeight: ScreenHeight?,
        dialogOptions: UpdateDialogOptions = UpdateDialogOptions(),
        cancelledByUser: AtomicBoolean? = null,
        locale: Locale? = configuration.locale
    ) {
        resultDispatcher.dispatch(
            call, result, callback, showDialog, callbackExecutor, presenter,
            maxHeight ?: configuration.maxHeight, dialogOptions, cancelledByUser, locale
        )
    }

    /** Shows the built-in dialog. Calls from a background thread are posted to the main thread. */
    fun showAlert(version: String, changelog: String, installUrl: String) {
        val settings = configuration
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showAlertOnMain(version, changelog, installUrl, settings)
        } else {
            mainHandler.post {
                showAlertOnMain(version, changelog, installUrl, settings)
            }
        }
    }

    private fun showAlertOnMain(
        version: String,
        changelog: String,
        installUrl: String,
        settings: Configuration
    ) {
        val activity = activityReference?.get() ?: return
        DefaultUpdateDialog.show(
            activity, version, changelog, installUrl, settings.maxHeight, settings.dialogOptions, settings.locale
        )
    }

    private inline fun updateConfiguration(change: (Configuration) -> Configuration) {
        synchronized(configurationLock) {
            configuration = change(configuration)
        }
    }
}
