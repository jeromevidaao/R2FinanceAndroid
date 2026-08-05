package com.cleaningbutton.r2finance

import android.app.Application
import com.cleaningbutton.r2finance.data.AppContainer

class R2FinanceApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
