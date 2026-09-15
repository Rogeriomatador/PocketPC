package dev.pocketpc.core

import android.app.Application
import android.content.ComponentCallbacks2
import dev.pocketpc.core.performance.RuntimePerformanceController
import dev.pocketpc.core.update.PocketPcDataContinuity

class PocketPcApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RuntimePerformanceController.initialize(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            flushBrowserAuthenticationState()
        }
    }

    override fun onLowMemory() {
        flushBrowserAuthenticationState()
        super.onLowMemory()
    }

    private fun flushBrowserAuthenticationState() {
        // WebView owns cookies/WebStorage under the package data directory.
        // Flush persistent cookies to disk before Android may kill PocketPC for
        // an update or memory pressure. Never clear authentication state here.
        PocketPcDataContinuity.flushWebAuthenticationState()
    }
}
