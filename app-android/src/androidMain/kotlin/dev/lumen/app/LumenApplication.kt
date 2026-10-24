package dev.lumen.app

import android.app.Application

class LumenApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SyncWorker.schedule(this)
    }
}
