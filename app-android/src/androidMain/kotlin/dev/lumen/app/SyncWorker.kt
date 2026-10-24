package dev.lumen.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.lumen.core.collector.PermissionState
import java.util.concurrent.TimeUnit

/**
 * Background pass: import new usage from the OS log, then sync. Every
 * 15 minutes (Android's floor for periodic work), whether or not Lumen is
 * open — before this, nothing was recorded or synced unless the app was on
 * screen.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val runtime = LumenRuntime.get(applicationContext)
        if (runtime.collector.permissionState() is PermissionState.Granted) {
            runCatching { runtime.importer.importNew() }
        }
        // syncOnce never throws; failures are reported on the Sync screen.
        runtime.sync.syncOnce()
        return Result.success()
    }

    companion object {
        private const val NAME = "lumen-background"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
