# Develop and validate the SDK

Use JDK 17 or later and the Android SDK installed with the compile SDK needed
by the project. The checked-in Gradle wrapper builds the SDK and sample.

```sh
./gradlew :zealot:assembleDebug :app:assembleDebug
./gradlew :zealot:testDebugUnitTest :zealot:lint :app:lint
```

## Run the sample

Install the debug `app` build on Android API 23 or later. Enter the server's
base URL and the Android channel key. Tap **Enable update prompts** or
**Check now**. The sample lets you preview a locale, custom colors and a
custom font. Its package is `im.ews.zealot.example`, so the channel must contain
APKs for that package.

The debug sample permits HTTP for local checks. To exercise actual in-app
installation, build two versions with the same signing key and application ID.

```sh
./gradlew :app:assembleDebug -PsampleVersionCode=1
cp app/build/outputs/apk/debug/app-debug.apk /tmp/zealot-sample-v1.apk
./gradlew :app:assembleDebug -PsampleVersionCode=2
```

Install the saved build 1 and serve build 2 from the test Zealot channel over
HTTPS. Verify download, installation confirmation, cancellation and retry.
Changing only the build code is sufficient for this test.

## Test as an SDK consumer

Publish the SDK locally and assemble the independent Java and Kotlin sample.
The release build runs R8.

```sh
./gradlew :zealot:publishToMavenLocal
./gradlew -p publication-smoke assembleDebug assembleRelease
```

Both commands read the version from `version.properties`. To test different
coordinates through Maven Local, set the same `GROUP` and `VERSION` for both
commands. For a pinned SHA or a tag on JitPack, see the
[release guide](../RELEASING.md#prepare-a-release).

The debug consumer runs on Android API 21. Start
`python3 publication-smoke/mock_server.py` in another terminal, then run these
commands with your emulator serial.

```sh
adb -s <serial> reverse tcp:18766 tcp:18766
adb -s <serial> install -r publication-smoke/build/outputs/apk/debug/zealot-publication-smoke-debug.apk
adb -s <serial> shell am start -n im.ews.zealot.publicationsmoke/.ConsumerActivity \
  --es zealot.endpoint http://127.0.0.1:18766/available \
  --es zealot.channel_key demo
```

The expected text is `Update available: 2.0`. Change `/available` to
`/current` or `/error` to exercise the other outcomes.

## Verify a live Zealot channel

Pick a test channel with at least two Android releases of the same app.
Supply its settings through environment variables.

```sh
ZEALOT_TEST_ENDPOINT=https://your-zealot.example.com \
ZEALOT_TEST_CHANNEL_KEY=your-channel-key \
./gradlew :zealot:testDebugUnitTest --tests 'im.ews.zealot.ZealotLiveApiTest'
```

The test checks that the older build sees an update, the newest checks as
current, and an invalid key fails. It also exercises the public
`checkForUpdate()` call. It skips when the variables are absent and reruns
when both are supplied. Sanitized fixtures in
[`zealot/src/test/resources/fixtures`](../zealot/src/test/resources/fixtures/)
cover the same response categories without network access.

See [RELEASING.md](../RELEASING.md) for the release cycle.
