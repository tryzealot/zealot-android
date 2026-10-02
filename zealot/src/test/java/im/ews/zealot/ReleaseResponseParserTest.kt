package im.ews.zealot

import im.ews.zealot.internal.ReleaseResponseParser
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReleaseResponseParserTest {
    @Test
    fun emptyReleasesMeansUpToDate() {
        assertEquals(UpdateResult.UpToDate, ReleaseResponseParser.parse("""{"releases":[]}"""))
    }

    @Test
    fun parsesReleaseAndChangelogEntries() {
        val result = ReleaseResponseParser.parse(
            """{"releases":[{"release_version":"2.0","build_version":12,
               "install_url":"https://zealot.example.com/releases/12/install",
               "changelog":[{"message":"Fixed login"},{"message":" Improved sync "}]}]}"""
        )

        assertTrue(result is UpdateResult.UpdateAvailable)
        val release = (result as UpdateResult.UpdateAvailable).release
        assertEquals("2.0", release.releaseVersion)
        assertEquals("12", release.buildVersion)
        assertEquals("01. Fixed login\n02. Improved sync", release.changelog)
        assertEquals("https://zealot.example.com/releases/12/install", release.installUrl)
    }

    @Test
    fun fallsBackToTextChangelog() {
        val result = ReleaseResponseParser.parse(
            """{"releases":[{"release_version":"2.0","build_version":"12",
               "install_url":"https://zealot.example.com/install","changelog":[],
               "text_changelog":"Release notes"}]}"""
        )

        assertEquals("Release notes", (result as UpdateResult.UpdateAvailable).release.changelog)
    }

    @Test
    fun combinesChangelogsFromMultipleReleasesLikeTheOriginalSdk() {
        val result = ReleaseResponseParser.parse(
            """{"releases":[{"release_version":"3.0","build_version":"30",
               "install_url":"https://zealot.example.com/install/30",
               "changelog":[{"message":"Latest change"}]},
               {"release_version":"2.0","build_version":"20",
               "install_url":"https://zealot.example.com/install/20",
               "changelog":[{"message":"Earlier change"}]}]}"""
        )

        val release = (result as UpdateResult.UpdateAvailable).release
        assertEquals("3.0", release.releaseVersion)
        assertEquals("01. Latest change\n01. Earlier change", release.changelog)
    }

    @Test
    fun repeatedChangelogMessagesAppearOnlyOnceInNewestFirstOrder() {
        val result = ReleaseResponseParser.parse(
            """{"releases":[{"release_version":"3.0","build_version":"30",
               "install_url":"https://zealot.example.com/install/30",
               "changelog":[{"message":"Fixed login"},{"message":"Improved sync"}]},
               {"release_version":"2.0","build_version":"20",
               "install_url":"https://zealot.example.com/install/20",
               "changelog":[{"message":" Fixed login "},{"message":"Added export"}]}]}"""
        )

        assertEquals(
            "01. Fixed login\n02. Improved sync\n02. Added export",
            (result as UpdateResult.UpdateAvailable).release.changelog
        )
    }

    @Test
    fun duplicateStructuredEntriesDoNotTriggerTextFallback() {
        val result = ReleaseResponseParser.parse(
            """{"releases":[{"release_version":"3.0","build_version":"30",
               "install_url":"https://zealot.example.com/install/30",
               "changelog":[{"message":"Fixed login"}]},
               {"release_version":"2.0","build_version":"20",
               "install_url":"https://zealot.example.com/install/20",
               "changelog":[{"message":"Fixed login"}],
               "text_changelog":"Fixed login"}]}"""
        )

        assertEquals(
            "01. Fixed login",
            (result as UpdateResult.UpdateAvailable).release.changelog
        )
    }

    @Test(expected = JSONException::class)
    fun rejectsLatestReleaseForDifferentApp() {
        ReleaseResponseParser.parse(
            """{"releases":[{"bundle_id":"com.other.app","release_version":"2.0",
               "build_version":"12","install_url":"https://zealot.example.com/install"}]}""",
            expectedBundleId = "com.example.app"
        )
    }

    @Test
    fun ignoresOtherAppsWhenCombiningChangelog() {
        val result = ReleaseResponseParser.parse(
            """{"releases":[{"bundle_id":"com.example.app","release_version":"3.0",
               "build_version":"30","install_url":"https://zealot.example.com/install/30",
               "changelog":[{"message":"Latest change"}]},
               {"bundle_id":"com.other.app","changelog":[{"message":"Unrelated change"}]},
               {"bundle_id":"com.example.app","changelog":[{"message":"Earlier change"}]}]}""",
            expectedBundleId = "com.example.app"
        )

        assertEquals(
            "01. Latest change\n01. Earlier change",
            (result as UpdateResult.UpdateAvailable).release.changelog
        )
    }

    @Test
    fun olderResponsesWithoutBundleIdRemainSupported() {
        val result = ReleaseResponseParser.parse(
            """{"releases":[{"release_version":"2.0","build_version":"12",
               "install_url":"https://zealot.example.com/install"}]}""",
            expectedBundleId = "com.example.app"
        )

        assertTrue(result is UpdateResult.UpdateAvailable)
    }

    @Test(expected = JSONException::class)
    fun rejectsNonHttpInstallUrl() {
        ReleaseResponseParser.parse(
            """{"releases":[{"release_version":"2.0","build_version":"12",
               "install_url":"javascript:alert(1)"}]}"""
        )
    }

    @Test(expected = JSONException::class)
    fun rejectsMissingReleasesArray() {
        ReleaseResponseParser.parse("""{"message":"ok"}""")
    }

    @Test(expected = JSONException::class)
    fun rejectsNullReleaseVersion() {
        ReleaseResponseParser.parse(
            """{"releases":[{"release_version":null,"build_version":12,
               "install_url":"https://zealot.example.com/install"}]}"""
        )
    }

    @Test
    fun nullChangelogEntriesAreIgnored() {
        val result = ReleaseResponseParser.parse(
            """{"releases":[{"release_version":"2.0","build_version":12,
               "install_url":"https://zealot.example.com/install",
               "changelog":[{"message":null},{"message":"Fixed sync"}],
               "text_changelog":null}]}"""
        )

        assertEquals("02. Fixed sync", (result as UpdateResult.UpdateAvailable).release.changelog)
    }
}
