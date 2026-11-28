# Integrate the Android SDK

The SDK supports Android API 21 and later. Build the consuming app with
`compileSdk` 37 or newer. Use the stable dependency from the
[installation instructions](../README.md#install) and an HTTPS Zealot server. Declare
`android.permission.INTERNET` in the app's manifest.

## Gradle with Groovy

Add JitPack to the existing repository list in `settings.gradle`.

```groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri('https://jitpack.io') }
    }
}
```

In the application module's `build.gradle`, use the coordinate and version from
the [README](../README.md#install) in the form
`implementation 'com.github.tryzealot:zealot-android:<tag>'`.

## Connect your Zealot channel

1. Copy your server's base URL. If it is hosted under a path, include that path.
   The SDK appends `/api/apps/latest` itself.
2. Copy the **Channel KEY** from the Android channel you distribute to.
3. Run a build whose `applicationId` matches the channel's APK and upload a build
   with a higher `versionCode` to that channel.

For variants distributed to different channels, register a default key and
choose the active build type explicitly.

```kotlin
val zealot = Zealot.create(this)
    .setEndpoint("https://zealot.example.com")
    .setChannelKey("default-channel-key")
    .setChannelKey("beta-channel-key", "beta")
    .setBuildType(BuildConfig.BUILD_TYPE)
```

The SDK uses the default key when no key is registered for the selected type.
Choose keys for the variants you actually ship.

## Choose how to check for updates

| API | Use it when |
| --- | --- |
| `checkAndShowUpdate()` | Zealot should check once and display its built-in dialog. Pass a callback if the app also needs the result. |
| `checkForUpdate(callback)` | The app should handle the result and decide what UI to show. An application context works for this API. |
| `launch()` | An existing integration already uses the original fluent API. |
| [`enableUpdatePrompts()`](automatic-updates.md) | A development build should manage checks and prompts for an Activity. |

Create the client from a live Activity when Zealot should present UI. The
stable dialog opens the release's `install_url` using Android's
`ACTION_VIEW` intent. It only shows one built-in dialog per Activity and
closes it when the Activity window detaches. The app can use the returned
OkHttp `Call` to cancel a check when its screen closes.

### Kotlin with your own UI

```kotlin
val call = zealot.checkForUpdate { result ->
    when (result) {
        is UpdateResult.UpdateAvailable -> showUpdate(result.release)
        UpdateResult.UpToDate -> showCurrentVersion()
        is UpdateResult.Error -> showCheckError(result.error)
    }
}
```

Import `im.ews.zealot.UpdateResult` and replace the three handlers with your
app's UI. `ReleaseInfo` contains the release version, build number, install URL
and changelog. `UpdateError` contains a category, message and HTTP status when
available.

### Java with the built-in dialog

```java
import im.ews.zealot.Zealot;
import okhttp3.Call;

Call updateCall = Zealot.create(this)
    .setEndpoint("https://zealot.example.com")
    .setChannelKey("your-channel-key")
    .checkAndShowUpdate();
```

This example runs inside an Activity. For your own UI, pass an
`im.ews.zealot.UpdateCallback` to `checkForUpdate()` and implement
`onUpdateAvailable(ReleaseInfo)`, `onUpToDate()`, and `onError(UpdateError)`.
The typed `UpdateResultCallback` also works in Java.

### Lifecycle and transport

Callbacks run on the main thread by default. Cancelling the returned call
suppresses queued UI and callbacks. The shared client uses a 30-second timeout
and reports timeouts as network errors.

Set `setHttpClient(appOkHttpClient)` before checking to use your interceptors,
TLS configuration and timeouts. Use `setCallbackExecutor(executor)` to choose
where the result is delivered. Dialog presentation still runs on the main
thread. If the executor rejects a task before delivery, Zealot falls back to
the main thread. Each check captures its settings at the start, and the app
owns the supplied client and executor. Pass `null` to restore the defaults.

### Flutter

Add JitPack to the Flutter project's Android Gradle settings, declare the
dependency in the Android app module, and add the internet permission to
`android/app/src/main/AndroidManifest.xml`. Run the Kotlin quickstart in
`MainActivity : FlutterActivity` after `super.onCreate(savedInstanceState)`.
The built-in Android UI works without a Dart method channel.

## Troubleshoot integration

| Symptom | Check |
| --- | --- |
| Gradle cannot resolve the SDK | Add JitPack to dependency repositories and use the published tag shown in the README. |
| The server rejects the check | Use the server's base URL, including any hosting subpath, and the Android channel key. Leave the appended API path to the SDK. |
| Zealot reports that the app is current | Compare the installed variant's `applicationId` and `versionCode` with the channel. In the sample app, use a channel for `im.ews.zealot.example`. |
| The callback gets a result but no dialog is shown | Use `checkAndShowUpdate()` with a live Activity and check whether a custom presenter is configured. |

## Zealot API contract

The SDK sends a GET request to `/api/apps/latest` with `channel_key`,
`bundle_id`, `release_version`, `build_version`, and `sdk=android-<version>`.
Zealot filters releases against the installed version. An empty release list
means the app is up to date. HTTP failures, network failures and invalid
responses produce errors.

The first release supplies the new version and installation link. Notes from
matching releases are combined in server order, with duplicate messages
shown once. The SDK verifies a concrete `bundle_id` against the installed app.
It accepts legacy responses with a missing or null bundle ID and rejects
malformed IDs and responses over 1 MiB.
