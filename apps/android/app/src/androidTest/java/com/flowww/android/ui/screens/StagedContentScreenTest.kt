package com.flowww.android.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flowww.android.test.util.FakeGateway
import com.flowww.android.test.util.FakeOperationRepo
import com.flowww.android.test.util.FakeStagedRepo
import com.flowww.android.test.util.FakeTrustedRepo
import com.flowww.android.test.util.stagedItem
import com.flowww.android.test.util.localTrustedDevice
import com.flowww.android.transfer.LocalContentHandoff
import com.flowww.android.ui.theme.FlowwwTheme
import com.flowww.android.ui.viewmodel.StagedContentViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StagedContentScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun viewModelFor(
        repo: FakeStagedRepo,
        trusted: FakeTrustedRepo = FakeTrustedRepo(),
        gateway: FakeGateway = FakeGateway()
    ): StagedContentViewModel {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val handoff = LocalContentHandoff(gateway) { context }
        return StagedContentViewModel(
            repository = repo,
            trustedDeviceRepository = trusted,
            gateway = gateway,
            handoff = handoff,
            operations = FakeOperationRepo()
        )
    }

    @Test
    fun stagedItemsRenderWithSelectionToolbar() {
        val repo = FakeStagedRepo(listOf(stagedItem("a", "doc-a"), stagedItem("b", "doc-b")))
        val viewModel = viewModelFor(repo)

        composeRule.setContent {
            FlowwwTheme {
                StagedContentScreen(viewModel)
            }
        }

        composeRule.onNodeWithText("doc-a").assertIsDisplayed()
        composeRule.onNodeWithText("doc-b").assertIsDisplayed()
        composeRule.onNodeWithText("Select all").performClick()
        composeRule.onNodeWithText("2/2 selected").assertIsDisplayed()
        composeRule.onNodeWithText("Clear").performClick()
        composeRule.onNodeWithText("0/2 selected").assertIsDisplayed()
    }

    @Test
    fun removeSelectedRequiresConfirmationAndRemovesItems() {
        val repo = FakeStagedRepo(listOf(stagedItem("a", "doc-a"), stagedItem("b", "doc-b")))
        val viewModel = viewModelFor(repo)

        composeRule.setContent {
            FlowwwTheme {
                StagedContentScreen(viewModel)
            }
        }

        composeRule.onNodeWithText("doc-a").assertIsDisplayed()
        composeRule.onNodeWithText("Select all").performClick()
        composeRule.onNodeWithText("Remove").performClick()

        // The destructive confirm must not happen without the user confirming.
        composeRule.onNodeWithText("Remove selected items?").assertIsDisplayed()
        composeRule.onNodeWithTag("staged_remove_selected_confirm").performClick()

        composeRule.waitUntil(timeoutMillis = 5000) { repo.items.value.isEmpty() }
        composeRule.onNodeWithText("doc-a").assertDoesNotExist()
        composeRule.onNodeWithText("Removed 2 item(s)").assertIsDisplayed()
    }

    @Test
    fun dismissingRemoveConfirmationKeepsItems() {
        val repo = FakeStagedRepo(listOf(stagedItem("a", "doc-a")))
        val viewModel = viewModelFor(repo)

        composeRule.setContent {
            FlowwwTheme {
                StagedContentScreen(viewModel)
            }
        }

        composeRule.onNodeWithText("Select all").performClick()
        composeRule.onNodeWithText("Remove").performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.waitUntil(timeoutMillis = 3000) { composeRule.onNodeWithText("doc-a").isDisplayed() }
        assertEquals(1, repo.items.value.size)
    }

    @Test
    fun sendIsUnavailableWhenNoTransferPathExists() {
        // The repository fake records honest failures: no Rust engine exists in this build.
        val repo = FakeStagedRepo(listOf(stagedItem("a", "doc-a")))
        val viewModel = viewModelFor(repo, FakeTrustedRepo(listOf(localTrustedDevice("d1", "Laptop"))))

        composeRule.setContent {
            FlowwwTheme {
                StagedContentScreen(viewModel)
            }
        }

        composeRule.onNodeWithText("Send").assertDoesNotExist()
        composeRule.onNodeWithText("Share").assertIsDisplayed()
        composeRule.onNodeWithText("Export").assertIsDisplayed()
    }
}
