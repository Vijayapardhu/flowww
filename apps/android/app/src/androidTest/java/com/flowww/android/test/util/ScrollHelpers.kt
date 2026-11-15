package com.flowww.android.test.util

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp

/**
 * Scrolls the tagged LazyColumn until a node rendering [text] is composed.
 *
 * Lazy lists compose items only once they approach the viewport, so a deep
 * item has no node in the semantics tree to assert on until the list is
 * scrolled towards it. This swipes the list (a no-op once the end is reached)
 * until the text exists, after which callers can use `performScrollTo()` for
 * a pixel-perfect reveal.
 */
fun ComposeContentTestRule.scrollListUntilExists(listTag: String, text: String) {
    repeat(15) {
        if (onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) return
        onNodeWithTag(listTag).performTouchInput { swipeUp() }
    }
}
