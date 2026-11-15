package com.flowww.android.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flowww.android.test.util.FakeTrustedRepo
import com.flowww.android.test.util.localTrustedDevice
import com.flowww.android.ui.theme.FlowwwTheme
import com.flowww.android.ui.viewmodel.TrustedDevicesViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrustedDevicesScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyStateSaysDevicesAreLocalRecordsOnly() {
        val viewModel = TrustedDevicesViewModel(FakeTrustedRepo())

        composeRule.setContent {
            FlowwwTheme {
                TrustedDevicesScreen(viewModel, onOpenPairing = {})
            }
        }

        composeRule.waitUntil(timeoutMillis = 3000) { !viewModel.uiState.value.isLoading }

        composeRule.onNodeWithText("No devices saved").assertIsDisplayed()
        composeRule.onNodeWithText("Add device").assertIsDisplayed()
        composeRule.onNodeWithText(
            "The entries below are local records only, not verified pairings.",
            substring = true
        ).assertIsDisplayed()
    }

    @Test
    fun savedDeviceRendersAndCanBeOpenedInDetails() {
        val repo = FakeTrustedRepo(listOf(localTrustedDevice("local_laptop", "Laptop", "Windows", "fp-abc")))
        val viewModel = TrustedDevicesViewModel(repo)

        composeRule.setContent {
            FlowwwTheme {
                TrustedDevicesScreen(viewModel, onOpenPairing = {})
            }
        }

        composeRule.waitUntil(timeoutMillis = 3000) { !viewModel.uiState.value.isLoading }

        composeRule.onNodeWithText("Laptop").assertIsDisplayed()
        composeRule.onNodeWithText("Windows", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Details").performClick()
        composeRule.onNodeWithText("Trust level").assertIsDisplayed()
        composeRule.onNodeWithText("fp-abc").assertIsDisplayed()
    }

    @Test
    fun addDevicePersistsARecord() {
        val repo = FakeTrustedRepo()
        val viewModel = TrustedDevicesViewModel(repo)

        composeRule.setContent {
            FlowwwTheme {
                TrustedDevicesScreen(viewModel, onOpenPairing = {})
            }
        }

        composeRule.waitUntil(timeoutMillis = 3000) { !viewModel.uiState.value.isLoading }

        composeRule.onNodeWithText("Add device").performClick()
        composeRule.onNodeWithTag("device_name_input").performTextReplacement("Office PC")
        composeRule.onNodeWithTag("device_platform_input").performTextReplacement("Windows")
        composeRule.onNodeWithText("Save").performClick()

        composeRule.waitUntil(timeoutMillis = 3000) {
            viewModel.uiState.value.devices.any { it.displayName == "Office PC" }
        }
        composeRule.onNodeWithText("Office PC").assertIsDisplayed()
    }

    @Test
    fun forgetRemovesTheRecordAfterConfirmation() {
        val repo = FakeTrustedRepo(listOf(localTrustedDevice("local_old", "Old Win", "Windows")))
        val viewModel = TrustedDevicesViewModel(repo)

        composeRule.setContent {
            FlowwwTheme {
                TrustedDevicesScreen(viewModel, onOpenPairing = {})
            }
        }

        composeRule.waitUntil(timeoutMillis = 3000) { !viewModel.uiState.value.isLoading }

        composeRule.onNodeWithText("Forget").performClick()
        // The destructive action must not happen without confirmation.
        composeRule.onNodeWithText("Remove device?").assertIsDisplayed()
        composeRule.onNodeWithTag("trusted_forget_confirm").performClick()

        composeRule.waitUntil(timeoutMillis = 3000) {
            viewModel.uiState.value.devices.isEmpty()
        }
        composeRule.onNodeWithText("No devices saved").assertIsDisplayed()
    }
}
