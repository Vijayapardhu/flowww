package com.flowww.android.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flowww.android.domain.models.FlowwwSettings
import com.flowww.android.test.util.FakeSettingsRepo
import com.flowww.android.test.util.scrollListUntilExists
import com.flowww.android.ui.theme.FlowwwTheme
import com.flowww.android.ui.viewmodel.SettingsViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settingsRenderWithPolicyInvariants() {
        val repo = FakeSettingsRepo(FlowwwSettings())
        val viewModel = SettingsViewModel(repo)
        var openedAbout = false

        composeRule.setContent {
            FlowwwTheme {
                SettingsScreen(
                    viewModel = viewModel,
                    onOpenGestureDiagnostics = {},
                    onOpenAbout = { openedAbout = true },
                    onOpenHistory = {}
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 3000) { !viewModel.uiState.value.isLoading }

        // Assert in list order so the LazyColumn only ever scrolls forward:
        // items above the viewport are disposed once scrolled past.
        composeRule.onNodeWithText("Device display name").assertIsDisplayed()
        composeRule.onNodeWithText("Default export folder: Not set").assertIsDisplayed()
        composeRule.scrollListUntilExists("settings_list", "Motion gestures")
        composeRule.onNodeWithText("Motion gestures").performScrollTo().assertIsDisplayed()
        composeRule.scrollListUntilExists("settings_list", "Transfer notifications")
        composeRule.onNodeWithText("Transfer notifications").performScrollTo().assertIsDisplayed()
        composeRule.scrollListUntilExists("settings_list", "Require approval for incoming transfers")
        composeRule.onNodeWithText("Require approval for incoming transfers").performScrollTo().assertIsDisplayed()
        // Security policy is stated in the UI itself.
        composeRule.scrollListUntilExists("settings_list", "Security policy: approval cannot be disabled")
        composeRule.onNodeWithText("Security policy: approval cannot be disabled").performScrollTo().assertIsDisplayed()
        composeRule.scrollListUntilExists("settings_list", "Auto-connect to last device")
        composeRule.onNodeWithText("Auto-connect to last device").performScrollTo().assertIsDisplayed()

        composeRule.scrollListUntilExists("settings_list", "Open about")
        composeRule.onNodeWithText("Open about").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 3000) { openedAbout }
    }

    @Test
    fun motionGestureTogglePersistsThroughRepository() {
        val repo = FakeSettingsRepo(FlowwwSettings())
        val viewModel = SettingsViewModel(repo)

        composeRule.setContent {
            FlowwwTheme {
                SettingsScreen(viewModel = viewModel, onOpenGestureDiagnostics = {}, onOpenAbout = {}, onOpenHistory = {})
            }
        }

        composeRule.waitUntil(timeoutMillis = 3000) { !viewModel.uiState.value.isLoading }

        composeRule.scrollListUntilExists("settings_list", "Motion gestures")
        composeRule.onNodeWithContentDescription("Motion gestures")
            .performScrollTo()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 5000) {
            repo.current.value?.motionGesturesEnabled == true
        }
        // The banner only exists once the mutation coroutine sets the info message.
        composeRule.waitUntil(timeoutMillis = 5000) { viewModel.uiState.value.infoMessage != null }
        // The info banner is rendered at the top of the list.
        composeRule.onNodeWithText("Settings").performScrollTo()
        // The info message is honest about what this build does.
        composeRule.onNodeWithText(
            "Gesture control enabled. Gestures act on the local staged content and never transfer by themselves."
        ).assertIsDisplayed()
    }

    @Test
    fun incomingApprovalCannotBeDisabledFromSettings() {
        val repo = FakeSettingsRepo(FlowwwSettings())
        val viewModel = SettingsViewModel(repo)

        composeRule.setContent {
            FlowwwTheme {
                SettingsScreen(viewModel = viewModel, onOpenGestureDiagnostics = {}, onOpenAbout = {}, onOpenHistory = {})
            }
        }

        composeRule.waitUntil(timeoutMillis = 3000) { !viewModel.uiState.value.isLoading }

        composeRule.scrollListUntilExists("settings_list", "Require approval for incoming transfers")
        composeRule.onNodeWithContentDescription("Require approval for incoming transfers")
            .performScrollTo()
            .performClick()

        // Policy invariant holds end-to-end: the setting stays required.
        composeRule.waitUntil(timeoutMillis = 3000) {
            repo.current.value?.incomingApprovalRequired == true
        }
        // The toggle is pinned on and disabled in the UI itself.
        composeRule.onNodeWithContentDescription("Require approval for incoming transfers")
            .assertIsOn()
            .assertIsNotEnabled()
    }

    @Test
    fun deviceNameEditPersists() {
        val repo = FakeSettingsRepo(FlowwwSettings())
        val viewModel = SettingsViewModel(repo)

        composeRule.setContent {
            FlowwwTheme {
                SettingsScreen(viewModel = viewModel, onOpenGestureDiagnostics = {}, onOpenAbout = {}, onOpenHistory = {})
            }
        }

        composeRule.waitUntil(timeoutMillis = 3000) { !viewModel.uiState.value.isLoading }

        composeRule.onNodeWithTag("device_name_field").performTextReplacement("Rover")

        composeRule.waitUntil(timeoutMillis = 5000) {
            repo.current.value?.deviceDisplayName == "Rover"
        }
        composeRule.onNodeWithTag("device_name_field").assertExists()
    }

    @Test
    fun gestureDiagnosticsAndHistoryButtonsNavigate() {
        val repo = FakeSettingsRepo(FlowwwSettings())
        val viewModel = SettingsViewModel(repo)
        var openedDiagnostics = false
        var openedHistory = false

        composeRule.setContent {
            FlowwwTheme {
                SettingsScreen(
                    viewModel = viewModel,
                    onOpenGestureDiagnostics = { openedDiagnostics = true },
                    onOpenAbout = {},
                    onOpenHistory = { openedHistory = true }
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 3000) { !viewModel.uiState.value.isLoading }

        composeRule.scrollListUntilExists("settings_list", "Gesture diagnostics & calibration")
        composeRule.onNodeWithText("Gesture diagnostics & calibration").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 3000) { openedDiagnostics }

        composeRule.scrollListUntilExists("settings_list", "Open operation history")
        composeRule.onNodeWithText("Open operation history").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 3000) { openedHistory }
    }
}
