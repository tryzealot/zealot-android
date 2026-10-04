package im.ews.zealot

import android.app.Activity

/**
 * Presents an available release from [Zealot.launch]. Called on the main thread while the
 * originating Activity is still alive. Use this to provide a custom dialog or other UI.
 */
fun interface UpdatePresenter {
    fun present(activity: Activity, release: ReleaseInfo)
}
