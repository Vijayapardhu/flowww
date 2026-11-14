package com.flowww.android

import android.app.Application
import com.flowww.android.di.StagedStoreProvider

class FlowwwApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        StagedStoreProvider.init(this)
    }
}
