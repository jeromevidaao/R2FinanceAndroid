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
        PushRegistration.ensureChannel(this)
        PushRegistration.subscribeAndRegister(this)
    }
}
