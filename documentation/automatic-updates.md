# Automatic prompts and in-app updates

> These features are currently available in development builds. For the
> published stable SDK, use the [README quickstart](../README.md#quickstart).
> For a development build, start with the [sample app](development.md#run-the-sample).

Call `enableUpdatePrompts()` once from your main Activity. Zealot performs the
check when the Activity is in the foreground and displays a card with
expandable release notes when a release is available. **Open update** opens the
release link via Android. On return, Zealot checks again and skips a repeat
prompt for the build just opened during that Activity's lifetime.

```kotlin
val updates = Zealot.create(this)
    .setEndpoint("https://zealot.example.com")
    .setChannelKey("your-channel-key")
    .enableUpdatePrompts()

// Call this from your optional "Check for updates" action.
updates.checkNow()
```

The same methods work in Java and in the Android Activity of a Flutter app.
Call them on the main thread. Zealot removes the prompt when the Activity is
destroyed. Call `close()` to stop prompts sooner or before attaching another
channel or configuration to the same Activity.

**Later** defers the build on that channel for the current app process. The
choice survives a return from the background. A cold launch or a manual
check can show the release again. After the first successful check, another
automatic check can run when the Activity resumes at least an hour later.
A manual check can also report that the app is current or the check failed.
On the update sheet, Back and a tap outside the sheet also defer an available
update for the current session.

## Download APKs in the app

Enable this for builds you distribute through Zealot. For a Google Play
distribution, use the [Play update mechanism](https://developer.android.com/guide/playcore/in-app-updates).

```kotlin
val updates = Zealot.create(this)
    .setEndpoint("https://zealot.example.com")
    .setChannelKey("your-channel-key")
    .enableUpdatePrompts(downloadUpdates = true)
```

Declare the installation permission in the Zealot distribution's manifest.

```xml
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
```

If you want progress in the notification shade on Android 13 and later,
declare `android.permission.POST_NOTIFICATIONS` and request it in your app.
Users can still follow or cancel the download from the sheet when notifications
are disabled.

Use a signed universal APK of up to 512 MiB served over HTTPS. If a channel
requires browser login, the sheet offers to open the release link. If the app
initializes WorkManager itself, complete that setup before enabling APK updates.
Checks use the configured OkHttp client. Background APK downloads use their
own client and the app's Android network security configuration.

| Step | What the user sees |
| --- | --- |
| Download | Progress and a cancel action. One WorkManager job runs per server and channel. |
| Continue in background | The sheet closes. The compact action reopens it, and a notification shows progress when allowed. Back and a tap outside the sheet also minimize it. |
| Ready to install | Zealot checks the application ID, version code, and signing certificate. The verified APK can be reused after a restart for up to seven days. |
| Allow installation | Android may require the user to allow installations from the app in Settings. Return to the app and tap Install. |
| Install | Android asks for confirmation. Cancelling this dialog keeps the verified APK for a later attempt. |

If a successful check finds a different build or an empty release list, Zealot
discards the outdated APK. A failed check keeps the verified download available
for installation. Closing the screen before Android shows its confirmation
releases the installer session and keeps the APK for retry.

See [customization](customization.md#update-sheet) for the bottom sheet's
colors, typography, and language.

## Manage the startup check yourself

Use `createStartupCheck()` if you want Zealot's built-in dialog and prefer to
forward lifecycle events yourself. Keep one helper per Activity. It stops
checking after a successful result and can retry a failure on the next resume.

```kotlin
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
```

Pausing cancels a pending check and its queued results. Pass a callback to
`createStartupCheck(callback)` if you need the result on the main thread. An
available release uses the configured presenter or Zealot's built-in dialog.
