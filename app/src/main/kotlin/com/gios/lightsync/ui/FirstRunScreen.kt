package com.gios.lightsync.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gios.light.common.hw.WheelScroll
import com.gios.lightsync.Prefs
import com.gios.lightsync.sync.Enrollment
import com.gios.lightsync.sync.Roll
import com.gios.lightsync.ui.theme.Dim

/**
 * The guided setup, replacing a first launch that used to open onto a wall of empty fields.
 *
 * Four steps, each of which is one decision or one action: what this phone should do, scan the
 * code, the one secret the code cannot carry, and access to the roll. Every step can be skipped
 * — the settings screen is still there and still does everything this does — but the order is
 * the order in which the values become useful, and a phone that follows it cannot end up
 * half-configured in the way a settings screen invites.
 *
 * "Photos only" is a real answer and not a lesser one. Immich needs neither the blob store nor
 * a passphrase, so a phone set up that way is two steps and no typing at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirstRunScreen(onManual: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }

    var step by remember { mutableStateOf(Step.Mode) }
    var mode by remember { mutableStateOf(prefs.mode) }
    var scanned by remember { mutableStateOf<Enrollment?>(null) }
    var passphrase by remember { mutableStateOf(prefs.passphrase) }
    var rollGranted by remember { mutableStateOf(Roll.granted(context)) }

    val askForRoll = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { rollGranted = Roll.granted(context) }

    /** The next step that still has something to ask for, given what is already known. */
    fun advance(from: Step) {
        val wantsBlobs = mode != Prefs.MODE_PHOTOS
        val wantsPhotos = mode != Prefs.MODE_BACKUPS
        step = when {
            // Photographs alone need no server, no token and no passphrase, so that path skips
            // the code entirely and asks for the two things Immich actually wants.
            from < Step.Immich && mode == Prefs.MODE_PHOTOS && !prefs.photosReady -> Step.Immich
            from < Step.Scan && mode != Prefs.MODE_PHOTOS -> Step.Scan
            from < Step.Passphrase && wantsBlobs && prefs.passphrase.isEmpty() -> Step.Passphrase
            from < Step.Roll && wantsPhotos && !rollGranted -> Step.Roll
            else -> Step.Done
        }
    }

    if (step == Step.Scan) {
        // Full bleed: a viewfinder inside a padded card is a viewfinder you have to aim twice.
        ScanScreen(
            onScanned = { enrollment ->
                enrollment.applyTo(prefs)
                scanned = enrollment
                advance(Step.Scan)
            },
            onManual = onManual,
        )
        return
    }

    val scroll = rememberScrollState()
    WheelScroll(scroll)

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = { Text("Set up", style = MaterialTheme.typography.titleMedium) },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(scroll)) {
            when (step) {
                Step.Mode -> {
                    SectionLabel("WHAT SHOULD THIS PHONE SEND?")
                    ModeRow(
                        label = "Everything",
                        detail = mode == Prefs.MODE_EVERYTHING,
                        sub = "App data as sealed blobs, and the camera roll to Immich",
                    ) { mode = Prefs.MODE_EVERYTHING; prefs.mode = mode }
                    ModeRow(
                        label = "Photos only",
                        detail = mode == Prefs.MODE_PHOTOS,
                        sub = "Just the roll, into Immich. No blob store, no passphrase, nothing to type",
                    ) { mode = Prefs.MODE_PHOTOS; prefs.mode = mode }
                    ModeRow(
                        label = "Backups only",
                        detail = mode == Prefs.MODE_BACKUPS,
                        sub = "App data as before. Photographs stay on the phone",
                    ) { mode = Prefs.MODE_BACKUPS; prefs.mode = mode }
                    Gap(12)
                    Text(
                        "You can change this later, and either half works without the other.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Dim,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Gap(20)
                    BigButton(
                        "NEXT",
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        filled = true,
                    ) { advance(Step.Mode) }
                }

                Step.Immich -> {
                    ImmichSignIn(
                        onDone = { advance(Step.Immich) },
                        onScan = { step = Step.Scan },
                    )
                    Gap(16)
                    BigButton(
                        "SKIP",
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    ) { advance(Step.Immich) }
                }

                Step.Passphrase -> {
                    SectionLabel("PASSPHRASE")
                    Text(
                        "Blobs are sealed with this on the phone, so BasilNet holds nothing it " +
                            "can read. It is the one value the setup code does not carry, and " +
                            "there is no reset: lose it and the backups are scrap.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Dim,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Gap(16)
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = {
                            passphrase = it
                            prefs.passphrase = it
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                    Gap(20)
                    BigButton(
                        "NEXT",
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        filled = true,
                        enabled = passphrase.isNotEmpty(),
                    ) { advance(Step.Passphrase) }
                }

                Step.Roll -> {
                    SectionLabel("THE ROLL")
                    Text(
                        "Photographs are the one thing no app can hand over, so BrightSync reads " +
                            "DCIM itself. Nothing is ever deleted from the phone, and files go " +
                            "to Immich in the clear — a library it cannot decode would have no " +
                            "thumbnails, no dates and no search.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Dim,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Gap(20)
                    BigButton(
                        if (rollGranted) "ALLOWED" else "ALLOW",
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        filled = !rollGranted,
                        enabled = !rollGranted,
                    ) { askForRoll.launch(Roll.PERMISSIONS) }
                    Gap(10)
                    BigButton(
                        if (rollGranted) "NEXT" else "SKIP",
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    ) { advance(Step.Roll) }
                }

                Step.Done -> {
                    SectionLabel("READY")
                    (scanned?.summary() ?: fromPrefs(prefs)).forEach { line ->
                        MenuRow(label = line)
                    }
                    Gap(12)
                    Text(
                        "Backups run daily on wifi. BasilNet only answers at home, so a day away " +
                            "simply waits and then catches up fifty frames at a time.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Dim,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Gap(20)
                    BigButton(
                        "DONE",
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        filled = true,
                    ) {
                        prefs.setupDone = true
                        onDone()
                    }
                    Gap(10)
                    BigButton(
                        "OPEN SETTINGS",
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    ) {
                        prefs.setupDone = true
                        onManual()
                    }
                }

                Step.Scan -> Unit
            }
            Gap(28)
        }
    }
}

/** What is configured now, for a phone that reached the last step without scanning anything. */
private fun fromPrefs(prefs: Prefs): List<String> = buildList {
    if (prefs.ready) add("Backups → ${prefs.server}")
    if (prefs.photosReady) add("Photographs → ${prefs.immich}")
    if (isEmpty()) add("Nothing set up yet — open settings")
}

@Composable
private fun ModeRow(label: String, detail: Boolean, sub: String, onClick: () -> Unit) {
    MenuRow(
        label = label,
        // Bracketed rather than ticked: on a greyscale matte panel a checkmark glyph at this
        // size is a smudge, and the tab bar already teaches this idiom.
        detail = if (detail) "[ ✓ ]" else "",
        sub = sub,
        dim = !detail,
        onClick = onClick,
    )
    Rule()
}

private enum class Step { Mode, Immich, Scan, Passphrase, Roll, Done }
