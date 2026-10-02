# Zealot Android SDK

Android SDK for checking newer releases on a Zealot channel and opening the release page for installation. The SDK supports Kotlin and Java.

## Requirements

- Android API 21 or newer for the SDK.
- HTTPS endpoint for the Zealot server.
- A channel key for the Android application.
- The INTERNET permission (declared by the consuming application).

The example application uses AppCompat 1.8, which requires Android API 23 or newer.

## Install

The library is configured to publish the `zealot` module through JitPack. After
building a tag in your fork, add JitPack to the repositories used by your project:

~~~groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
~~~

Then add the SDK dependency. Replace YOUR_GITHUB_LOGIN with the owner of the fork and use a tag that exists in that fork:

~~~groovy
dependencies {
    implementation 'com.github.<YOUR_GITHUB_LOGIN>.zealot-android:zealot:v0.3.0'
}
~~~

The fork owner and version above are examples. Confirm that JitPack has built the selected tag before using it. For the original project, use tryzealot as the owner after the release is available there.

## Permissions

Declare internet access in the consuming app's manifest:

~~~xml
<uses-permission android:name="android.permission.INTERNET" />
~~~

## Check for an update

The non-UI API returns the result to the app. Callbacks run on the main thread by default, and the returned OkHttp call can be cancelled. Cancelling also prevents a result already queued for delivery from invoking the callback.

~~~kotlin
Zealot.create(this)
    .setEndpoint("https://zealot.example.com")
    .setChannelKey("your-channel-key")
    .setBuildType(BuildConfig.BUILD_TYPE)
    .checkForUpdate(object : UpdateCallback {
        override fun onUpdateAvailable(release: ReleaseInfo) {
            // Show your own UI or open release.installUrl.
        }

        override fun onUpToDate() {
            // No newer release is available for this app and channel.
        }

        override fun onError(error: UpdateError) {
            // Handle network, HTTP, or invalid-response errors.
        }
    })
~~~

If you keep the returned call, invoke cancel() when the check is no longer needed.

For one result handler, use the typed overload. Its three states work well with a
single `when` expression, and it has the same cancellation and callback-thread behavior:

~~~kotlin
val call = Zealot.create(this)
    .setEndpoint("https://zealot.example.com")
    .setChannelKey("your-channel-key")
    .checkForUpdate { result ->
        when (result) {
            is UpdateResult.UpdateAvailable -> showRelease(result.release)
            UpdateResult.UpToDate -> showCurrentVersion()
            is UpdateResult.Error -> showCheckError(result.error)
        }
    }
~~~

The request uses a shared OkHttp client and has a 30-second call timeout. A timeout is
reported as a network error through either callback style.

You can supply an existing OkHttp client to use your app's interceptors, TLS configuration, and timeouts. A callback executor can move result handling off the main thread. Configure these before starting a check; each check uses a snapshot of its settings.

~~~kotlin
val zealot = Zealot.create(this)
    .setEndpoint("https://zealot.example.com")
    .setChannelKey("your-channel-key")
    .setHttpClient(appOkHttpClient)
    .setCallbackExecutor(backgroundExecutor)

val call = zealot.checkForUpdate(callback)
~~~

The app owns the supplied client and executor. The SDK neither shuts them down nor changes their configuration. If the executor rejects a result before delivery, the SDK falls back to the main thread. A result is delivered at most once even if a faulty executor queues the task and then reports rejection.
Pass `null` to `setHttpClient`, `setCallbackExecutor`, or `setUpdatePresenter` to
restore the defaults for later checks. A check already in progress keeps its settings.

Java callers can use the same API directly:

~~~java
Zealot.create(this)
    .setEndpoint("https://zealot.example.com")
    .setChannelKey("your-channel-key")
    .setBuildType(BuildConfig.BUILD_TYPE)
    .checkForUpdate(new UpdateCallback() {
        @Override
        public void onUpdateAvailable(ReleaseInfo release) {
            // Open release.getInstallUrl() or show app-specific UI.
        }

        @Override
        public void onUpToDate() {
        }

        @Override
        public void onError(UpdateError error) {
            // Handle error.getCode() and error.getMessage().
        }
    });
~~~

## Show the built-in update dialog

The legacy fluent API remains available. Create the client from an Activity so the SDK can show a dialog when a newer release is found:

~~~kotlin
Zealot.create(this)
    .setEndpoint("https://zealot.example.com")
    .setChannelKey("your-channel-key")
    .setBuildType(BuildConfig.BUILD_TYPE)
    .launch()
~~~

If the screen owns the check, use `checkAndShowUpdate()` to keep its cancellable
`Call` and cancel it when the screen closes. This UI-only overload needs no callback.
The legacy `launch()` remains available for existing callers.

The dialog opens Zealot's install_url with an Android ACTION_VIEW intent. The SDK does not download or install APK files itself. Repeated results do not stack multiple built-in dialogs on the same screen, and the dialog closes when the Activity window detaches. Calls created with an application context can check releases through checkForUpdate, but cannot show the built-in dialog.

For small changes to the built-in dialog, set `UpdateDialogOptions` before checking.
Omitted text uses the localized library strings. Setting `cancelable = false`
disables Back and outside-tap dismissal; the Later button still dismisses the dialog.
Custom title text replaces the versioned default title. Pass `null` to restore
the defaults for later checks.

