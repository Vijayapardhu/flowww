package com.flowww.android.sensor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flowww.android.ui.theme.FlowwwTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GestureDiagnosticsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun viewModel() = GestureDiagnosticsViewModel(
        ApplicationProvider.getApplicationContext()
    )

    @Test
    fun diagnosticsScreenShowsHonestSensorStateAndNeverExecutesTransfers() {
        composeRule.setContent {
            FlowwwTheme {
                GestureDiagnosticsScreen(
                    viewModel = viewModel(),
                    onBack = {},
                    onOpenSession = {}
                )
            }
        }

        composeRule.onNodeWithText("Gesture diagnostics").assertIsDisplayed()
        composeRule.onNodeWithText("Live sensor readout. Diagnostics never execute transfers.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Mappings: +Y = SEND, -Y = RECEIVE, twist = CANCEL.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Calibrate (2 s capture)").assertIsDisplayed()
        composeRule.onNodeWithText("Open dedicated gesture session").assertIsDisplayed()
        // Diagnostics is not a transfer surface: no send controls may exist here.
        composeRule.onNodeWithText("Send").assertDoesNotExist()
        composeRule.onNodeWithText("Send selected").assertDoesNotExist()
    }

    @Test
    fun openSessionButtonNavigates() {
        var openedSession = false

        composeRule.setContent {
            FlowwwTheme {
                GestureDiagnosticsScreen(
                    viewModel = viewModel(),
                    onBack = {},
                    onOpenSession = { openedSession = true }
                )
            }
        }

        composeRule.onNodeWithText("Open dedicated gesture session").performClick()
        composeRule.waitUntil(timeoutMillis = 3000) { openedSession }
        assertTrue(openedSession)
    }

    @Test
    fun backButtonNavigates() {
        var back = false

        composeRule.setContent {
            FlowwwTheme {
                GestureDiagnosticsScreen(
                    viewModel = viewModel(),
                    onBack = { back = true },
                    onOpenSession = {}
                )
            }
        }

        // The Back button sits below the fold in the scrollable column.
        composeRule.onNodeWithText("Back").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 3000) { back }
        assertTrue(back)
    }
}
