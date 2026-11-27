package im.ews.zealot

import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.Signature
import android.content.Context
import android.content.Intent
import android.app.Activity
import android.graphics.Typeface
import android.os.Build
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestWorkerBuilder
import androidx.work.workDataOf
import im.ews.zealot.internal.apk.ApkDownloadWorker
import im.ews.zealot.internal.apk.ApkInstaller
import im.ews.zealot.internal.apk.ApkUpdateStatus
import im.ews.zealot.internal.apk.apkFile
import im.ews.zealot.internal.apk.validateApk
import im.ews.zealot.internal.UpdateNotice
import im.ews.zealot.internal.UpdatePromptSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class ApkUpdateTest {
    @get:Rule val files = TemporaryFolder()

    private val app = RuntimeEnvironment.getApplication()
    private val signer = Signature(byteArrayOf(1, 2, 3))

    @Test
    fun requiresSamePackageCompatibleSignerAndExactNewerBuild() {
        val file = files.newFile("update.apk").apply { writeBytes(byteArrayOf(1)) }
        val packages = Shadows.shadowOf(app.packageManager)
        packages.installPackage(packageInfo(app.packageName, 1, signer))
        val builds = listOf(
            packageInfo(app.packageName, 2, signer) to true,
            packageInfo("com.example.another", 2, signer) to false,
            packageInfo(app.packageName, 2, Signature(byteArrayOf(4))) to false,
            packageInfo(app.packageName, 1, signer) to false,
            packageInfo(app.packageName, 3, signer) to false
        )
        for ((archive, valid) in builds) {
            packages.setPackageArchiveInfo(file.path, archive)
            assertEquals(valid, validateApk(app, file, 2))
        }
        packages.setPackageArchiveInfo(file.path, packageInfo(app.packageName, 1, signer))
        assertFalse(validateApk(app, file, 1))
        file.delete()
        assertFalse(validateApk(app, file, 2))
    }

    @Test
    fun workerRejectsCleartextAndCredentialBearingUrlsBeforeDownloading() {
        for (url in listOf("http://example.org/app.apk", "https://token@example.org/app.apk")) {
            val worker = TestWorkerBuilder.from(app, ApkDownloadWorker::class.java, Runnable::run)
                .setInputData(workDataOf("url" to url, "build" to 2L))
                .build() as ApkDownloadWorker
            assertEquals(Result.failure(workDataOf("reason" to "browser")), worker.doWork())
        }
        assertTrue(app.noBackupFilesDir.resolve("zealot-apks").listFiles().isNullOrEmpty())
    }

    @Test
    fun installResultsKeepTheFileOnRejectionAndIgnoreOldSessions() {
        val id = UUID.randomUUID()
        val apk = apkFile(app, id).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        val preferences = app.getSharedPreferences("im.ews.zealot.apk_installer", Context.MODE_PRIVATE)
        preferences.edit().putInt("session", 42).commit()
        val result = Intent().putExtra("workId", id.toString())
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, 42)
            .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_BLOCKED)
        ApkInstaller.status.value = null
        ApkInstaller.accept(app, result)
        assertEquals("blocked", ApkInstaller.status.value?.reason)
        assertTrue(apk.isFile)

        preferences.edit().putInt("session", 43).commit()
        result.putExtra(PackageInstaller.EXTRA_SESSION_ID, 43)
            .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_ABORTED)
            .putExtra(PackageInstaller.EXTRA_DEVELOPER_VERIFICATION_FAILURE_REASON,
                PackageInstaller.DEVELOPER_VERIFICATION_FAILED_REASON_DEVELOPER_BLOCKED)
        ApkInstaller.accept(app, result)
        assertEquals("blocked", ApkInstaller.status.value?.reason)
        assertTrue(apk.isFile)

        preferences.edit().putInt("session", 44).commit()
        ApkInstaller.accept(app, result.putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS))
        assertTrue(apk.isFile)
        ApkInstaller.accept(app, result.putExtra(PackageInstaller.EXTRA_SESSION_ID, 44))
        assertFalse(apk.exists())
        assertNull(ApkInstaller.status.value)
    }

    @Test
    fun bottomSheetRetainsItsProgressViewAndDismissesWithTheLatestState() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val sheet = UpdatePromptSheet(activity, null)
        var dismissed: UpdateNotice? = null
        fun show(percent: Int) {
            val progress = ApkUpdateStatus(ApkUpdateStatus.Stage.DOWNLOADING, percent)
            sheet.render(UpdateNotice.Apk(progress, null), {}, {}, {}, {}, { _, _ -> }, {}) {
                dismissed = it
                sheet.hide()
            }
        }
        show(10)
        val dialog = requireNotNull(ShadowDialog.getLatestDialog())
        val content = requireNotNull(dialog.findViewById<View>(R.id.zealot_update_banner))
        show(45)
        assertSame(dialog, ShadowDialog.getLatestDialog())
        assertSame(content, dialog.findViewById<View>(R.id.zealot_update_banner))
        dialog.cancel()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(sheet.isShowing)
        assertEquals(45, (dismissed as? UpdateNotice.Apk)?.status?.percent)
        controller.pause().stop().destroy()
    }

    @Test
    @Config(sdk = [30])
    fun bottomSheetWrapsShortContentAndUsesAvailableHeightForLongNotes() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val sheet = UpdatePromptSheet(controller.get(), null)
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 24, 40, 32))
            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(48, 0, 0, 0))
            .build()
        fun layout(viewport: ViewGroup, height: Int) {
            val exact = View.MeasureSpec.EXACTLY
            viewport.measure(View.MeasureSpec.makeMeasureSpec(640, exact), View.MeasureSpec.makeMeasureSpec(height, exact))
            viewport.layout(0, 0, 640, height)
        }
        fun render(notice: UpdateNotice): ScrollView {
            sheet.render(notice, {}, {}, {}, {}, { _, _ -> }, {}, {})
            val dialog = requireNotNull(ShadowDialog.getLatestDialog())
            val body = requireNotNull(dialog.findViewById<View>(R.id.zealot_update_banner))
            val notes = body.parent as ScrollView
            val viewport = notes.parent.parent.parent as ViewGroup
            ViewCompat.dispatchApplyWindowInsets(viewport, insets)
            layout(viewport, 320)
            return notes
        }

        val ready = ApkUpdateStatus(ApkUpdateStatus.Stage.READY)
        val short = render(UpdateNotice.Apk(ready, null)).parent.parent as View
        assertTrue(short.top > 24)
        assertEquals(320, short.bottom)

        val release = ReleaseInfo("2.0", "2", "https://example.org/update.apk", "Release note\n".repeat(50))
        val notes = render(UpdateNotice.Available(release, download = true))
        val panel = notes.parent.parent as View
        assertEquals(0, panel.left)
        assertEquals(640, panel.right)
        assertEquals(24, panel.top)
        assertEquals(320, panel.bottom)
        assertEquals(48, panel.paddingLeft)
        assertEquals(40, panel.paddingRight)
        assertEquals(32, panel.paddingBottom)
        val height = notes.height
        assertTrue(notes.canScrollVertically(1))
        layout(panel.parent as ViewGroup, 480)
        assertEquals(24, panel.top)
        assertTrue(notes.height > height)
        sheet.hide()
        controller.pause().stop().destroy()
    }

    @Test
    @Config(sdk = [24, 30])
    fun bottomSheetAppliesTheCustomTypefaceToBothTextRoles() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val font = Typeface.SERIF
        val emphasized = if (Build.VERSION.SDK_INT >= 28) Typeface.create(font, 500, false)
        else Typeface.create(font, Typeface.BOLD)
        val sheet = UpdatePromptSheet(activity, null, typeface = font)
        val release = ReleaseInfo("2.0", "2", "https://example.org/update.apk", "Preview the new release")
        sheet.render(UpdateNotice.Available(release, download = true), {}, {}, {}, {}, { _, _ -> }, {}, {})
        val window = requireNotNull(ShadowDialog.getLatestDialog().window)
        fun fontOf(label: String): Typeface {
            val matches = arrayListOf<View>()
            window.decorView.findViewsWithText(matches, label, View.FIND_VIEWS_WITH_TEXT)
            return matches.filterIsInstance<TextView>().single { it.text.toString() == label }.typeface
        }
        assertEquals(font, fontOf(release.changelog))
        assertEquals(emphasized, fontOf(activity.getString(R.string.zealot_prompt_available)))
        assertEquals(emphasized, fontOf(activity.getString(R.string.zealot_prompt_notes)))
        assertEquals(emphasized, fontOf(activity.getString(R.string.zealot_apk_download)))
        sheet.hide()
        controller.pause().stop().destroy()
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(name: String, build: Int, signer: Signature) = PackageInfo().apply {
        packageName = name
        versionCode = build
        signatures = arrayOf(signer)
    }
}