~~~kotlin
Zealot.create(this)
    .setEndpoint("https://zealot.example.com")
    .setChannelKey("your-channel-key")
    .setDialogOptions(
        UpdateDialogOptions(
            title = getString(R.string.your_update_title),
            updateButtonText = getString(R.string.your_install_label),
            laterButtonText = getString(R.string.your_later_label)
        )
    )
    .launch()
~~~

To use your own update UI while retaining `launch()`, set a presenter. Its `present` method runs on the main thread and receives a live Activity. `launch(callback)` additionally reports available, up-to-date, or error results through `UpdateCallback`. Use `checkAndShowUpdate(callback)` when the UI check needs both a result callback and a cancellable `Call`.

~~~kotlin
Zealot.create(this)
    .setEndpoint("https://zealot.example.com")
    .setChannelKey("your-channel-key")
    .setUpdatePresenter { activity, release ->
        // Show your app's update sheet using release.installUrl and release.changelog.
    }
    .checkAndShowUpdate(callback)
~~~

For multiple build types, register one key per type; the default key is used as a fallback:

~~~kotlin
Zealot.create(this)
    .setEndpoint("https://zealot.example.com")
    .setChannelKey("beta-channel-key", "beta")
    .setChannelKey("test-channel-key", "test")
    .setBuildType(BuildConfig.BUILD_TYPE)
    .launch()
~~~

The same methods are callable from Java. Implement UpdateCallback with the three methods shown above, or use the fluent launch() API.

## Zealot API

The SDK calls GET /api/apps/latest and sends channel_key, bundle_id, release_version, and build_version. Zealot filters the channel's releases against the installed app version. The current SDK also sends sdk=android-<sdk-version> as client metadata. A successful response with no releases is reported as up to date. Non-2xx responses, network failures, and invalid response data are reported through onError.

The first release supplies the install URL and version; for the versioned request
sent by this SDK, Zealot returns newer releases in newest-first order. Changelog
entries from matching releases are combined in
that order, with exact duplicate messages shown once. When a release includes a
concrete bundle_id, the SDK checks it against the installed app before showing
an update. Responses without this field remain supported. Responses larger than
1 MiB are rejected.

## Migrating from 0.2.0

The original `create`, `setEndpoint`, `setChannelKey`, `setBuildType`, `setAlertMaxHeight`, `launch`, `showAlert`, and `Callback` entry points remain available. The SDK now requires Android API 21 or newer; applications supporting API 14–20 cannot use this release. The built-in dialog uses the platform `AlertDialog`, with English strings by default and the original Chinese strings for Chinese locales. Endpoint and channel-key setters reject invalid input immediately. To handle update and error states explicitly, use `checkForUpdate(callback)`; for a cancellable check that also shows update UI, use `checkAndShowUpdate(callback)`.

## Development

Use JDK 17 or newer to run the Gradle build. The example app lets you enter your own Zealot server URL and Android channel key at runtime. It shows the update result and cancels an active request if its Activity closes; no key is committed to the repository. Its debug build permits HTTP for local server testing. Use HTTPS for a release build.

Build the library and sample app:

~~~shell
./gradlew :zealot:assembleDebug :app:assembleDebug
~~~

Run the library tests and lint checks:

~~~shell
./gradlew :zealot:testDebugUnitTest :zealot:lint :app:lint
~~~

To verify the published AAR as an independent Java consumer, publish locally and build
the small `publication-smoke` project. Its release build runs R8. Both builds read
the SDK version from `version.properties`:

~~~shell
./gradlew :zealot:publishToMavenLocal
./gradlew -p publication-smoke assembleDebug assembleRelease
~~~

You can also simulate JitPack's tag and fork coordinates locally by setting `GROUP`
and `VERSION` for both commands. For example, use
`GROUP=com.github.YourName VERSION=v0.3.0`; the consumer will then resolve
`com.github.YourName.zealot-android:zealot:v0.3.0` from Maven Local.

The debug consumer also runs on Android API 21. To exercise it on an emulator,
start `python3 publication-smoke/mock_server.py` in another terminal, then run:

~~~shell
adb -s <serial> reverse tcp:18766 tcp:18766
adb -s <serial> install -r publication-smoke/build/outputs/apk/debug/zealot-publication-smoke-debug.apk
adb -s <serial> shell am start -n im.ews.zealot.publicationsmoke/.ConsumerActivity \
  --es zealot.endpoint http://127.0.0.1:18766/available \
  --es zealot.channel_key demo
~~~

The screen shows `Update available: 2.0`. Change `/available` to `/current` or
`/error` to exercise the other outcomes. Only this test app's debug variant
permits HTTP.

To verify the live Zealot contract against a channel with at least two Android releases
of the same app, set the endpoint and channel key for one local test run:

~~~shell
ZEALOT_TEST_ENDPOINT=https://your-zealot.example.com \
ZEALOT_TEST_CHANNEL_KEY=your-channel-key \
./gradlew :zealot:testDebugUnitTest --tests 'im.ews.zealot.ZealotLiveApiTest'
~~~

The test is skipped when these variables are absent. It finds the newest release and
an earlier release of the same app, then checks that the older version sees an update,
the newest version does not, and an invalid channel key fails. It also calls the public
`Zealot.checkForUpdate` API with a simulated installed app at the older version.
The channel key is not written to source files or test reports.

The example app sends its own application ID as `bundle_id`. To exercise an existing
channel in the example app, that channel must contain releases for the example app's
application ID; entering a channel key for a different app correctly reports no update.

## License

MIT. See [LICENSE](LICENSE).
