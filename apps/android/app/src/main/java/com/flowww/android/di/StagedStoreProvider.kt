package com.flowww.android.di

import android.content.Context
import com.flowww.android.storage.StagedStore

/**
 * Dependency injection provider for shared instances.
 * In production, this would use Hilt or Koin.
 */
object StagedStoreProvider {
    @Volatile private var _store: StagedStore? = null

    fun init(context: Context) {
        if (_store == null) {
            synchronized(this) {
                if (_store == null) {
                    _store = StagedStore(context.cacheDir)
                }
            }
        }
    }

    val store: StagedStore
        get() {
            val s = _store
                ?: throw IllegalStateException("StagedStore not initialized. Call init(context) first.")
            return s
        }
}
