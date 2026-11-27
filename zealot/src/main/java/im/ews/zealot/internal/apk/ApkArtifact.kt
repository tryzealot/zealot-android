package im.ews.zealot.internal.apk

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

internal val apkRetentionDays = TimeUnit.DAYS.toMillis(7)

internal fun apkDirectory(context: Context) = File(context.noBackupFilesDir, "zealot-apks")

internal fun apkFile(context: Context, id: UUID) = File(File(apkDirectory(context), id.toString()), "update.apk")

internal fun installedBuild(context: Context): Long = installedPackage(context, 0).buildNumber()

internal fun validateApk(context: Context, file: File, expectedBuild: Long): Boolean {
    if (!file.isFile || file.length() == 0L) return false
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
    else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
    val installed = installedPackage(context, flags)
    val candidate = @Suppress("DEPRECATION") context.packageManager.getPackageArchiveInfo(file.path, flags)
        ?: return false
    return candidate.packageName == installed.packageName &&
        candidate.buildNumber() == expectedBuild && expectedBuild > installed.buildNumber() &&
        compatibleSigners(installed, candidate)
}

private fun compatibleSigners(installed: PackageInfo, candidate: PackageInfo): Boolean {
    val current = installed.currentSigners()
    val next = candidate.currentSigners()
    if (current.isEmpty() || next.isEmpty()) return false
    if (current == next) return true
    if (Build.VERSION.SDK_INT < 28 || current.size != 1 || next.size != 1) return false
    // The platform verifies the rotation proof and its capabilities at installation.
    return candidate.signingInfo?.signingCertificateHistory?.contains(current.single()) == true
}

@Suppress("DEPRECATION")
private fun PackageInfo.currentSigners(): Set<Signature> =
    (if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures).orEmpty().toSet()

@Suppress("DEPRECATION")
private fun PackageInfo.buildNumber(): Long =
    if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

@Suppress("DEPRECATION")
private fun installedPackage(context: Context, flags: Int): PackageInfo =
    context.packageManager.getPackageInfo(context.packageName, flags)
