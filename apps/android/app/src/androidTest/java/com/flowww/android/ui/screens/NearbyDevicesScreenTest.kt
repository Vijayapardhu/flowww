package com.flowww.android.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flowww.android.domain.models.DiscoveryStatus
import com.flowww.android.domain.models.NetworkEnvironment
import com.flowww.android.domain.models.WifiNetwork
import com.flowww.android.domain.models.WifiPermissionState
import com.flowww.android.test.util.FakeDiscoveryRepo
import com.flowww.android.ui.theme.FlowwwTheme
import com.flowww.android.ui.viewmodel.NearbyDevicesViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NearbyDevicesScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun networkStateRendersHonestlyAndNoPeersAreInvented() {
        val repo = FakeDiscoveryRepo()
        val viewModel = NearbyDevicesViewModel(repo)

        composeRule.setContent {
            FlowwwTheme {
                NearbyDevicesScreen(viewModel, onOpenPairing = {}, onOpenDevice = {})
            }
        }

        composeRule.waitUntil(timeoutMillis = 3000) {
            viewModel.uiState.value.status != DiscoveryStatus.INITIALIZING
        }

        composeRule.onNodeWithText("Nearby").assertIsDisplayed()
        composeRule.onNodeWithText("Connection").assertIsDisplayed()
        composeRule.onNodeWithText("Connected").assertIsDisplayed()
        // No Flowww peer is ever fabricated; the empty state says why.
        composeRule.onNodeWithText("No Flowww peers").assertIsDisplayed()
        composeRule.onNodeWithText("Pair a device instead").assertIsDisplayed()
    }

    @Test
    fun scanButtonIsDisabledWithoutThePermission() {
        val repo = FakeDiscoveryRepo(
            initialEnv = NetworkEnvironment(
                isConnected = true,
                transport = "Wi-Fi",
                isWifiEnabled = true,
                permissionState = WifiPermissionState.NOT_GRANTED,
                requiredPermission = "NEARBY_WIFI_DEVICES"
            )
        )
        val viewModel = NearbyDevicesViewModel(repo)

        composeRule.setContent {
            FlowwwTheme {
                NearbyDevicesScreen(viewModel, onOpenPairing = {}, onOpenDevice = {})
            }
        }

        composeRule.waitUntil(timeoutMillis = 3000) {
            viewModel.uiState.value.status != DiscoveryStatus.INITIALIZING
        }

        composeRule.onNodeWithText("Allow nearby devices").assertIsDisplayed()
        composeRule.onNodeWithText("Scan Wi-Fi").assertIsNotEnabled()
    }

    @Test
    fun scannedNetworksAreListedWithRealLabels() {
        val repo = FakeDiscoveryRepo()
        repo.scan = Result.success(
            listOf(WifiNetwork("HomeNet", "aa:bb:cc:dd:ee:ff", -58, 5180, "[WPA2-PSK-CCMP]"))
        )
        val viewModel = NearbyDevicesViewModel(repo)

        composeRule.setContent {
            FlowwwTheme {
                NearbyDevicesScreen(viewModel, onOpenPairing = {}, onOpenDevice = {})
            }
        }

        composeRule.onNodeWithText("Scan Wi-Fi").performClick()
        composeRule.waitUntil(timeoutMillis = 5000) {
            viewModel.uiState.value.environment.networks.isNotEmpty()
        }
        // The Wi-Fi card is the third card and can sit below the fold.
        composeRule.onNodeWithText("HomeNet").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Good", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun pairButtonNavigatesToPairing() {
        var opened = false
        val viewModel = NearbyDevicesViewModel(FakeDiscoveryRepo())

        composeRule.setContent {
            FlowwwTheme {
                NearbyDevicesScreen(viewModel, onOpenPairing = { opened = true }, onOpenDevice = {})
            }
        }

        composeRule.onNodeWithText("Pair a device instead").performClick()
        composeRule.waitUntil(timeoutMillis = 3000) { opened }
    }
}
