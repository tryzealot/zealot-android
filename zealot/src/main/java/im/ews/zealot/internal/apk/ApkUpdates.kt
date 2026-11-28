package im.ews.zealot.internal.apk

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.lifecycle.Observer
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import im.ews.zealot.ReleaseInfo
import im.ews.zealot.UpdateResult
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

internal data class ApkUpdateStatus(
    val stage: Stage,
    val percent: Int = -1,
    val reason: String? = null
) {
    enum class Stage { QUEUED, DOWNLOADING, VERIFYING, READY, INSTALLING, FAILED }
}

internal class ApkUpdates(
    context: Context,
    endpoint: HttpUrl,
    channelKey: String,
    private val locale: Locale?,
    private val changed: () -> Unit
) {
    companion object {
        @Suppress("DEPRECATION")
        fun supported(context: Context): Boolean {
            val packages = context.packageManager
            val packageName = context.packageName
            val installer = if (Build.VERSION.SDK_INT >= 30) {
                packages.getInstallSourceInfo(packageName).installingPackageName
            } else packages.getInstallerPackageName(packageName)
            return installer != "com.android.vending" && (Build.VERSION.SDK_INT < 26 ||
                packages.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
                    .requestedPermissions?.contains(Manifest.permission.REQUEST_INSTALL_PACKAGES) == true)
        }
    }

    private val app = context.applicationContext
    private val scope = MessageDigest.getInstance("SHA-256")
        .digest("$endpoint\u0000$channelKey".toByteArray())
        .joinToString("") { "%02x".format(it) }
    private val uniqueWork = "im.ews.zealot.apk_download.$scope"
    private val work = WorkManager.getInstance(app)
    private val history = work.getWorkInfosForUniqueWorkLiveData(uniqueWork)
    private val main = Executor { Handler(Looper.getMainLooper()).post(it) }
    private data class EnqueuedDownload(val id: UUID, val build: Long)
    private data class CheckedDownload(val id: UUID?, val serverBuild: Long?)
    private var latest: WorkInfo? = null
    private var enqueued: EnqueuedDownload? = null
    val workId: UUID? get() = enqueued?.id ?: latest?.id
    val workBuild: Long? get() = enqueued?.build ?: latest?.let(::targetBuild)
    private var hiddenFailure: UUID? = null
    private var checkedDownload: CheckedDownload? = null
    private var historyLoaded = false
    private var settingsUnavailable = false
    private var enqueueFailed = false
    private val workObserver = Observer<List<WorkInfo>> { infos ->
        val info = infos.orEmpty().firstOrNull { !it.state.isFinished } ?: infos.orEmpty().firstOrNull()
        latest = info
        if (info?.id == enqueued?.id || info?.state?.isFinished == false) enqueued = null
        if (!historyLoaded) {
            historyLoaded = true
            // The first successful check can arrive before the persisted work is loaded.
            val check = checkedDownload
            if (check != null && check.id == null) checkedDownload = check.copy(id = workId)
        }
        changed()
    }
    private val installObserver = Observer<ApkInstaller.Status?> { changed() }

    fun observe() {
        history.observeForever(workObserver)
        ApkInstaller.status.observeForever(installObserver)
    }

    fun close() {
        history.removeObserver(workObserver)
        ApkInstaller.status.removeObserver(installObserver)
        ApkInstaller.detach(app, this)
    }

    fun canDownload(release: ReleaseInfo): Boolean {
        val url = release.installUrl.toHttpUrlOrNull() ?: return false
        return release.installUrl.length <= 8_000 && url.isHttps && url.username.isEmpty() && url.password.isEmpty() &&
            (release.buildVersion.toLongOrNull() ?: 0L) > installedBuild(app)
    }

    fun download(release: ReleaseInfo) {
        if (enqueued != null || latest?.state?.isFinished == false) return
        if (!canDownload(release)) return
        val build = release.buildVersion.toLong()
        val request = OneTimeWorkRequestBuilder<ApkDownloadWorker>()
            .setInputData(workDataOf("url" to release.installUrl, "build" to build,
                "locale" to locale?.toLanguageTag()))
            .addTag(scope)
            .addTag("build:$build")
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .keepResultsForAtLeast(7, TimeUnit.DAYS)
            .build()
        enqueued = EnqueuedDownload(request.id, build)
        enqueueFailed = false
        hiddenFailure = null
        val operation = work.enqueueUniqueWork(uniqueWork, ExistingWorkPolicy.KEEP, request).result
        operation.addListener({
            if (enqueued?.id != request.id) return@addListener
            if (runCatching { operation.get() }.isFailure) {
                enqueued = null
                enqueueFailed = true
                changed()
            } else {
                // KEEP can succeed without creating our request when another Activity enqueues first.
                val persisted = work.getWorkInfoById(request.id)
                persisted.addListener({
                    if (enqueued?.id == request.id && runCatching { persisted.get() }.getOrNull() == null) {
                        enqueued = null
                        changed()
                    }
                }, main)
            }
        }, main)
        changed()
    }

    fun cancel() {
        val id = enqueued?.id ?: latest?.takeIf { scope in it.tags && !it.state.isFinished }?.id ?: return
        work.cancelWorkById(id)
    }

    fun hideFailure() {
        enqueueFailed = false
        hiddenFailure = latest?.id
    }

    fun reconcile(result: UpdateResult) {
        val build = when (result) {
            is UpdateResult.UpdateAvailable -> result.release.buildVersion.toLongOrNull()
            UpdateResult.UpToDate -> null
            is UpdateResult.Error -> return
        }
        checkedDownload = CheckedDownload(workId, build)
        enqueueFailed = false
    }

    fun status(): ApkUpdateStatus? {
        if (enqueued != null) return ApkUpdateStatus(ApkUpdateStatus.Stage.QUEUED)
        if (enqueueFailed) return ApkUpdateStatus(ApkUpdateStatus.Stage.FAILED, reason = "background")
        val latest = latest ?: return null
        if (scope !in latest.tags) return null
        val installer = ApkInstaller.status.value
        val installation = installer?.takeIf { it.workId == latest.id }
        if (installation?.busy == true) return ApkUpdateStatus(ApkUpdateStatus.Stage.INSTALLING)
        // WorkManager can report a completed download after the server check has finished.
        val checked = checkedDownload
        if (latest.state.isFinished && checked?.id == latest.id &&
            (checked.serverBuild == null || targetBuild(latest) != checked.serverBuild)
        ) {
            apkFile(app, latest.id).delete()
            return null
        }
        return when (latest.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ApkUpdateStatus(ApkUpdateStatus.Stage.QUEUED)
            WorkInfo.State.RUNNING -> if (latest.progress.getBoolean("verifying", false)) {
                ApkUpdateStatus(ApkUpdateStatus.Stage.VERIFYING)
            } else ApkUpdateStatus(ApkUpdateStatus.Stage.DOWNLOADING, latest.progress.getInt("percent", -1))
            WorkInfo.State.SUCCEEDED -> {
                val build = latest.outputData.getLong("build", 0)
                if (build <= installedBuild(app)) {
                    apkFile(app, latest.id).delete()
                    return null
                }
                val file = apkFile(app, latest.id)
                if (!file.isFile) return installation?.reason?.let {
                    ApkUpdateStatus(ApkUpdateStatus.Stage.FAILED, reason = it)
                }?.takeIf { latest.id != hiddenFailure }
                if (System.currentTimeMillis() - file.lastModified() >= apkRetentionDays) {
                    file.delete()
                    return null
                }
                if (installer?.busy == true) return ApkUpdateStatus(ApkUpdateStatus.Stage.INSTALLING)
                val reason = when {
                    settingsUnavailable -> "settings"
                    needsPermission() -> "permission"
                    else -> installation?.reason
                }
                ApkUpdateStatus(ApkUpdateStatus.Stage.READY, reason = reason)
            }
            WorkInfo.State.FAILED -> if (latest.id == hiddenFailure) null else
                ApkUpdateStatus(ApkUpdateStatus.Stage.FAILED, reason = latest.outputData.getString("reason"))
            WorkInfo.State.CANCELLED -> null
        }
    }

    fun install(activity: Activity) {
        if (status()?.stage != ApkUpdateStatus.Stage.READY) return
        val latest = latest?.takeIf { it.state == WorkInfo.State.SUCCEEDED && scope in it.tags } ?: return
        if (needsPermission()) {
            try {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")))
                settingsUnavailable = false
            } catch (_: ActivityNotFoundException) {
                settingsUnavailable = true
            } catch (_: SecurityException) {
                settingsUnavailable = true
            }
            changed()
        } else {
            settingsUnavailable = false
            ApkInstaller.install(app, latest.id, latest.outputData.getLong("build", 0), this)
        }
    }

    fun launchConfirmation(activity: Activity) {
        val current = latest ?: return
        if (scope in current.tags && current.id == ApkInstaller.status.value?.workId) {
            ApkInstaller.launchConfirmation(activity, this)
        }
    }

    private fun needsPermission() = Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()

    private fun targetBuild(info: WorkInfo): Long? = info.outputData.getLong("build", 0L).takeIf { it > 0L }
        ?: info.tags.firstOrNull { it.startsWith("build:") }?.removePrefix("build:")?.toLongOrNull()
}
