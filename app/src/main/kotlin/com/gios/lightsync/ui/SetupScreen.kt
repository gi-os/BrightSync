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
import com.gios.lightsync.sync.Server
import com.gios.lightsync.ui.theme.Dim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The three things LightSync needs, and whether BasilNet is answering.
 *
 * The passphrase warning is not decoration. Lose it and every blob on the server is scrap: there
 * is no recovery path by design, because a server that could recover your data is a server that
 * could read it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }

    var server by remember { mutableStateOf(prefs.server) }
    var token by remember { mutableStateOf(prefs.token) }
    var pass by remember { mutableStateOf(prefs.passphrase) }
    var auto by remember { mutableStateOf(prefs.autoDaily) }
    var editing by remember { mutableStateOf<String?>(null) }
    var reachable by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(server, token) {
        reachable = if (server.isEmpty() || token.isEmpty()) {
            null
        } else {
            withContext(Dispatchers.IO) { Server(server, token).reachable() }
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
                    "with PBKDF2-HMAC-SHA256(pass, salt, 200000)",
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
