package im.ews.zealot

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class Zealot private constructor(val context: Context) {
    companion object {
        const val BUILD_TYPE = "default"

        @JvmStatic fun create(context: Context): Zealot {
            return Zealot(context)
        }
    }

    enum class ScreenHeight {
        AUTOMATIC, HALFSCREEN
    }

    private var endpoint: String? = null

    private var channelKeys = hashMapOf<String, String>()
    private var buildType: String = BUILD_TYPE
    private var maxHeight = ScreenHeight.AUTOMATIC

    private val client = OkHttpClient()
    private val packageName: String
        get() = context.packageName

    private val packageInfo: PackageInfo
        get() = context.packageManager.getPackageInfo(packageName, 0)

    private val releaseVersion: String
        get() = packageInfo.versionName

    private val buildVersion: Long
        get() {
            val versionCode: Long
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                versionCode = packageInfo.longVersionCode
            } else {
                versionCode = packageInfo.versionCode.toLong()
            }
            return versionCode
        }

    fun setEndpoint(endpoint: String): Zealot {
        this.endpoint = endpoint
        return this
    }

    fun setChannelKey(channelKey: String): Zealot {
        return setChannelKey(channelKey, BUILD_TYPE)
    }

    fun setChannelKey(channelKey: String, buildType: String): Zealot {
        this.channelKeys[buildType] = channelKey
        return this
    }

    fun setBuildType(buildType: String): Zealot {
        this.buildType = buildType
        return this
    }

    fun setAlertMaxHeight(height: ScreenHeight): Zealot {
        this.maxHeight = height
        return this
    }

    fun launch() {
        val urlBuilder = HttpUrl.parse("${endpoint}/api/apps/latest")!!.newBuilder()
        urlBuilder.addQueryParameter("channel_key", channelKey())
        urlBuilder.addQueryParameter("release_version", releaseVersion)
        urlBuilder.addQueryParameter("build_version", buildVersion.toString())
        urlBuilder.addQueryParameter("bundle_id", packageName)

        val url = urlBuilder.build()
        val request = Request.Builder()
            .url(url)
            .build()

        client.newCall(request).enqueue(Callback(this))
    }

    fun showAlert(version: String, changelog: String, installUrl: String)  {
        val dialog  = AlertDialog.Builder(context)
            .setTitle("发现新版本 ${version}️")
            .setMessage(changelog)
            .setNegativeButton("下次再说", null)
            .setPositiveButton("立即更新") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(installUrl))
                context.startActivity(intent)
            }
            .create()

        dialog.show()

        if (maxHeight == ScreenHeight.HALFSCREEN) {
            dialog.setMaxHeight((context.resources.displayMetrics.heightPixels / 2.0f).toInt())
        }
    }

    private fun channelKey(): String {
        if (channelKeys.size == 0 ) {
            throw IllegalArgumentException("channelKey is empty")
        }

        var channelKey: String? = null
        if (channelKeys.containsKey(buildType)) {
            channelKey = channelKeys[buildType]
        } else if (channelKeys.containsKey(BUILD_TYPE)) {
            channelKey = channelKeys[BUILD_TYPE]
        }

        if (channelKey == null) {
            throw IllegalArgumentException("channelKey is empty")
        }

        return channelKey
    }

    private fun AlertDialog.setMaxHeight(height: Int) {
        val dialogHeight = window?.attributes?.height
        if (dialogHeight != null && dialogHeight <= height) { return }

        val layoutParams = WindowManager.LayoutParams()
        layoutParams.copyFrom(window?.attributes)
        layoutParams.height = height
        window?.attributes = layoutParams
    }
}


