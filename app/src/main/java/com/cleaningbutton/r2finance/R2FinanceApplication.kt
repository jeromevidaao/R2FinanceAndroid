package com.cleaningbutton.r2finance

import android.app.Application
import com.cleaningbutton.r2finance.data.AppContainer
import com.cleaningbutton.r2finance.push.PushRegistration

class R2FinanceApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Offline-first: flush PENDING_PUSH → DDB whenever network is/returns available.
        container.connectivityMonitor.start()
        // Precompute Reflect / Home / balances in memory on a background dispatcher.
        container.startBackgroundWarmup()
        PushRegistration.ensureChannel(this)
        PushRegistration.subscribeAndRegister(this)
    }
}
