package com.flowww.android.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flowww.android.test.util.FakeOperationRepo
import com.flowww.android.test.util.scrollListUntilExists
import com.flowww.android.test.util.transferOperation
import com.flowww.android.ui.theme.FlowwwTheme
import com.flowww.android.ui.viewmodel.TransferViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransferHistoryScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyHistoryIsHonest() {
        val repo = FakeOperationRepo()
        val viewModel = TransferViewModel(repo)

        composeRule.setContent {
            FlowwwTheme {
                TransferHistoryScreen(viewModel, onBack = {}, onOpenDetails = {})
            }
        }

        composeRule.waitUntil(timeoutMillis = 3000) { !viewModel.historyState.value.isLoading }

        composeRule.onNodeWithText("Operation history").assertIsDisplayed()
        composeRule.onNodeWithText("No operations recorded").assertIsDisplayed()
        // No fabricated records may ever appear.
        composeRule.onNodeWithText("file-op-1.bin").assertDoesNotExist()
    }

    @Test
    fun recordedOperationsRenderWithTheirDetails() {
        val repo = FakeOperationRepo()
        val op = transferOperation("op-1", "Report.pdf", title = "Exported Report.pdf")
        repo.add(op)
        val viewModel = TransferViewModel(repo)

        composeRule.setContent {
            FlowwwTheme {
                TransferHistoryScreen(viewModel, onBack = {}, onOpenDetails = {})
            }
        }

        composeRule.waitUntil(timeoutMillis = 3000) { !viewModel.historyState.value.isLoading }

        composeRule.scrollListUntilExists("history_list", "Exported Report.pdf")
        composeRule.onNodeWithText("Exported Report.pdf").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Local export").assertIsDisplayed()
    }

    @Test
    fun clearHistoryRequiresConfirmationAndClearsRecords() {
        val repo = FakeOperationRepo()
        repo.add(transferOperation("op-1", "Report.pdf"))
        val viewModel = TransferViewModel(repo)

        composeRule.setContent {
            FlowwwTheme {
                TransferHistoryScreen(viewModel, onBack = {}, onOpenDetails = {})
            }
        }

        composeRule.waitUntil(timeoutMillis = 3000) { !viewModel.historyState.value.isLoading }

        composeRule.onNodeWithText("Clear history").performScrollTo().performClick()
        composeRule.onNodeWithText("Clear history?").assertIsDisplayed()

        // Dismissing keeps records.
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithText("Exported 1 item(s)").assertIsDisplayed()

        // Confirming clears everything.
        composeRule.onNodeWithText("Clear history").performScrollTo().performClick()
        composeRule.onNodeWithTag("history_clear_confirm").performClick()

        composeRule.waitUntil(timeoutMillis = 3000) { repo.operations.value.isEmpty() }
        composeRule.onNodeWithText("No operations recorded").assertIsDisplayed()
    }
}
