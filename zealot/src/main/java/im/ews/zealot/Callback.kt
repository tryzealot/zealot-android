package im.ews.zealot

import android.app.Activity
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

class Callback(val zealot: Zealot): Callback {

    override fun onResponse(call: Call, response: Response) {
        response.use {
            if (!response.isSuccessful) { return }

            generateChangelog(response)
        }
    }

    override fun onFailure(call: Call, e: IOException) {
        // ignore
    }

    private fun generateChangelog(response: Response) {
        try {
            val body = response.body()!!.string()
            val reader = JSONObject(body)
            val releaseArray = reader.getJSONArray("releases")

            if (releaseArray.length() == 0) { return }
            var version = ""
            var installUrl = ""
            var changelogList = ArrayList<String>()

            for (x in 0 until releaseArray.length()) {
                val releaseItem = releaseArray[x] as JSONObject
                if (x.equals(0)) {
                    val releaseVersion = releaseItem.getString("release_version")
                    val buildVersion = releaseItem.getString("build_version")
                    version = "${releaseVersion} (${buildVersion})"
                    installUrl = releaseItem.getString("install_url")
                }

                val changelogArray = releaseItem.getJSONArray("changelog")
                for (y in 0 until changelogArray.length()) {
                    val changelogItem = changelogArray[y] as JSONObject
                    val index = "${(y + 1).toString().padStart(2, '0')}"
                    val message = changelogItem.getString("message")
                    changelogList.add("${index}. ${message}")
                }
            }
            val changelog = changelogList.joinToString("\n")

            (zealot.context as? Activity)?.runOnUiThread {
                zealot.showAlert(version, changelog, installUrl)
            }
        } catch (e: JSONException) {
            e.printStackTrace()
        }
    }
}
