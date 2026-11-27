package im.ews.zealot.publicationsmoke

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import im.ews.zealot.UpdateDialogOptions
import im.ews.zealot.UpdateResult
import im.ews.zealot.Zealot
import okhttp3.Call

/** Exercises Kotlin named arguments, defaults and exhaustive results against the published AAR. */
class KotlinConsumerActivity : Activity() {
    private var call: Call? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this)
        setContentView(status)

        val options = UpdateDialogOptions(updateButtonText = "Install")
            .copy(laterButtonText = "Later")
        call = Zealot.create(this)
            .setEndpoint(endpoint = intent.getStringExtra(ConsumerActivity.EXTRA_ENDPOINT)
                ?: "https://zealot.example.com")
            .setChannelKey(channelKey = intent.getStringExtra(ConsumerActivity.EXTRA_CHANNEL_KEY)
                ?: "channel")
            .setDialogOptions(options)
            .checkAndShowUpdate { result ->
                status.text = when (result) {
                    is UpdateResult.UpdateAvailable -> "Update available: ${result.release.releaseVersion}"
                    UpdateResult.UpToDate -> "Up to date"
                    is UpdateResult.Error -> "Error: ${result.error.code}"
                }
            }
    }

    override fun onDestroy() {
        call?.cancel()
        super.onDestroy()
    }
}
