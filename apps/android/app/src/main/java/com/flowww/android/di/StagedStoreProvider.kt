package com.flowww.android.di

import android.content.Context
import com.flowww.android.storage.StagedStore
import java.io.File

/**
 * Dependency provider for shared instances.
 */
object StagedStoreProvider {
    @Volatile private var _store: StagedStore? = null

    fun init(context: Context) {
        if (_store == null) {
            synchronized(this) {
                if (_store == null) {
                    val dir = File(context.filesDir, "staged_store")
                    _store = StagedStore(dir)
                }
            }
        }
    }

    val store: StagedStore
        get() = _store ?: throw IllegalStateException("StagedStore not initialized. Call init(context) first.")
}
