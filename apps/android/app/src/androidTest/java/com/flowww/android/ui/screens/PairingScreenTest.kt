package com.flowww.android.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flowww.android.pairing.PairingResult
import com.flowww.android.test.util.FakePairingRepo
import com.flowww.android.test.util.pairingUriData
import com.flowww.android.ui.theme.FlowwwTheme
import com.flowww.android.ui.viewmodel.PairingViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairingScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyUriIsRejectedBeforeSubmission() {
        val viewModel = PairingViewModel(FakePairingRepo())

        composeRule.setContent {
            FlowwwTheme {
                PairingScreen(viewModel, onBack = {})
            }
        }

        composeRule.onNodeWithText("Pair a device").assertIsDisplayed()
        composeRule.onNodeWithText("Validate URI").performClick()
        composeRule.onNodeWithText("Enter a pairing URI.").assertIsDisplayed()
    }

    @Test
    fun malformedUriIsRejectedHonestLy() {
        val viewModel = PairingViewModel(FakePairingRepo())

        composeRule.setContent {
            FlowwwTheme {
                PairingScreen(viewModel, onBack = {})
            }
        }

        composeRule.onNodeWithTag("pairing_uri_input").performTextReplacement("https://example.com")
        composeRule.onNodeWithText("Validate URI").performClick()

        composeRule.waitUntil(timeoutMillis = 3000) {
            viewModel.uiState.value.validationError != null
        }
        composeRule.onNodeWithText("Pairing URI must start with", substring = true).assertIsDisplayed()
    }

    @Test
    fun wellFormedUriIsParsedButPairingIsNotFaked() {
        val repo = FakePairingRepo(
            validationResult = Result.success(pairingUriData()),
            pairResult = Result.success(
                PairingResult.Unavailable("The secure pairing protocol is not available in this build.")
            )
        )
        val viewModel = PairingViewModel(repo)

        composeRule.setContent {
            FlowwwTheme {
                PairingScreen(viewModel, onBack = {})
            }
        }

        composeRule.onNodeWithTag("pairing_uri_input").performTextReplacement("flowww://pair/v1/dGVzdA")
        composeRule.onNodeWithText("Validate URI").performClick()

        composeRule.waitUntil(timeoutMillis = 3000) {
            viewModel.uiState.value.parsed != null
        }
        composeRule.onNodeWithText("Parsed pairing request").assertIsDisplayed()
        composeRule.onNodeWithText("Office PC").assertIsDisplayed()
        // The pair result is honest: no device was paired.
        composeRule.onNodeWithText(
            "The secure pairing protocol is not available in this build."
        ).assertIsDisplayed()
    }
}
