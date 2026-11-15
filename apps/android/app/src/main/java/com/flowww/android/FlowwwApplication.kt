package com.flowww.android

import android.app.Application
import com.flowww.android.di.AppContainer
import com.flowww.android.di.DefaultAppContainer

class FlowwwApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // The container lazily creates the staged store, settings
        // data source, history store and repositories with the
        // application context. No global singletons are required.
        container = DefaultAppContainer(this)
    }
}
