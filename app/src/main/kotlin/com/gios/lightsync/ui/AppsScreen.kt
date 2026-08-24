package com.gios.lightsync.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import com.gios.light.common.hw.WheelScroll
import com.gios.lightsync.Prefs
import com.gios.lightsync.sync.Backupable
import com.gios.lightsync.sync.Discovery
import com.gios.lightsync.sync.Photos
import com.gios.lightsync.sync.Roll
import com.gios.lightsync.sync.Vault
import com.gios.lightsync.ui.theme.Dim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Every app that offered itself, when it last went up, and two verbs.
 *
 * Restore asks twice, because it is the one button here that destroys something: it overwrites
 * whatever is on the phone now with whatever BasilNet has, and the app it belongs to will most
 * likely die mid-way by design.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(onSetup: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()

    var apps by remember { mutableStateOf(emptyList<Backupable>()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableStateOf(0) }
    var granted by remember { mutableStateOf(Roll.granted(context)) }
    var waiting by remember { mutableStateOf(-1) }

    // Asked for here rather than at launch, and only when the roll is actually about to be
    // read. A permission dialog in front of a list of app names, on first open, explains
    // nothing about why it is being asked.
    val askForRoll = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // Re-queried rather than read out of the result map: the map omits anything that was
        // already granted, so folding it in can only ever keep a false false.
        granted = Roll.granted(context)
    }

    LaunchedEffectApps(revision) { apps = withContext(Dispatchers.IO) { Discovery.find(context) } }

    LaunchedEffectApps(revision + if (granted) 1 else 0) {
        waiting = if (granted && prefs.photosReady) {
            withContext(Dispatchers.IO) { Roll.pending(context, prefs.photoMark) }
        } else {
            -1
        }
    }

    val listState = rememberLazyListState()
    WheelScroll(listState)

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = { Text("Sync", style = MaterialTheme.typography.titleMedium) },
                actions = {
                    TextButton(onClick = onSetup) {
                        Text("SETUP", style = MaterialTheme.typography.labelSmall, color = Color.White)
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                if (prefs.wantsBlobs) item {
                    MenuRow(
                        label = if (busy) "Backing up…" else "Back up everything",
                        detail = "${apps.size}",
                        sub = status ?: prefs.lastError() ?: whenReady(prefs),
                        dim = !prefs.ready,
                        onClick = {
                            if (!prefs.ready) {
                                onSetup()
                            } else {
                                busy = true
                                status = null
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching { Vault(context).backupAll() }
                                    }
                                    busy = false
                                    status = result.fold(
                                        onSuccess = { "backed up $it" },
                                        onFailure = { it.message ?: "failed" },
                                    )
                                    revision++
                                }
                            }
                        },
                    )
                    Rule()
                }

                if (prefs.wantsPhotos) item {
                    // The roll is the one row here that does not go into a blob. It is on this
                    // screen and not behind a third tab because it is the same verb — send what
                    // is new to BasilNet — and because a gigabyte of photographs waiting on the
                    // phone is worth seeing next to everything else that is up to date.
                    MenuRow(
                        label = if (busy) "Uploading…" else "Back up the roll",
                        detail = when {
                            !prefs.photosReady -> "OFF"
                            !granted -> "ALLOW"
                            waiting < 0 -> "—"
                            else -> "$waiting"
                        },
                        dim = !prefs.photosReady,
                        sub = when {
                            !prefs.photosReady -> "no Immich yet — tap to set one up"
                            !granted -> "BrightSync needs to read DCIM to upload photographs"
                            waiting == 0 -> "everything is on Immich" + photoTotal(prefs)
                            else -> "to Immich, in the clear" + photoTotal(prefs)
                        },
                        onClick = {
                            when {
                                !prefs.photosReady -> onSetup()
                                !granted -> askForRoll.launch(Roll.PERMISSIONS)
                                else -> {
                                    busy = true
                                    status = null
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching { Photos(context).run() }
                                        }
                                        busy = false
                                        status = result.fold(
                                            onSuccess = { run ->
                                                "sent ${run.uploaded}, ${run.remaining} to go"
                                            },
                                            onFailure = { it.message ?: "failed" },
                                        )
                                        revision++
                                    }
                                }
                            }
                        },
                    )
                    Rule()
                }

                items(if (prefs.wantsBlobs) apps else emptyList(), key = { it.pkg }) { app ->
                    val confirm = confirming == app.pkg
                    MenuRow(
                        label = app.label,
                        detail = if (confirm) "SURE?" else age(prefs.lastRun(app.pkg)),
                        sub = if (confirm) {
                            "tap again to overwrite this app's data with the copy on BasilNet"
                        } else {
                            app.pkg
                        },
                        onClick = {
                            if (!prefs.ready) {
                                onSetup()
                                return@MenuRow
                            }
                            if (!confirm) {
                                confirming = app.pkg
                                return@MenuRow
                            }
                            confirming = null
                            busy = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { Vault(context).restore(app) }
                                }
                                busy = false
                                status = result.fold(
                                    onSuccess = { "restored ${app.label}" },
                                    onFailure = { "${app.label}: ${it.message}" },
                                )
                            }
                        },
                    )
                    Rule()
                }

                item {
                    Text(
                        if (apps.isEmpty()) {
                            "No apps have offered anything to back up yet. An app joins by " +
                                "shipping a lightsync.backup provider — see the module README."
                        } else {
                            "Tapping an app twice restores it. Backups run daily on wifi, and " +
                                "BasilNet only answers at home, so a day away simply waits."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Dim,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

/** Named so the import list stays honest about what this is: a LaunchedEffect keyed on a counter. */
@Composable
private fun LaunchedEffectApps(key: Int, block: suspend () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(key) { block() }
}

/** " · 412 photographs so far", or nothing on a phone that has never sent one. */
private fun photoTotal(prefs: Prefs): String =
    prefs.photoCount().let { if (it == 0) "" else " · $it sent so far" }

private fun whenReady(prefs: Prefs): String =
    if (prefs.ready) "daily, on wifi" else "not set up — tap SETUP"

private fun age(at: Long): String {
    if (at == 0L) return "NEVER"
    val ms = System.currentTimeMillis() - at
    val days = TimeUnit.MILLISECONDS.toDays(ms)
    val hours = TimeUnit.MILLISECONDS.toHours(ms)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms)
    return when {
        days > 0 -> "${days}d"
        hours > 0 -> "${hours}h"
        minutes > 0 -> "${minutes}m"
        else -> "NOW"
    }
}
