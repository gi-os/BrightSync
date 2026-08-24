package com.gios.lightsync.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.gios.lightsync.Prefs
import com.gios.lightsync.sync.Immich
import com.gios.lightsync.ui.theme.Dim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Photographs, set up with the password you already know.
 *
 * The blob store needs a server, a token and a passphrase; Immich needs an address and an API
 * key. On a phone with this keyboard, that key — forty-three characters of base64 — was the
 * worst thing this app asked anyone to type, and the only one where a single wrong character
 * cannot be noticed until an upload fails hours later.
 *
 * So this signs in the way Immich's own app does and asks the server for a key instead. The
 * password makes exactly one request and is never stored; what is kept is a key scoped to
 * uploading, reading and one album — see [Immich.PERMISSIONS] — which is a credential that can
 * be revoked in Immich without touching anything else.
 *
 * There is no BasilNet here, no token and no passphrase, because photographs need none of them.
 * A phone that only wants Immich is this screen and nothing else.
 */
@Composable
fun ImmichSignIn(onDone: () -> Unit, onScan: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()

    var address by remember { mutableStateOf(prefs.immich.ifEmpty { "192.168.68.59:2283" }) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    Column(modifier) {
        SectionLabel("IMMICH")
        // The code first, because it is the path with nothing to mistype. Everything below is
        // the same two answers given by hand, for a phone that is nowhere near a screen.
        BigButton(
            "SCAN A CODE",
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            onClick = onScan,
        )
        Gap(14)
        Text(
            "The address of your Immich, and the account you log into it with. BrightSync asks " +
                "Immich for a key of its own and forgets the password — nothing else here is " +
                "needed for photographs.",
            style = MaterialTheme.typography.bodyMedium,
            color = Dim,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Gap(14)
        Field("Address", address, { address = it }) 
        Field("Email", email, { email = it })
        Field("Password", password, { password = it }, secret = true)
        Gap(8)
        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = Dim,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Gap(8)
        }
        BigButton(
            label = if (busy) "CONNECTING…" else "CONNECT",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            filled = true,
            enabled = !busy && address.isNotBlank() && email.isNotBlank() && password.isNotBlank(),
        ) {
            busy = true
            status = null
            scope.launch {
                val url = Immich.normalize(address)
                val result = withContext(Dispatchers.IO) {
                    runCatching { Immich(url, "").signInAndMintKey(email, password) }
                }
                busy = false
                result.fold(
                    onSuccess = { key ->
                        prefs.immich = url
                        prefs.immichKey = key
                        // Nothing is kept from this screen but the key: the password was used
                        // for one request, and the fields go out of scope with the composable.
                        password = ""
                        status = "Connected. Photographs will go to $url."
                        onDone()
                    },
                    onFailure = { status = it.message ?: "could not sign in" },
                )
            }
        }
        Gap(12)
        Text(
            "A code comes from /enroll on BasilNet, or from `python3 server/enroll_qr.py " +
                "--immich <address> --email <you>` on any machine — that one needs no server " +
                "running at all. A QR holding nothing but the address works too; the phone then " +
                "asks for the password here.",
            style = MaterialTheme.typography.bodyMedium,
            color = Dim,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

/** The standalone version, for a phone that is already set up and is adding photographs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImmichSignInScreen(onBack: () -> Unit, onScan: () -> Unit) {
    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = { Text("Photographs", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { pad ->
        ImmichSignIn(onDone = onBack, onScan = onScan, modifier = Modifier.padding(pad).fillMaxSize())
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    secret: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        label = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
