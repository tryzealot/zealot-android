package im.ews.zealot.internal

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.WindowManager
import im.ews.zealot.R
import im.ews.zealot.UpdateDialogOptions
import im.ews.zealot.Zealot
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Optional Android UI for applications that use the original [Zealot.launch] flow. */
internal object DefaultUpdateDialog {
    fun show(
        activity: Activity,
        version: String,
        changelog: String,
        installUrl: String,
        maxHeight: Zealot.ScreenHeight,
        options: UpdateDialogOptions
    ): AlertDialog? {
        if (activity.isFinishing || activity.isDestroyed) return null
        if (installUrl.toHttpUrlOrNull() == null) return null
        val decorView = activity.window.decorView
        if ((decorView.getTag(R.id.zealot_visible_dialog) as? AlertDialog)?.isShowing == true) {
            return null
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(options.title ?: activity.getString(R.string.zealot_update_title, version))
            .setMessage(changelog)
            .setNegativeButton(options.laterButtonText ?: activity.getString(R.string.zealot_update_later), null)
            .setPositiveButton(options.updateButtonText ?: activity.getString(R.string.zealot_update_now)) { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(installUrl))
                try {
                    activity.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    // No browser or installer can handle the release link.
                } catch (_: SecurityException) {
                    // The system or device policy can deny opening the release link.
                }
            }
            .setCancelable(options.cancelable)
            .create()
        val detachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                dialog.dismiss()
            }
        }
        dialog.setOnDismissListener {
            decorView.removeOnAttachStateChangeListener(detachListener)
            if (decorView.getTag(R.id.zealot_visible_dialog) === dialog) {
                decorView.setTag(R.id.zealot_visible_dialog, null)
            }
        }

        try {
            dialog.show()
            decorView.addOnAttachStateChangeListener(detachListener)
            decorView.setTag(R.id.zealot_visible_dialog, dialog)
        } catch (_: WindowManager.BadTokenException) {
            decorView.removeOnAttachStateChangeListener(detachListener)
            // The Activity can lose its window while the asynchronous request is running.
            return null
        }

        if (maxHeight == Zealot.ScreenHeight.HALFSCREEN) {
            val maxDialogHeight = (activity.resources.displayMetrics.heightPixels / 2f).toInt()
            val window = dialog.window
            window?.decorView?.post {
                if (dialog.isShowing && window.decorView.height > maxDialogHeight) {
                    val params = WindowManager.LayoutParams().apply {
                        copyFrom(window.attributes)
                        height = maxDialogHeight
                    }
                    window.attributes = params
                }
            }
        }
        return dialog
    }
}
