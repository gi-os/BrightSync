package com.gios.lightsync.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.gios.lightsync.sync.Immich
import com.gios.lightsync.sync.Server
import com.gios.lightsync.ui.theme.Dim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What BrightSync needs to work, and whether the two things on BasilNet are answering.
 *
 * Two servers rather than one, which is worth being plain about on this screen: blobs go to the
 * LightSync container, which cannot read them, and photographs go to Immich, which must. The
 * passphrase protects the first and is unrecoverable; the Immich key protects nothing on the
 * phone and can be rotated in Immich whenever you like. They are separated here so that neither
 * one is ever typed into the other's box.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onBack: () -> Unit, onScan: () -> Unit = {}, onSignIn: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }

    var server by remember { mutableStateOf(prefs.server) }
    var token by remember { mutableStateOf(prefs.token) }
    var pass by remember { mutableStateOf(prefs.passphrase) }
    var auto by remember { mutableStateOf(prefs.autoDaily) }
    var immich by remember { mutableStateOf(prefs.immich) }
    var immichKey by remember { mutableStateOf(prefs.immichKey) }
    var album by remember { mutableStateOf(prefs.immichAlbum) }
    var photosAuto by remember { mutableStateOf(prefs.photosAuto) }
    var mode by remember { mutableStateOf(prefs.mode) }
    var editing by remember { mutableStateOf<String?>(null) }
    var reachable by remember { mutableStateOf<Boolean?>(null) }
    var immichUp by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(server, token) {
        reachable = if (server.isEmpty() || token.isEmpty()) {
            null
        } else {
            withContext(Dispatchers.IO) { Server(server, token).reachable() }
        }
    }

    LaunchedEffect(immich, immichKey) {
        immichUp = if (immich.isEmpty()) {
            null
        } else {
            withContext(Dispatchers.IO) { Immich(immich, immichKey).reachable() }
        }
    }

    val scroll = rememberScrollState()
    WheelScroll(scroll)

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = { Text("Setup", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(scroll)) {
            SectionLabel("SETUP CODE")
            MenuRow(
                label = "Scan a code",
                detail = "QR",
                sub = "Open /enroll/<token> on BasilNet and point the phone at it. Everything " +
                    "below arrives at once.",
                onClick = onScan,
            )
            Rule()

            SectionLabel("WHAT THIS PHONE SENDS")
            MenuRow(
                label = when (mode) {
                    Prefs.MODE_PHOTOS -> "Photos only"
                    Prefs.MODE_BACKUPS -> "Backups only"
                    else -> "Everything"
                },
                detail = "CHANGE",
                sub = "Tap to cycle. Either half works without the other.",
                onClick = {
                    mode = when (mode) {
                        Prefs.MODE_EVERYTHING -> Prefs.MODE_PHOTOS
                        Prefs.MODE_PHOTOS -> Prefs.MODE_BACKUPS
                        else -> Prefs.MODE_EVERYTHING
                    }
                    prefs.mode = mode
                },
            )
            Rule()

            SectionLabel("BASILNET")
            MenuRow(
                label = "Server",
                detail = if (server.isEmpty()) "NOT SET" else "SET",
                sub = server.ifEmpty { "http://192.168.68.59:8099" },
                onClick = { editing = "server" },
            )
            MenuRow(
                label = "Token",
                detail = if (token.isEmpty()) "NOT SET" else "SET",
                sub = "the same value as LIGHTSYNC_TOKEN on the server",
                onClick = { editing = "token" },
            )
            MenuRow(
                label = "Reachable",
                detail = when (reachable) {
                    null -> "—"
                    true -> "YES"
                    false -> "NO"
                },
                sub = if (reachable == false) "not answering — are you on the home wifi?" else null,
                dim = reachable != true,
            )
            Rule()

            SectionLabel("ENCRYPTION")
            MenuRow(
                label = "Passphrase",
                detail = if (pass.isEmpty()) "NOT SET" else "SET",
                sub = "Blobs are sealed with this before upload, so the server never holds " +
                    "anything readable. Lose it and the backups are scrap — there is no reset.",
                onClick = { editing = "pass" },
            )
            Rule()

            SectionLabel("PHOTOS")
            MenuRow(
                label = "Immich",
                detail = if (immich.isEmpty()) "NOT SET" else "SET",
                sub = immich.ifEmpty { "http://192.168.68.59:2283" },
                onClick = { editing = "immich" },
            )
            MenuRow(
                label = "Sign in instead",
                detail = "EASIER",
                sub = "Immich address and your password, and BrightSync asks Immich for a key " +
                    "of its own. The password is not stored.",
                onClick = onSignIn,
            )
            MenuRow(
                label = "API key",
                detail = if (immichKey.isEmpty()) "NOT SET" else "SET",
                sub = "Immich → Account Settings → API Keys. Upload and album rights are enough.",
                onClick = { editing = "immichKey" },
            )
            MenuRow(
                label = "Album",
                detail = if (album.isEmpty()) "OFF" else "SET",
                sub = album.ifEmpty { "no album — frames land in the main timeline" },
                onClick = { editing = "album" },
            )
            MenuRow(
                label = "Answering",
                detail = when (immichUp) {
                    null -> "—"
                    true -> "YES"
                    false -> "NO"
                },
                dim = immichUp != true,
            )
            MenuRow(
                label = "Upload the roll",
                detail = if (photosAuto) "ON" else "OFF",
                sub = "Photographs go up in the clear, because a library Immich cannot decode " +
                    "would have no thumbnails, no dates and no search. Nothing is ever deleted " +
                    "from the phone.",
                onClick = {
                    photosAuto = !photosAuto
                    prefs.photosAuto = photosAuto
                },
            )
            Rule()

            SectionLabel("SCHEDULE")
            MenuRow(
                label = "Daily backup",
                detail = if (auto) "ON" else "OFF",
                sub = "on wifi only, since BasilNet is only reachable at home",
                onClick = {
                    auto = !auto
                    prefs.autoDaily = auto
                },
            )
            Gap(20)
            Text(
                "Recovery of last resort, with only curl and openssl:\n\n" +
                    "curl -H 'X-Token: …' $server/b/<app>/latest -o blob\n" +
                    "# strip the 4-byte magic, 16-byte salt and 12-byte IV, then AES-256-GCM " +
                    "with PBKDF2-HMAC-SHA256(pass, salt, 200000)\n\n" +
                    "Photographs need none of that: they are ordinary files in Immich, and any " +
                    "Immich client or immich-go can read them back.",
                style = MaterialTheme.typography.bodyMedium,
                color = Dim,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Gap(28)
        }
    }

    editing?.let { field ->
        var draft by remember(field) {
            mutableStateOf(
                when (field) {
                    "server" -> server
                    "token" -> token
                    "immich" -> immich
                    "immichKey" -> immichKey
                    "album" -> album
                    else -> pass
                },
            )
        }
        AlertDialog(
            containerColor = Color.Black,
            onDismissRequest = { editing = null },
            title = {
                Text(
                    when (field) {
                        "server" -> "Server"
                        "token" -> "Token"
                        "immich" -> "Immich"
                        "immichKey" -> "API key"
                        "album" -> "Album"
                        else -> "Passphrase"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    when (field) {
                        "server" -> {
                            prefs.server = draft
                            server = prefs.server
                        }
                        "token" -> {
                            prefs.token = draft
                            token = prefs.token
                        }
                        "immich" -> {
                            prefs.immich = draft
                            immich = prefs.immich
                        }
                        "immichKey" -> {
                            prefs.immichKey = draft
                            immichKey = prefs.immichKey
                        }
                        "album" -> {
                            prefs.immichAlbum = draft
                            album = prefs.immichAlbum
                        }
                        else -> {
                            prefs.passphrase = draft
                            pass = prefs.passphrase
                        }
                    }
                    editing = null
                }) { Text("SAVE", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("CANCEL", color = Dim) }
            },
        )
    }
}

@Composable
fun SectionLabel(text: String) {
    Gap(18)
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Dim,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    Gap(6)
}
