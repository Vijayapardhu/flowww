package com.flowww.android.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flowww.android.domain.models.LocalOperationStatus
import com.flowww.android.test.util.FakeOperationRepo
import com.flowww.android.test.util.scrollListUntilExists
import com.flowww.android.test.util.transferOperation
import com.flowww.android.ui.theme.FlowwwTheme
import com.flowww.android.ui.viewmodel.TransferViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActiveTransfersScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyStateHasHonestCopyAndNoFakeOperations() {
        val repo = FakeOperationRepo()
        val viewModel = TransferViewModel(repo)

        composeRule.setContent {
            FlowwwTheme {
                ActiveTransfersScreen(viewModel, onOpenHistory = {}, onOpenDetails = {})
            }
        }

        composeRule.waitUntil(timeoutMillis = 3000) { !viewModel.uiState.value.isLoading }

        composeRule.onNodeWithText("Transfer manager").assertIsDisplayed()
        composeRule.onNodeWithText("No local operations yet").assertIsDisplayed()
        // The network banner is honest about what this build cannot do.
        composeRule.onNodeWithText(
            "Unavailable: no Flowww peer in this build"
        ).assertIsDisplayed()
    }

    @Test
    fun recordedOperationsRenderWithStatus() {
        val repo = FakeOperationRepo()
        repo.add(transferOperation("op-1", "Report.pdf", title = "Exported Report.pdf"))
        repo.add(transferOperation("op-2", "Notes.txt", status = LocalOperationStatus.FAILED, title = "Exported Notes.txt"))
        val viewModel = TransferViewModel(repo)

        composeRule.setContent {
            FlowwwTheme {
                ActiveTransfersScreen(viewModel, onOpenHistory = {}, onOpenDetails = {})
            }
        }

        composeRule.waitUntil(timeoutMillis = 3000) { !viewModel.uiState.value.isLoading }

        // Each title is unique; cards render them, chips never repeat them.
        composeRule.scrollListUntilExists("transfers_list", "Exported Report.pdf")
        composeRule.onNodeWithText("Exported Report.pdf").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Exported Notes.txt").performScrollTo().assertIsDisplayed()
        assertEquals(2, repo.operations.value.size)
    }

    @Test
    fun openHistoryButtonNavigates() {
        var opened = false
        val viewModel = TransferViewModel(FakeOperationRepo())

        composeRule.setContent {
            FlowwwTheme {
                ActiveTransfersScreen(viewModel, onOpenHistory = { opened = true }, onOpenDetails = {})
            }
        }

        composeRule.waitUntil(timeoutMillis = 3000) { !viewModel.uiState.value.isLoading }

        composeRule.onNodeWithText("Open history").performClick()
        composeRule.waitUntil(timeoutMillis = 3000) { opened }
    }
}
