package com.gios.lightsync

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.gios.lightsync.ui.AppsScreen
import com.gios.lightsync.ui.SetupScreen
import com.gios.lightsync.ui.theme.LightSyncTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class MainActivity : ComponentActivity() {

    private val notches = MutableSharedFlow<Int>(extraBufferCapacity = 64)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LightSyncTheme {
                CompositionLocalProvider(LocalNotches provides notches.asSharedFlow()) {
                    var setup by remember { mutableStateOf(false) }
                    BackHandler(enabled = setup) { setup = false }
                    if (setup) {
                        SetupScreen(onBack = { setup = false })
                    } else {
                        AppsScreen(onSetup = { setup = true })
                    }
                }
            }
        }
    }

    /** The wheel scrolls, as it does in the rest of the family. See LightControl. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val scan = event.scanCode
        val up = code == KeyEvent.keyCodeFromString("WHEEL_CCW") || scan == 19
        val down = code == KeyEvent.keyCodeFromString("WHEEL_CW") || scan == 20
        if ((up || down) && event.device?.name == "Pixart pat9126ja") {
            if (event.action == KeyEvent.ACTION_DOWN) notches.tryEmit(if (up) 1 else -1)
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

val LocalNotches = staticCompositionLocalOf<SharedFlow<Int>?> { null }
