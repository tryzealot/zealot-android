# Releasing Zealot Android SDK

## Versioning and compatibility

- Reserve `vX.Y.Z` tags for stable releases. Store `X.Y.Z` in `version.properties`. It supplies `Zealot.VERSION` and the SDK identifier sent to the server. JitPack uses the tag or commit SHA as the dependency version.
- During `0.x`, patch releases contain compatible fixes, documentation, and release tooling. Use a new minor series for features or an explicitly documented breaking change. From `1.0.0`, follow [Semantic Versioning](https://semver.org/).
- Treat public Kotlin and Java APIs, documented behavior, Android and build requirements, and dependencies exposed by the API as part of the compatibility contract. Explain any required migration in release notes. Deprecate APIs with a replacement before removing them when practical.
- Official coordinates are `com.github.tryzealot:zealot-android:<tag>`. The package is `im.ews.zealot`. Development builds may use a pinned SHA with the repository owner's coordinates.
- Keep published tags immutable. When a release regresses, point consumers to the last working version and publish the fix as a new release.

## Prepare a release

1. Start a short branch from upstream `master`. In the release PR, increase `sdkVersion` in `version.properties` and add a matching `## X.Y.Z` section to [CHANGELOG.md](CHANGELOG.md). Link user-facing changes to the corresponding PRs.
2. Wait for Android CI. It builds the SDK and sample, checks the public JVM signatures against the last stable AAR baseline, runs lint and unit tests, and compiles independent Java and Kotlin consumers against the local Maven publication in debug and release with R8.
3. To test JitPack before tagging, build the independent consumer against the exact reviewed commit SHA on the fork. If runtime behavior changed, test that same SHA in a representative app.
4. After merge, wait for the Android workflow on upstream `master`. Its release job creates an annotated tag on the commit that passed CI, verifies that JitPack built that exact commit, and builds the independent consumer in debug and release with R8 before publishing.

To verify an untagged commit, set `owner` to the fork owner and `commit` to the
full SHA.

```sh
GROUP="com.github.$owner" VERSION="$commit" \
  ./gradlew -p publication-smoke -PzealotRepository=jitpack assembleDebug assembleRelease
```

To verify a stable release, set `owner` to the releasing repository's owner,
`commit` to the tagged SHA, and `tag` to the published tag.

```sh
python3 .github/scripts/verify-release.py "$tag" --jitpack-owner "$owner" --commit "$commit"
GROUP="com.github.$owner" VERSION="$tag" \
  ./gradlew -p publication-smoke -PzealotRepository=jitpack assembleDebug assembleRelease
```

## Maintain the public API baseline

CI compares the new release AAR with the signatures in
[`zealot/api/v0.3.1.txt`](zealot/api/v0.3.1.txt). The snapshot contains public
SDK types and their JVM members, including Kotlin default-argument methods and
constructors used by precompiled callers. The check permits new members on
these types. The independent consumer builds also exercise Java and Kotlin
source usage, including named arguments and exhaustive result handling in Kotlin.

After a new stable release, generate its snapshot from the published AAR
using JDK 17. Pass the existing public class names and add the public types
introduced in the release, including nested types. Record the release
coordinate, commit and AAR SHA-256 in the snapshot, then update the CI path.

```sh
python3 .github/scripts/check-api.py "$published_aar" --snapshot \
  $(awk -F ' [|] ' '!/^#/ { print $1 }' zealot/api/v0.3.1.txt | sort -u) \
  im.ews.zealot.StartupUpdateCheck
```

## Automatic publication

The release job runs only after a successful push build on
`tryzealot/zealot-android:master`. Its repository `GITHUB_TOKEN` needs
`contents: write`, and repository rules must allow it to create release tags.
The introduction of the workflow releases `0.3.1`. Subsequent releases require
a version increase. Ordinary pushes with the same version skip publication.

GitHub does not start a tag-push workflow when `GITHUB_TOKEN` creates the tag,
so the release job runs JitPack and consumer verification itself.

The workflow uses the matching changelog section as the body of the
[GitHub Release](https://github.com/tryzealot/zealot-android/releases), with
the tag as the title. Write the section using the structure below, inspired by
[Decompose releases](https://github.com/arkivanov/Decompose/releases).

| Section | What readers need |
| --- | --- |
| Opening note | A known regression or required migration goes first when it affects whether to upgrade. |
| What's Changed | User-facing changes with PR links and contributor credit. Group by topic when there are enough entries. |
| Upgrade | Any steps required to move from the previous release. |
| Versions and Dependencies | Android and compile SDK requirements, dependencies shipped to consumers, and the toolchain used to build the SDK. |
| New Contributors | Optional credit for first-time contributions. |

Use `###` for headings within the version's changelog section. The workflow
adds **Installation** with the exact Gradle dependency and a **Full Changelog**
link from the previous stable tag. The first release compares against the
accepted `0.3.0` commit from [#3](https://github.com/tryzealot/zealot-android/pull/3).

## Recover a failed release

For a transient failure, rerun the failed jobs in the original Android workflow
run. The release job reuses an existing tag on the same commit and skips an
already published release. It stops if the tag points elsewhere or a manual
release draft needs review.

If a tagged build needs a code fix, increase the version in a new PR. If a
published release has a regression, add a note with the affected versions,
the workaround, and the replacement when available.
