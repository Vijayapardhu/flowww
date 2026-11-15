package com.flowww.android.ui.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flowww.android.ui.components.FlowwwBottomBar
import com.flowww.android.ui.theme.FlowwwTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BottomNavTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun bottomBarShowsAllFiveTabs() {
        composeRule.setContent {
            FlowwwTheme {
                FlowwwBottomBar(currentRoute = "staged", onNavigate = {})
            }
        }

        composeRule.onNodeWithText("Nearby").assertIsDisplayed()
        composeRule.onNodeWithText("Trusted").assertIsDisplayed()
        composeRule.onNodeWithText("Staged").assertIsDisplayed()
        composeRule.onNodeWithText("Transfers").assertIsDisplayed()
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun tappingATabReportsItsRoute() {
        val routes = mutableListOf<String>()
        composeRule.setContent {
            FlowwwTheme {
                FlowwwBottomBar(currentRoute = "nearby", onNavigate = { route -> routes += route })
            }
        }

        composeRule.onNodeWithText("Settings").performClick()
        composeRule.waitUntil(timeoutMillis = 3000) { routes == listOf("settings") }

        composeRule.onNodeWithText("Transfers").performClick()
        composeRule.waitUntil(timeoutMillis = 3000) { routes == listOf("settings", "transfers") }
    }
}
