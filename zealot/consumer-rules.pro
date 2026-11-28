# WorkManager persists the worker class name across app updates and process restarts.
-keep class im.ews.zealot.internal.apk.ApkDownloadWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
