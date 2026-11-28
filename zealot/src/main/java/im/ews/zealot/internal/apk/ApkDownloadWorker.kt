package im.ews.zealot.internal.apk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.workDataOf
import im.ews.zealot.R
import im.ews.zealot.internal.withUpdateLocale
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

// WorkManager recreates this worker by class name after process death.
internal class ApkDownloadWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    companion object {
        private val client = OkHttpClient.Builder()
            .followSslRedirects(false)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.MINUTES)
            .build()
        private const val maxApkBytes = 512L * 1024 * 1024
        private const val notificationChannel = "zealot.apk.download"
    }

    @Volatile private var activeCall: Call? = null
    private val notificationId = id.hashCode().let { if (it == 0) 0x5EA107 else it }

    override fun doWork(): Result {
        val build = inputData.getLong("build", 0)
        val url = inputData.getString("url")?.toHttpUrlOrNull() ?: return failure("browser")
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) return failure("browser")
        if (build <= installedBuild(applicationContext)) return failure("version")

        val target = apkFile(applicationContext, id)
        val directory = checkNotNull(target.parentFile)
        val partial = File(directory, "partial.apk")
        val expiration = System.currentTimeMillis() - apkRetentionDays
        apkDirectory(applicationContext).listFiles()?.filter {
            it != directory && it.lastModified() < expiration
        }?.forEach { it.deleteRecursively() }
        if (!directory.isDirectory && !directory.mkdirs()) return failure("storage")
        // A stopped worker may be restarted after publishing a file but before recording success.
        if (validateApk(applicationContext, target, build)) return ready(build)
        target.delete()
        val request = Request.Builder().url(url).build()
        val call = client.newCall(request)
        activeCall = call
        return try {
            val notification = createNotification()
            val percentFormat = NumberFormat.getPercentInstance(
                resourcesLocale(notificationStrings)
            )
            setForegroundAsync(foregroundInfo(notification)).get()
            if (isStopped) return Result.failure()
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    return when (response.code) {
                        401, 403, 404, 410 -> failure("browser")
                        408, 429, in 500..599 -> retryNetwork()
                        else -> failure("network")
                    }
                }
                val body = response.body
                if (body.contentType()?.subtype in setOf("html", "json", "xml")) return failure("browser")
                val total = body.contentLength()
                if (total == 0L || total > maxApkBytes) return failure("invalid")
                if (total > directory.usableSpace) return failure("storage")
                var copied = 0L
                var publishedAt = 0L
                var notifiedAt = 0L
                FileOutputStream(partial).use { output ->
                    val input = body.byteStream()
                    val buffer = ByteArray(64 * 1024)
                    while (!isStopped) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        copied += read
                        if (copied > maxApkBytes) return failure("invalid")
                        output.write(buffer, 0, read)
                        val now = SystemClock.elapsedRealtime()
                        if (now - publishedAt >= 250L) {
                            val percent = if (total > 0) (copied * 100 / total).toInt().coerceIn(0, 100) else -1
                            setProgressAsync(workDataOf("percent" to percent))
                            publishedAt = now
                            if (percent >= 0 && now - notifiedAt >= 1_000L) {
                                setForegroundAsync(foregroundInfo(notification
                                    .setContentText(percentFormat.format(percent / 100.0))
                                    .setProgress(100, percent, false)))
                                notifiedAt = now
                            }
                        }
                    }
                    output.fd.sync()
                }
                if (isStopped) return Result.failure()
                if (total > 0 && copied != total) return retryNetwork()
                setProgressAsync(workDataOf("verifying" to true))
                setForegroundAsync(foregroundInfo(notification
                    .setContentTitle(notificationStrings.getString(R.string.zealot_apk_verifying))
                    .setContentText(null)
                    .setProgress(0, 0, true)))
                if (!validateApk(applicationContext, partial, build)) return failure("invalid")
                if (isStopped) return Result.failure()
                if (!partial.renameTo(target)) return failure("storage")
                ready(build)
            }
        } catch (_: IOException) {
            if (directory.usableSpace < 1024 * 1024) failure("storage") else retryNetwork()
        } catch (_: ExecutionException) {
            failure("background")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            Result.failure()
        } finally {
            activeCall = null
            partial.delete()
        }
    }

    override fun onStopped() {
        activeCall?.cancel()
    }

    private fun ready(build: Long) = Result.success(workDataOf("build" to build))

    private fun retryNetwork(): Result =
        if (!isStopped && runAttemptCount < 2) Result.retry() else failure("network")

    private fun failure(reason: String) = Result.failure(workDataOf("reason" to reason))

    private val notificationStrings by lazy {
        applicationContext.withUpdateLocale(inputData.getString("locale")?.let(Locale::forLanguageTag))
    }

    @Suppress("DEPRECATION")
    private fun resourcesLocale(context: Context): Locale {
        val config = context.resources.configuration
        return if (Build.VERSION.SDK_INT >= 24) config.locales[0] else config.locale
    }

    @Suppress("DEPRECATION")
    private fun createNotification(): Notification.Builder {
        val context = notificationStrings
        val label = context.getString(R.string.zealot_apk_downloading)
        val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(
                NotificationChannel(notificationChannel, label, NotificationManager.IMPORTANCE_LOW)
            )
            Notification.Builder(context, notificationChannel)
        } else {
            Notification.Builder(context)
        }
        applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)?.let { launch ->
            builder.setContentIntent(PendingIntent.getActivity(
                applicationContext, 0x5EA107, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            ))
        }
        return builder.setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(label)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.zealot_apk_cancel),
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
            )
    }

    private fun foregroundInfo(builder: Notification.Builder): ForegroundInfo {
        val notification = builder.build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(notificationId, notification)
    }
}
