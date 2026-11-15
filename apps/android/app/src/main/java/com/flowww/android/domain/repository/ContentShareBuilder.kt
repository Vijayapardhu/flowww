package com.flowww.android.domain.repository

import android.content.Intent
import com.flowww.android.domain.models.LocalContentHandle

/**
 * Builds the system Sharesheet intent for staged content.
 *
 * The interface exists so the ViewModel can be exercised without an Android
 * runtime: the production implementation builds a real
 * `ACTION_SEND`/`ACTION_SEND_MULTIPLE` intent.
 */
interface ContentShareBuilder {
    /** The intent to launch and the title to show on the system chooser. */
    fun build(handles: List<LocalContentHandle>): Result<Pair<Intent, String>>
}
