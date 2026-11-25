# Changelog

Check [GitHub Releases](https://github.com/tryzealot/zealot-android/releases)
for published versions.

## 0.3.1 — Unreleased

- Update OkHttp to 5.5.1 and align MockWebServer with it. Update the build to
  Android Gradle Plugin 9.5.2 and Gradle 9.9.2, and the tests to Robolectric 4.18.1.
- Document the versioning and release process.
- Validate release tags against the SDK version and build an independent Android app against each tagged JitPack publication.
- Document the verified upstream Gradle dependency and link to release notes from the README.

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
