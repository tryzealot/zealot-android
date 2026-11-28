# Upgrade the SDK

The Kotlin and Java package remains `im.ews.zealot`. Keep
`com.github.tryzealot:zealot-android` for official releases and update the tag
in your Gradle dependency. See the [README](../README.md#install) for the
current coordinate and [GitHub Releases](https://github.com/tryzealot/zealot-android/releases)
for release-specific requirements.

## Upgrade from 0.2.0

- The SDK requires Android API 21 at runtime and `compileSdk` 37 to consume
  the current release. The stable SDK uses OkHttp 5.5.1. Check your app's
  toolchain and any existing OkHttp constraints before upgrading.
- Existing calls to `create`, `setEndpoint`, `setChannelKey`, `setBuildType`,
  `setAlertMaxHeight`, `launch`, `showAlert`, and the public `Callback` remain
  available.
- Pass the Zealot server's base URL and a nonempty channel key. Invalid inputs
  now fail at configuration time.
- The built-in UI uses Android's `AlertDialog` in the stable release. Use
  `checkForUpdate(callback)` for your own UI, or `checkAndShowUpdate()` for a
  cancellable check with the built-in dialog.

## Switch from a fork or a pinned commit

Switch the group back to `com.github.tryzealot` and select the stable tag. A
merge into upstream leaves any existing dependency on a fork pointing to the
fork. When switching from a development build, check the [published API](integration.md)
for the features available in that release.

See the [changelog](../CHANGELOG.md) for the history of changes.
