package im.ews.zealot.example

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import im.ews.zealot.ReleaseInfo
import im.ews.zealot.UpdateCallback
import im.ews.zealot.UpdateError
import im.ews.zealot.Zealot
import okhttp3.Call

class MainActivity : AppCompatActivity() {
    private var updateCall: Call? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById<ScrollView>(R.id.sample_root)) {
                view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val endpoint = findViewById<EditText>(R.id.endpoint)
        val channelKey = findViewById<EditText>(R.id.channel_key)
        val checkButton = findViewById<Button>(R.id.check_update)
        val status = findViewById<TextView>(R.id.check_status)

        checkButton.setOnClickListener {
            val serverUrl = endpoint.text.toString().trim()
            val key = channelKey.text.toString().trim()
            if (serverUrl.isEmpty() || key.isEmpty()) {
                status.setText(R.string.check_status_missing_input)
                return@setOnClickListener
            }

            val zealot = try {
                Zealot.create(this)
                    .setEndpoint(serverUrl)
                    .setChannelKey(key)
                    .setBuildType(BuildConfig.BUILD_TYPE)
                    .setAlertMaxHeight(Zealot.ScreenHeight.HALFSCREEN)
            } catch (error: IllegalArgumentException) {
                status.text = getString(R.string.check_status_error, error.message)
                return@setOnClickListener
            }

            checkButton.isEnabled = false
            status.setText(R.string.check_status_loading)
            updateCall = zealot.checkAndShowUpdate(object : UpdateCallback {
                override fun onUpdateAvailable(release: ReleaseInfo) {
                    status.text = getString(
                        R.string.check_status_available,
                        release.releaseVersion,
                        release.buildVersion
                    )
                    checkButton.isEnabled = true
                    updateCall = null
                }

                override fun onUpToDate() {
                    status.text = getString(R.string.check_status_current, packageName)
                    checkButton.isEnabled = true
                    updateCall = null
                }

                override fun onError(error: UpdateError) {
                    status.text = getString(R.string.check_status_error, error.message)
                    checkButton.isEnabled = true
                    updateCall = null
                }
            })
        }
    }

    override fun onDestroy() {
        updateCall?.cancel()
        updateCall = null
        super.onDestroy()
    }
}
