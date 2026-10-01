package im.ews.zealot.internal

import im.ews.zealot.ReleaseInfo
import im.ews.zealot.UpdateResult
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONException
import org.json.JSONArray
import org.json.JSONObject

/** Parses the response contract of GET /api/apps/latest, independently of transport and UI. */
internal object ReleaseResponseParser {
    fun parse(body: String): UpdateResult {
        val releases = JSONObject(body).optJSONArray("releases")
            ?: throw JSONException("Response does not contain a releases array")
        if (releases.length() == 0) return UpdateResult.UpToDate

        val release = releases.optJSONObject(0)
            ?: throw JSONException("The first release is not an object")
        val releaseVersion = versionText(release, "release_version")
        val buildVersion = versionText(release, "build_version")
        val installUrl = optionalText(release, "install_url")

        if (releaseVersion.isNullOrEmpty() || buildVersion.isNullOrEmpty()) {
            throw JSONException("Release version information is missing")
        }
        val parsedInstallUrl = installUrl?.toHttpUrlOrNull()
            ?: throw JSONException("Release install URL is missing or invalid")

        return UpdateResult.UpdateAvailable(
            ReleaseInfo(
                releaseVersion = releaseVersion,
                buildVersion = buildVersion,
                installUrl = parsedInstallUrl.toString(),
                changelog = buildChangelog(releases)
            )
        )
    }

    private fun buildChangelog(releases: JSONArray): String {
        val messages = mutableListOf<String>()
        for (releaseIndex in 0 until releases.length()) {
            val release = releases.optJSONObject(releaseIndex) ?: continue
            val entries = release.optJSONArray("changelog")
            val beforeRelease = messages.size
            if (entries != null) {
                for (entryIndex in 0 until entries.length()) {
                    val message = entries.optJSONObject(entryIndex)
                        ?.let { optionalText(it, "message") }.orEmpty()
                    if (message.isNotEmpty()) {
                        messages += "${(entryIndex + 1).toString().padStart(2, '0')}. $message"
                    }
                }
            }
            if (messages.size == beforeRelease) {
                val text = optionalText(release, "text_changelog").orEmpty()
                if (text.isNotEmpty()) messages += text
            }
        }
        return messages.joinToString("\n")
    }

    private fun versionText(release: JSONObject, key: String): String? = when (val value = release.opt(key)) {
        is String -> value.trim()
        is Number -> value.toString()
        else -> null
    }

    private fun optionalText(release: JSONObject, key: String): String? =
        (release.opt(key) as? String)?.trim()
}
