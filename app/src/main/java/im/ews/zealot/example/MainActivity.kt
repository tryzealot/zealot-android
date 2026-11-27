package im.ews.zealot.example

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import im.ews.zealot.UpdatePrompts
import im.ews.zealot.Zealot
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private data class PromptSettings(val endpoint: String, val key: String, val locale: Locale?, val sheetTheme: Int)

    private var prompts: UpdatePrompts? = null
    private var activeSettings: PromptSettings? = null
    private val languages by lazy {
        listOf(
            getString(R.string.use_app_language) to null,
            "English" to Locale.ENGLISH,
            "简体中文" to Locale.SIMPLIFIED_CHINESE,
            "Deutsch" to Locale.GERMAN,
            "Français" to Locale.FRENCH,
            "Русский" to Locale.forLanguageTag("ru")
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val light = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = light

        ViewCompat.setOnApplyWindowInsetsListener(findViewById<ScrollView>(R.id.sample_root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        findViewById<Spinner>(R.id.prompt_locale).adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            languages.map { it.first }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        findViewById<Button>(R.id.enable_prompts).setOnClickListener { configurePrompts() }
        findViewById<Button>(R.id.check_update).setOnClickListener { configurePrompts()?.checkNow() }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if (savedInstanceState?.getBoolean("zealotPromptsEnabled") == true) configurePrompts()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("zealotPromptsEnabled", prompts != null)
        super.onSaveInstanceState(outState)
    }

    private fun configurePrompts(): UpdatePrompts? {
        val endpoint = findViewById<EditText>(R.id.endpoint)
        val channelKey = findViewById<EditText>(R.id.channel_key)
        val status = findViewById<TextView>(R.id.check_status)
        val serverUrl = endpoint.text.toString().trim()
        val key = channelKey.text.toString().trim()
        if (serverUrl.isEmpty() || key.isEmpty()) {
            status.setText(R.string.check_status_missing_input)
            return null
        }

        val keyboard = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        keyboard.hideSoftInputFromWindow(endpoint.windowToken, 0)
        findViewById<ScrollView>(R.id.sample_root).requestFocus()
        val locale = languages.getOrNull(findViewById<Spinner>(R.id.prompt_locale).selectedItemPosition)?.second
        val sheetTheme = if (findViewById<CheckBox>(R.id.custom_sheet_colors).isChecked) R.style.SampleUpdateSheet else 0
        val settings = PromptSettings(serverUrl, key, locale, sheetTheme)
        if (activeSettings == settings) return prompts

        val client = try {
            Zealot.create(this)
                .setEndpoint(serverUrl)
                .setChannelKey(key)
                .setBuildType(BuildConfig.BUILD_TYPE)
                .setLocale(locale)
                .setUpdateSheetTheme(sheetTheme)
        } catch (error: IllegalArgumentException) {
            status.text = error.message
            return null
        }

        prompts?.close()
        activeSettings = settings
        status.setText(R.string.check_status_enabled)
        return client.enableUpdatePrompts(downloadUpdates = BuildConfig.DEBUG).also { prompts = it }
    }
}
