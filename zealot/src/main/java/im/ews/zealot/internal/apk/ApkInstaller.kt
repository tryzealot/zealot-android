package im.ews.zealot.internal.apk

import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.MutableLiveData
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors

internal object ApkInstaller {
    data class Status(val workId: UUID, val reason: String? = null, val busy: Boolean = false)

    val status = MutableLiveData<Status?>()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private class Attempt(owner: Any) {
        var owner: Any? = owner
        var confirmation: Intent? = null
        var presented = false
    }
    private var attempt: Attempt? = null

    fun install(context: Context, workId: UUID, build: Long, owner: Any) {
        if (status.value?.busy == true) return
        val app = context.applicationContext
        val preferences = preferences(app)
        val previous = preferences.getInt("session", -1)
        if (previous >= 0) preferences.edit().remove("session").apply()
        val current = Attempt(owner)
        attempt = current
        status.value = Status(workId, busy = true)
        io.execute {
            val installer = app.packageManager.packageInstaller
            if (previous >= 0) runCatching { installer.abandonSession(previous) }
            var sessionId = -1
            try {
                val file = apkFile(app, workId)
                if (!validateApk(app, file, build)) {
                    file.delete()
                    main.post { finish(current, Status(workId, "invalid")) }
                    return@execute
                }
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(app.packageName)
                    setSize(file.length())
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
                sessionId = installer.createSession(params)
                val receiver = Intent(app, InstallResultReceiver::class.java).putExtra("workId", workId.toString())
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val result = PendingIntent.getBroadcast(app, sessionId, receiver, flags)
                installer.openSession(sessionId).use { session ->
                    session.openWrite("base.apk", 0, file.length()).use { output ->
                        file.inputStream().use { it.copyTo(output) }
                        session.fsync(output)
                    }
                    if (!preferences.edit().putInt("session", sessionId).commit()) {
                        throw IOException("Could not save the installer session")
                    }
                    session.commit(result.intentSender)
                }
            } catch (_: IOException) {
                fail(app, installer, sessionId, current, Status(workId, "storage"))
            } catch (_: IllegalStateException) {
                fail(app, installer, sessionId, current, Status(workId, "installer"))
            } catch (_: SecurityException) {
                fail(app, installer, sessionId, current, Status(workId, "installer"))
            } catch (_: IllegalArgumentException) {
                fail(app, installer, sessionId, current, Status(workId, "installer"))
            }
        }
    }

    fun detach(context: Context, owner: Any) {
        val current = attempt?.takeIf { it.owner === owner } ?: return
        current.owner = null
        if (current.presented) return
        if (current.confirmation != null) {
            abandonUnconfirmed(context, current)
        } else {
            // Let the current I/O finish staging before clearing its session.
            io.execute { main.post { abandonUnconfirmed(context, current) } }
        }
    }

    private fun abandonUnconfirmed(context: Context, current: Attempt) {
        if (attempt !== current || current.presented) return
        abandonPending(context, preferences(context).getInt("session", -1))
        finish(current, null)
    }

    fun launchConfirmation(activity: Activity, owner: Any) {
        val current = attempt?.takeIf { it.owner === owner } ?: return
        val next = current.confirmation ?: return
        current.confirmation = null
        current.presented = true
        try {
            activity.startActivity(next)
        } catch (_: ActivityNotFoundException) {
            confirmationFailed(activity)
        } catch (_: SecurityException) {
            confirmationFailed(activity)
        }
    }

    private fun confirmationFailed(context: Context) {
        val workId = status.value?.workId ?: return
        abandonPending(context, preferences(context).getInt("session", -1))
        finish(attempt, Status(workId, "installer"))
    }

    @Suppress("DEPRECATION")
    fun accept(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val preferences = preferences(context)
        if (sessionId < 0 || sessionId != preferences.getInt("session", -1)) return
        val workId = intent.getStringExtra("workId")?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return
        val result = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (result == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val current = attempt
            if (current?.owner == null) {
                abandonPending(context, sessionId)
                finish(current, null)
                return
            }
            // A subsequent user-action result, if any, requires handing off again.
            current.presented = false
            current.confirmation = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_INTENT)
            if (current.confirmation == null) {
                abandonPending(context, sessionId)
                finish(current, Status(workId, "installer"))
            } else {
                status.value = Status(workId, busy = true)
            }
            return
        }
        preferences.edit().remove("session").apply()
        attempt = null
        when (result) {
            PackageInstaller.STATUS_SUCCESS -> {
                apkFile(context, workId).delete()
                status.value = null
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                val verificationFailed = intent.hasExtra(PackageInstaller.EXTRA_DEVELOPER_VERIFICATION_FAILURE_REASON)
                status.value = if (verificationFailed) Status(workId, "blocked") else null
            }
            PackageInstaller.STATUS_FAILURE_BLOCKED -> status.value = Status(workId, "blocked")
            PackageInstaller.STATUS_FAILURE_STORAGE -> status.value = Status(workId, "storage")
            else -> status.value = Status(workId, "installer")
        }
    }

    private fun finish(current: Attempt?, result: Status?) {
        if (attempt !== current) return
        attempt = null
        status.value = result
    }

    private fun fail(context: Context, installer: PackageInstaller, sessionId: Int, current: Attempt, result: Status) {
        if (sessionId >= 0) abandon(context, installer, sessionId)
        main.post { finish(current, result) }
    }

    private fun abandonPending(context: Context, sessionId: Int) {
        if (sessionId < 0) return
        val app = context.applicationContext
        preferences(app).edit().remove("session").apply()
        io.execute { runCatching { app.packageManager.packageInstaller.abandonSession(sessionId) } }
    }

    private fun abandon(context: Context, installer: PackageInstaller, sessionId: Int) {
        preferences(context).edit().remove("session").apply()
        runCatching { installer.abandonSession(sessionId) }
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences("im.ews.zealot.apk_installer", Context.MODE_PRIVATE)
}

internal class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ApkInstaller.accept(context, intent)
    }
}
