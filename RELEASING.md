# Releasing Zealot Android SDK

## Versioning and compatibility

- Reserve `vX.Y.Z` tags for stable releases. Store `X.Y.Z` in `version.properties`. It supplies `Zealot.VERSION` and the SDK identifier sent to the server. JitPack uses the tag or commit SHA as the dependency version.
- During `0.x`, patch releases contain compatible fixes, documentation, and release tooling. Use a new minor series for features or an explicitly documented breaking change. From `1.0.0`, follow [Semantic Versioning](https://semver.org/).
- Treat public Kotlin and Java APIs, documented behavior, Android and build requirements, and dependencies exposed by the API as part of the compatibility contract. Explain any required migration in release notes. Deprecate APIs with a replacement before removing them when practical.
- Official coordinates are `com.github.tryzealot:zealot-android:<tag>`. The package is `im.ews.zealot`. Development builds may use a pinned SHA. Fork candidates use the fork owner's coordinates.
- Keep published tags immutable. When a release regresses, point consumers to the last working version and publish the fix as a new release.

## Prepare a release

1. Start a short branch from upstream `master` and open a PR. Set `sdkVersion` in `version.properties` and update [CHANGELOG.md](CHANGELOG.md). Link user-facing changes to the corresponding PRs.
2. Wait for Android CI. It builds the SDK and sample, runs lint and unit tests, and compiles an independent Java consumer against the local Maven publication in debug and release with R8.
3. To test JitPack before tagging, build the independent consumer against the exact reviewed commit SHA on the fork. If runtime behavior changed, test that same SHA in a representative app.
4. After merge, a maintainer creates an annotated tag on the intended upstream commit after its branch CI passes. The tag push runs Android CI and validates the version, JitPack POM, and build commit. It then builds the independent consumer using the remote artifact.

From a checkout where `upstream` points to the original repository, set `tag`
and `commit` to the reviewed release tag and SHA, then push that tag.

```sh
git tag -a "$tag" "$commit" -m "Zealot Android SDK $tag"
git push upstream "refs/tags/$tag"
```

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
  ./gradlew -p publication-smoke -PzealotRepository=jitpack \
    --refresh-dependencies assembleDebug assembleRelease
```

For tags made before the tag checks were introduced, run the consumer build with
the published tag and verify its commit in JitPack's build record. The first planned
stable tag, `v0.3.0`, points to
`e8a168e75f552a133111ffc3bd320967e04c1972`, the merge commit for [#3](https://github.com/tryzealot/zealot-android/pull/3).

## Publish the GitHub Release

Create a draft in [GitHub Releases](https://github.com/tryzealot/zealot-android/releases)
for the existing tag. Use the tag as the release title. Prepare the body from
the changelog and merged PRs, following the structure used by
[Decompose releases](https://github.com/arkivanov/Decompose/releases).

| Section | What readers need |
| --- | --- |
| Opening note | A known regression or required migration goes first when it affects whether to upgrade. |
| What's Changed | User-facing changes with PR links and contributor credit. Group by topic when there are enough entries. |
| Installation | The exact Gradle dependency for this tag and the JitPack repository. Include upgrade steps when needed. |
| Versions and Dependencies | Android and compile SDK requirements, dependencies shipped to consumers, and the toolchain used to build the SDK. |
| New Contributors | Optional credit for first-time contributions. |
| Full Changelog | A compare link from the previous stable tag to the new tag. For the first tagged release, use the prior baseline commit. |

Publish the draft only after the tag's CI and JitPack verification pass.
Set releases on the current series as **Latest**. JitPack serves the SDK
artifact. The GitHub entry points to the exact dependency and records what changed.

If verification fails, inspect JitPack's build log. Retry verification for an
infrastructure failure. If the source needs a fix, use a new tag and keep the
failed draft unpublished. If a published release has a regression, add a note
with the affected versions, the workaround, and the replacement when available.
