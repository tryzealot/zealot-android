# Zealot Android SDK

Android SDK for checking newer releases on a Zealot channel, opening the release page, and optionally downloading APK updates in the background. The SDK supports Kotlin and Java.

## Requirements

- Android API 21 or newer for the SDK.
- `compileSdk` 37 or newer in the consuming application (required by the published AAR metadata).
- HTTPS endpoint for the Zealot server.
- A channel key for the Android application.
- The INTERNET permission (declared by the consuming application).

The example application uses AppCompat 1.8, which requires Android API 23 or newer.

## Install

Add JitPack to the repositories used by your project:

~~~groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
~~~

Use the dependency version shown in [GitHub Releases](https://github.com/tryzealot/zealot-android/releases).

~~~groovy
dependencies {
    implementation 'com.github.tryzealot:zealot-android:v0.3.1'
}
~~~

## Releases

[GitHub Releases](https://github.com/tryzealot/zealot-android/releases) lists stable
versions tagged `vX.Y.Z`. Each entry contains the Gradle dependency, changes,
build requirements, and any upgrade steps.
See the [changelog](CHANGELOG.md) for unreleased changes and the
[release guide](RELEASING.md) for versioning and publishing.

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
reported as a network error through either callback style, even though OkHttp marks
its internal call as cancelled. Calling `cancel()` on the returned `Call` suppresses
the result instead.

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

The dialog opens Zealot's `install_url` with an Android `ACTION_VIEW` intent. For background downloads and installation, use [automatic prompts with APK updates enabled](#download-updates-inside-the-app). Repeated results do not stack multiple built-in dialogs on the same screen, and the dialog closes when the Activity window detaches. Calls created with an application context can check releases through `checkForUpdate`, but cannot show the built-in dialog.

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

## Automatic update prompts

`enableUpdatePrompts()` is available in the development version. Call it once
from your main Activity after configuring the SDK. Zealot attaches to that
Activity's lifecycle and shows a non-modal prompt when an update is available.

~~~kotlin
val updates = Zealot.create(this)
    .setEndpoint("https://zealot.example.com")
    .setChannelKey("your-channel-key")
    .enableUpdatePrompts()

findViewById<View>(R.id.check_for_updates).setOnClickListener {
    updates.checkNow()
}
~~~

The prompt shows the app icon, name and new version. Release notes expand
inside the card when available. The animations follow Android's system setting.
**Later** hides that build on this channel for the current app process.
Returning from the background preserves the choice. A cold launch can show
the update again. A manual check can show a deferred build and reports
whether the app is up to date or the check failed. **Open update** hands the link
to Android. When you return to the Activity, Zealot checks again and suppresses
a repeat prompt for the build you just opened during that Activity's lifetime.

The same API works from Java and from a Flutter app's Android Activity.
Lifecycle handling and the light and dark prompt palettes are built in.
Automatic checks run on the foreground Activity and may run again on a later
resume after one hour. Its handle closes when the Activity is destroyed.
Call `close()` to turn off prompts sooner or before attaching a different
channel to the same Activity. Call these methods on the main thread.

## Download updates inside the app

Enable background APK updates in a build distributed through Zealot:

~~~kotlin
val updates = Zealot.create(this)
    .setEndpoint("https://zealot.example.com")
    .setChannelKey("your-channel-key")
    .enableUpdatePrompts(downloadUpdates = true)
~~~

Declare the installation permission in that build's manifest:

~~~xml
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
~~~

Use a channel serving a signed universal APK of up to 512 MiB over HTTPS. The
**Download** action queues one WorkManager job per channel. The update sheet
shows progress and lets you cancel or choose **Continue in background**. The
compact update action reopens the sheet and changes to **Update ready to install**
when verification finishes. Back and tapping outside during a download also
minimize the sheet. From **Update available**, **Later**, Back, and
tapping outside the sheet defer that release for the current app session.
Zealot verifies the package, signing certificate and newer build number before
offering **Install**.
The ready file can be used after an app restart for up to seven days. Android
may require permission for this installation source and will ask for confirmation.
If the card shows **Allow installation**, use it to open Android settings.
Allow the source, return to the app, and tap **Install**.
The SDK continues to use the release link on channels that require browser login.
Builds distributed through Google Play must use Google's update mechanism.

To show download progress in the notification shade on Android 13 and later,
declare `POST_NOTIFICATIONS` and obtain the user's permission in your
application. The in-app progress and cancel action also work when notifications
are disabled.

`enableUpdatePrompts()` retains the link-based behavior. The library
uses WorkManager 2.10.5 to support Android 21. If the app configures
WorkManager manually, install its configuration before enabling APK updates.
To test the in-app path with the debug sample, assemble two versions using
`./gradlew :app:assembleDebug -PsampleVersionCode=1` and
`./gradlew :app:assembleDebug -PsampleVersionCode=2`. Serve the second APK from
an HTTPS Zealot channel and run the first on an emulator.

### Customize the update sheet colors

Define an Android style in the app's resources and pass it before attaching
in-app update prompts.

~~~xml
<style name="AppUpdateSheet">
    <item name="zealotSheetAccent">@color/update_accent</item>
    <item name="zealotSheetOnAccent">@color/update_on_accent</item>
    <item name="zealotSheetNotesSurface">@color/update_notes_surface</item>
</style>
~~~

~~~kotlin
val updates = Zealot.create(this)
    .setEndpoint("https://zealot.example.com")
    .setChannelKey("your-channel-key")
    .setUpdateSheetTheme(R.style.AppUpdateSheet)
    .enableUpdatePrompts(downloadUpdates = true)
~~~

| Attribute | Applies to |
| --- | --- |
| `zealotSheetSurface` | Panel background |
| `zealotSheetNotesSurface` | Release notes background |
| `zealotSheetOnSurface` | Headings |
| `zealotSheetOnSurfaceVariant` | Supporting text, notes and the percentage label |
| `zealotSheetAccent` | Primary button background, secondary actions and progress indicator |
| `zealotSheetOnAccent` | Primary button text |
| `zealotSheetProgressTrack` | Progress track |
| `zealotSheetRipple` | Button press feedback |

Omitted colors use Zealot's light or dark palette. Define referenced colors in
`values` and `values-night` for separate day and night palettes. From Java,
use the same setter. Pass `0` to restore all defaults before attaching prompts.
The overlay applies to the in-app sheet. The compact status, standalone update
dialog and system installer have their own appearance.

## Choose the SDK language

Zealot's built-in dialog and automatic prompts follow the Activity's language
preferences. The SDK provides English, Simplified Chinese, German, French and
Russian. Android loads English resources if no supported language matches.

Call `setLocale(Locale)` to override the language for one Zealot instance.
Pass `null` to follow the Activity again. For example, use
`zealot.setLocale(Locale.forLanguageTag("fr-FR"))` from Kotlin or Java.
Set the locale before starting a check or calling `enableUpdatePrompts()`.
To apply a new locale to automatic prompts, close the current handle and
enable prompts again. The override leaves the app's language, custom button
labels, and release notes supplied by your Zealot server as they are.

If your app filters Android resources by language, include the SDK languages you
want to display. The example app's language selector applies to its update
prompt and leaves the rest of the screen in the app's language.

## Manage the startup check yourself

`createStartupCheck()` is available in the development version. Configure Zealot
with the Activity and keep one helper per Activity instance. Call the helper
from the Activity's lifecycle methods on the main thread.

~~~kotlin
class MainActivity : Activity() {
    private val updateCheck by lazy {
        Zealot.create(this)
            .setEndpoint("https://zealot.example.com")
            .setChannelKey("your-channel-key")
            .createStartupCheck()
    }

    override fun onPostResume() {
        super.onPostResume()
        updateCheck.onResume()
    }

    override fun onPause() {
        updateCheck.onPause()
        super.onPause()
    }
}
~~~

An available release uses the configured presenter or the built-in dialog.
The helper stops checking after the first successful result. A failed check
retries on the next resume. Pausing cancels an active request and suppresses
any queued result or dialog from it. You can pass a result callback to
`createStartupCheck(callback)` to receive outcomes on the main thread.
Both overloads are also available in Java.

## Zealot API

The SDK calls GET /api/apps/latest and sends channel_key, bundle_id, release_version, and build_version. Zealot filters the channel's releases against the installed app version. The current SDK also sends sdk=android-<sdk-version> as client metadata. A successful response with no releases is reported as up to date. Non-2xx responses, network failures, and invalid response data are reported through onError.

The first release supplies the install URL and version; for the versioned request
sent by this SDK, Zealot returns newer releases in newest-first order. Changelog
entries from matching releases are combined in
that order, with exact duplicate messages shown once. When a release includes a
concrete bundle_id, the SDK checks it against the installed app before showing
an update. Responses without this field or with `null` remain supported;
malformed bundle IDs are rejected. Responses larger than 1 MiB are rejected.

## Migrating from 0.2.0

The original `create`, `setEndpoint`, `setChannelKey`, `setBuildType`, `setAlertMaxHeight`, `launch`, `showAlert`, and `Callback` entry points remain available. The SDK now requires Android API 21 or newer; applications supporting API 14–20 cannot use this release. The built-in dialog uses the platform `AlertDialog`, with English strings by default and the original Chinese strings for Chinese locales. Endpoint and channel-key setters reject invalid input immediately. To handle update and error states explicitly, use `checkForUpdate(callback)`; for a cancellable check that also shows update UI, use `checkAndShowUpdate(callback)`.

The Kotlin/Java package remains `im.ews.zealot`, so existing imports do not change.
After an upstream release, consumers of `com.github.tryzealot:zealot-android`
can keep that group and artifact and select the new upstream tag. Using a fork
before upstream acceptance changes the dependency owner to that fork's account;
merging the upstream PR does not redirect existing fork dependencies.

This release also requires `compileSdk` 37 or newer and upgrades the transitive
OkHttp dependency to the 5.5 series. Applications with an older Android build configuration
or their own OkHttp integration should verify those requirements before upgrading.

## Development

The public `Zealot` class holds a snapshot of each check's configuration. Request
construction, response parsing, result delivery, and the optional dialog live in
separate internal components, so non-UI checks do not depend on presentation.

Use JDK 17 or newer to run the Gradle build. In the example app, enter a Zealot
server URL and Android channel key. Tap **Enable update prompts** to preview
automatic prompts or **Check now** to run a manual check. The debug build
permits HTTP for local server testing. Use HTTPS for a release build.

Build the library and sample app:

~~~shell
./gradlew :zealot:assembleDebug :app:assembleDebug
~~~

Run the library tests and lint checks:

~~~shell
./gradlew :zealot:testDebugUnitTest :zealot:lint :app:lint
~~~

To verify the published AAR with independent Java and Kotlin consumers, publish locally and build
the small `publication-smoke` project. Its release build runs R8. Both builds read
the SDK version from `version.properties`:

~~~shell
./gradlew :zealot:publishToMavenLocal
./gradlew -p publication-smoke assembleDebug assembleRelease
~~~

You can also simulate JitPack's tag and fork coordinates locally by setting `GROUP`
and `VERSION` for both commands. The consumer uses those coordinates from Maven Local.

The same consumer can build against a pinned commit from JitPack. This is useful
for testing a branch before it is ready to tag.

~~~shell
GROUP=com.github.tryzealot VERSION=e8a168e75f552a133111ffc3bd320967e04c1972 \
./gradlew -p publication-smoke -PzealotRepository=jitpack assembleDebug assembleRelease
~~~

This mode excludes Maven Local and resolves the SDK group only from JitPack.

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
`/error` to exercise the other outcomes. On an emulator running API 23 or newer,
enter the same URL and key in the debug example app to test the automatic or
manual prompt.

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
When both variables are supplied, the test task runs again on each invocation rather
than reusing an earlier result. Credentials are not registered as Gradle cache inputs.
Sanitized responses in `zealot/src/test/resources/fixtures` also exercise the
available, current, and invalid-channel contract without network access.

The example app sends its own application ID as `bundle_id`. To exercise an existing
channel in the example app, that channel must contain releases for the example app's
application ID; entering a channel key for a different app correctly reports no update.

## License

MIT. See [LICENSE](LICENSE).
