package com.gios.lightsync.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.graphics.Color
import com.gios.light.common.LightCommon
import com.gios.light.common.hw.WheelScroll
import com.gios.lightsync.Prefs
import com.gios.lightsync.sync.Fleet
import com.gios.lightsync.sync.FleetApp
import com.gios.lightsync.ui.theme.Dim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * The whole family, and which parts of it have fallen behind.
 *
 * The Apps tab answers "is my data safe"; this one answers "why is this app behaving unlike the
 * others", which until now meant opening twenty repositories. Nearly every cross-app bug has
 * turned out to be one app carrying an older copy of the shared code, so the library version is
 * the column that earns its place — the rest is context for reading it.
 *
 * Nothing here is fetched from the network or from GitHub. Every value comes from the app itself
 * over the same provider LightSync already uses for backups, so it describes the phone in your
 * hand rather than the state of a branch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FleetScreen() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }

    var apps by remember { mutableStateOf<List<FleetApp>?>(null) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { Fleet.survey(context) { prefs.lastRun(it) } }
    }

    val listState = rememberLazyListState()
    WheelScroll(listState)

    val found = apps
    val newest = found?.let { Fleet.newestCommon(it) } ?: LightCommon.VERSION

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = { Text("Fleet", style = MaterialTheme.typography.titleMedium) },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        item {
            MenuRow(
                label = "Fleet",
                detail = found?.size?.toString() ?: "…",
                sub = when {
                    found == null -> "asking every app"
                    found.isEmpty() -> "nothing has offered itself yet"
                    else -> summary(found, newest)
                },
            )
            Rule()
        }

        items(found.orEmpty(), key = { it.pkg }) { app ->
            val behind = app.commonVersion != null &&
                Fleet.compareVersions(app.commonVersion, newest) < 0
            MenuRow(
                label = app.label,
                // The app's own version on the right, where the Apps tab puts the backup age,
                // so the two lists line up when you flick between them.
                detail = app.appVersion ?: "—",
                sub = detailLine(app, behind),
                dim = app.unreachable,
            )
            Rule()
        }

        item {
            Text(
                if (found.isNullOrEmpty()) {
                    "An app joins the fleet by shipping a lightsync.backup provider. Until it " +
                        "does, nothing here knows it exists."
                } else {
                    "Versions come from the apps themselves, not from GitHub, so this is the " +
                        "phone in your hand. This copy of LightSync is on ${LightCommon.VERSION}."
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

/**
 * One line, three facts, in the order you would ask for them.
 *
 * Library version first because it is the reason to open this screen. The stores say what the
 * backup would actually contain, which is the only place the answer is visible at all. The age
 * repeats the Apps tab on purpose: the interesting row is one that is both behind *and* stale,
 * and noticing that should not need two screens.
 */
private fun detailLine(app: FleetApp, behind: Boolean): String {
    if (app.unreachable) {
        return "installed, but it did not answer — older than the shared provider, or stopped"
    }
    val parts = mutableListOf<String>()
    parts += when {
        app.commonVersion == null -> "common —"
        behind -> "common ${app.commonVersion} (behind)"
        else -> "common ${app.commonVersion}"
    }
    if (app.stores.isNotEmpty()) parts += app.stores.joinToString(" · ")
    if (app.sizeHint > 0) parts += size(app.sizeHint)
    parts += age(app.lastRun)
    return parts.joinToString(" · ")
}

private fun summary(apps: List<FleetApp>, newest: String): String {
    val behind = apps.count {
        it.commonVersion == null || Fleet.compareVersions(it.commonVersion, newest) < 0
    }
    val never = apps.count { it.lastRun == 0L }
    val bits = mutableListOf("newest common $newest")
    // Only mention a count when it is not zero. A row of well-behaved zeroes reads as a problem
    // at a glance on a panel this small, and the absence of a warning is the good state.
    if (behind > 0) bits += "$behind behind"
    if (never > 0) bits += "$never never backed up"
    if (behind == 0 && never == 0) bits += "all current"
    return bits.joinToString(" · ")
}

private fun size(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}

private fun age(at: Long): String {
    if (at == 0L) return "never"
    val ms = System.currentTimeMillis() - at
    val days = TimeUnit.MILLISECONDS.toDays(ms)
    val hours = TimeUnit.MILLISECONDS.toHours(ms)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms)
    return when {
        days > 0 -> "${days}d ago"
        hours > 0 -> "${hours}h ago"
        minutes > 0 -> "${minutes}m ago"
        else -> "just now"
    }
}
