# Changelog

Check [GitHub Releases](https://github.com/tryzealot/zealot-android/releases)
for published versions.

## Unreleased

- Let apps personalize the in-app update sheet with an Android theme overlay.
- Keep the update sheet inside system insets and make long release notes fully scrollable.

- Add optional in-app APK download and installation for Zealot-distributed
  builds, with WorkManager, a minimizable bottom sheet, notification progress, APK
  verification, cancellation and restart recovery.
- Localize the built-in update UI in English, Simplified Chinese, German,
  French and Russian. Add `setLocale(Locale)` for a per-instance override.
- Add one-call update prompts for an Activity. The non-modal card supports
  light and dark themes, app branding, animated release notes, a session-only
  Later action, and manual checks.
- Add an opt-in update check that follows an Activity's resume and pause events.
  It retries on the next resume after an error or cancellation and stops after
  the first successful result.
- Verify JVM compatibility with the published `v0.3.1` AAR in CI and build
  independent Java and Kotlin consumers in debug and release with R8.

## 0.3.1

### What's Changed

- Update OkHttp to 5.5.1 and align MockWebServer with it. Update the build to
  Android Gradle Plugin 9.5.2 and Gradle 9.9.2, and the tests to Robolectric 4.18.1.
- Document the versioning and release process.
- Publish stable releases after CI verifies the tagged JitPack artifact in an independent Android app, including a release build with R8.
- Document the verified upstream Gradle dependency and link to release notes from the README.

### Upgrade

Apps using the accepted `0.3.0` build can replace the dependency SHA with
`v0.3.1`. Existing Kotlin and Java calls continue to work.

### Versions and Dependencies

- Minimum Android API: 21. Minimum `compileSdk` for consuming apps: 37.
- Published dependencies: OkHttp `5.5.1` and Kotlin standard library `2.2.10`.
- SDK build toolchain: JDK `17`, Gradle `9.9.2`, and Android Gradle Plugin `9.5.2`.

## 0.3.0 — Accepted upstream

- Add typed update results and cancellable checks for Kotlin and Java callers.
- Allow apps to supply their own OkHttp client, callback executor and update UI, or customize the built-in dialog.
- Validate update responses and release links, and distinguish network and server errors from an up-to-date result.
- Modernize the build and verify the published SDK in an independent Android app with R8. [PR #3](https://github.com/tryzealot/zealot-android/pull/3)

The minimum Android version is API 21 and the consuming app requires
`compileSdk` 37 or newer. The SDK now depends on OkHttp 5.5.0. The package
`im.ews.zealot` and legacy entry points remain available. See
[migration notes](README.md#migrating-from-020).

[Full Changelog](https://github.com/tryzealot/zealot-android/compare/2ee4b3a9e0ded0b670484b2343e1cfecb23958ab...e8a168e75f552a133111ffc3bd320967e04c1972)
