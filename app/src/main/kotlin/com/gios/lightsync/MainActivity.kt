package com.gios.lightsync

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.gios.light.common.hw.LightKey
import com.gios.light.common.hw.LightKeys
import com.gios.light.common.hw.LocalWheelBus
import com.gios.light.common.hw.WheelBus
import com.gios.lightsync.ui.AppsScreen
import com.gios.lightsync.ui.FirstRunScreen
import com.gios.lightsync.ui.ImmichSignInScreen
import com.gios.lightsync.ui.ScanScreen
import com.gios.lightsync.ui.FleetScreen
import com.gios.lightsync.ui.SetupScreen
import com.gios.lightsync.ui.TabBar
import com.gios.lightsync.ui.theme.LightSyncTheme

class MainActivity : ComponentActivity() {

    private val wheel = WheelBus()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LightSyncTheme {
                CompositionLocalProvider(LocalWheelBus provides wheel) {
                    Root()
                }
            }
        }
    }

    /**
     * Two tabs and a settings screen stacked over them.
     *
     * Setup is not a third tab. It is entered from the Apps tab, it is the only screen here that
     * changes anything on the server, and giving it equal billing at the bottom of the screen
     * would put a destructive edit one stray thumb away from a list you scroll every day.
     */
    @Composable
    private fun Root() {
        val prefs = remember { Prefs(this) }
        var tab by remember { mutableIntStateOf(0) }
        var setup by remember { mutableStateOf(false) }
        // Shown on a phone that has neither half working and has not been walked through once.
        // Deliberately not "shown until configured": a phone that was set up and then had its
        // server taken away needs the error on the front screen, not a wizard over the top of it.
        var firstRun by remember { mutableStateOf(!prefs.setupDone && !prefs.configured) }
        var scanning by remember { mutableStateOf(false) }
        var signingIn by remember { mutableStateOf(false) }
        BackHandler(enabled = setup || scanning || signingIn) {
            when {
                scanning -> scanning = false
                signingIn -> signingIn = false
                else -> setup = false
            }
        }

        if (signingIn) {
            ImmichSignInScreen(
                onBack = { signingIn = false },
                onScan = {
                    signingIn = false
                    scanning = true
                },
            )
            return
        }

        if (scanning) {
            ScanScreen(
                onScanned = { enrollment ->
                    enrollment.applyTo(prefs)
                    scanning = false
                },
                onManual = { scanning = false },
            )
            return
        }

        if (firstRun) {
            FirstRunScreen(
                onManual = {
                    firstRun = false
                    setup = true
                },
                onDone = { firstRun = false },
            )
            return
        }

        if (setup) {
            SetupScreen(
                onBack = { setup = false },
                onScan = { scanning = true },
                onSignIn = { signingIn = true },
            )
            return
        }

        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> AppsScreen(onSetup = { setup = true })
                    else -> FleetScreen()
                }
            }
            TabBar(selected = tab, labels = listOf("APPS", "FLEET"), onSelect = { tab = it })
        }
    }

    /**
     * The wheel scrolls, as it does in the rest of the family.
     *
     * This used to be a hand-rolled scancode check. `LightKeys` is the same logic with the two
     * fallbacks in the right order — resolve Light's own key label first, and only trust the raw
     * scancode when it came from one of the two devices that physically own these controls, so a
     * paired Bluetooth keyboard's `r` cannot scroll the list.
     *
     * Only the two wheel directions are consumed. The wheel click and the camera button belong
     * to LightControl, and swallowing them here would take them away from it.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (LightKeys.of(event)) {
            LightKey.WheelUp -> {
                if (event.action == KeyEvent.ACTION_DOWN) wheel.send(1)
                return true
            }
            LightKey.WheelDown -> {
                if (event.action == KeyEvent.ACTION_DOWN) wheel.send(-1)
                return true
            }
            else -> Unit
        }
        return super.dispatchKeyEvent(event)
    }
}
