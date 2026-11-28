package im.ews.zealot.internal

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri

internal fun openUpdateLink(activity: Activity, installUrl: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(installUrl))
    return try {
        activity.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
