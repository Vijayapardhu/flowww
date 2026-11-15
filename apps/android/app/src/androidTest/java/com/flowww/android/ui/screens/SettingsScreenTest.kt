package com.flowww.android.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
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

        composeRule.onNodeWithText("Device display name").assertIsDisplayed()
        composeRule.onNodeWithText("Auto-connect to last device").assertIsDisplayed()
        composeRule.onNodeWithText("Transfer notifications").assertIsDisplayed()
        composeRule.onNodeWithText("Motion gestures").assertIsDisplayed()
        composeRule.onNodeWithText("Require approval for incoming transfers").assertIsDisplayed()
        // Security policy is stated in the UI itself.
        composeRule.onNodeWithText("Security policy: approval cannot be disabled").assertIsDisplayed()
        composeRule.onNodeWithText("Default save location: Not set").assertIsDisplayed()

        composeRule.onNodeWithText("About Flowww").performClick()
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

        composeRule.onNodeWithContentDescription("Motion gestures")
            .performScrollTo()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 5000) {
            repo.current.value?.motionGesturesEnabled == true
        }
        // The info message is honest about what still needs the Rust layer.
        composeRule.onNodeWithText(
            "Gesture preference saved. Real gesture-to-transfer handoff requires Rust integration."
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

        composeRule.onNodeWithContentDescription("Require approval for incoming transfers")
            .performScrollTo()
            .performClick()

        // Policy invariant holds end-to-end: the setting stays required.
        composeRule.waitUntil(timeoutMillis = 3000) {
            repo.current.value?.incomingApprovalRequired == true
        }
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

        composeRule.onNodeWithText("Gesture diagnostics & calibration").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 3000) { openedDiagnostics }

        composeRule.onNodeWithText("Transfer history").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 3000) { openedHistory }
    }
}
