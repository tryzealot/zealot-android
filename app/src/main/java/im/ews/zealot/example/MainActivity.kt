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
import im.ews.zealot.StartupUpdateCheck
import im.ews.zealot.UpdateResult
import im.ews.zealot.Zealot

class MainActivity : AppCompatActivity() {
    private var startupCheck: StartupUpdateCheck? = null

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

            startupCheck?.onPause()
            status.setText(R.string.check_status_loading)
            startupCheck = zealot.createStartupCheck { result ->
                when (result) {
                    is UpdateResult.UpdateAvailable -> {
                        status.text = getString(
                            R.string.check_status_available,
                            result.release.releaseVersion,
                            result.release.buildVersion
                        )
                    }
                    UpdateResult.UpToDate ->
                        status.text = getString(R.string.check_status_current, packageName)
                    is UpdateResult.Error ->
                        status.text = getString(R.string.check_status_error, result.error.message)
                }
            }
            startupCheck?.onResume()
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        startupCheck?.onResume()
    }

    override fun onPause() {
        startupCheck?.onPause()
        super.onPause()
    }
}
