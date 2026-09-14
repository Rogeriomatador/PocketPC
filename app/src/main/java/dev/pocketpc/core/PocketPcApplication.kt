package dev.pocketpc.core

import android.app.Application
import dev.pocketpc.core.performance.RuntimePerformanceController

class PocketPcApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RuntimePerformanceController.initialize(this)
    }
}
