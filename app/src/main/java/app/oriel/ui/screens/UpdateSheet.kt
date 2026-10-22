package app.oriel.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.oriel.ui.AppState
import app.oriel.ui.components.ActionPill
import app.oriel.ui.components.ActivityIndicator
import app.oriel.ui.components.LocalSheetClose
import app.oriel.ui.components.OrielSheet
import app.oriel.ui.components.PAGE_GUTTER
import app.oriel.ui.components.SettingsGroup
import app.oriel.ui.components.SheetTitle
import app.oriel.ui.components.TextAction
import app.oriel.update.Release
import app.oriel.update.ReleaseAsset
import app.oriel.update.Releases
import app.oriel.update.UpdateInstaller
import app.oriel.update.UpdateStep
import app.oriel.update.Updates
import app.oriel.util.Format

/** What the update sheet's buttons do. */
class UpdateActions(
    val update: () -> Unit,
    val later: () -> Unit,
    val skip: () -> Unit,
    val cancel: () -> Unit,
    val openSettings: () -> Unit,
)

/** Whether the update sheet is up ([showing]), and whether it has to wait its turn ([held]) behind another first-run question. */
@Stable
class UpdatePromptGate {
    var showing by mutableStateOf(false)
    var held by mutableStateOf(false)
}

/**
 * Runs updates for the app's frame: the daily check once the UI is up, results as notices,
 * carrying on when the person comes back from "Install unknown apps", starting Android's
 * confirmation, and the sheet when there's something to offer.
 */
@Composable
fun UpdateHost(app: AppState, gate: UpdatePromptGate = remember { UpdatePromptGate() }) {
    val context = app.activity
    val updates = Updates.get(context)
    val state by updates.state.collectAsState()
    if (!state.supported) return
    LaunchedEffect(updates) { updates.onAppStart() }
    LaunchedEffect(updates) { updates.notices.collect { app.notice(it) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) updates.onResume() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    state.confirm?.let { intent ->
        LaunchedEffect(intent) {
            // Checked by Updates before it got here; started from the activity, as a receiver may not.
            runCatching { context.startActivity(intent) }
            updates.confirmHandled()
        }
    }
    val release = state.available
    val apk = state.apk
    // Never over an alert or a report already open: it waits its turn, then stays.
    var showing by remember { mutableStateOf(false) }
    LaunchedEffect(state.prompt) {
        if (!state.prompt) { showing = false; return@LaunchedEffect }
        snapshotFlow { app.alert == null && app.report == null && !gate.held }.first { it }
        showing = true
    }
    val offering = showing && state.prompt && release != null && apk != null
    SideEffect { gate.showing = offering }
    if (offering) {
        UpdateSheet(
            release, apk, state.step,
            UpdateActions(
                update = updates::update,
                later = updates::later,
                skip = updates::skip,
                cancel = updates::cancel,
                openSettings = { runCatching { context.startActivity(UpdateInstaller.permissionIntent(context)) } },
            ),
        )
    }
}

/** The offer of [release]: its notes and size, then Update, Later or Skip; then the download's progress and what happened. */
@Composable
fun UpdateSheet(release: Release, apk: ReleaseAsset, step: UpdateStep, actions: UpdateActions) {
    // Swiped away or closed is "Later"; it also stops a download.
    OrielSheet(onDismiss = actions.later, maxHeight = 720.dp) {
        val close = LocalSheetClose.current
        SheetTitle("Software Update")
        Column(Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Oriel ${release.version}", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
            Spacer(Modifier.height(2.dp))
            Text(
                (if (release.prerelease) "Beta · " else "") + Format.bytes(apk.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The group is already headed "What's new"; notes that open with the same heading don't repeat it.
        val notes = Releases.plainNotes(release.notes).let { n ->
            if (n.substringBefore('\n').trim().equals("What's new", ignoreCase = true)) n.substringAfter('\n', "").trimStart() else n
        }
        SettingsGroup(header = "What's new", top = 14.dp) {
            Text(
                notes.ifEmpty { "Fixes and improvements." },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        Spacer(Modifier.height(22.dp))
        Column(Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER), horizontalAlignment = Alignment.CenterHorizontally) {
            when (val s = step) {
                UpdateStep.Offer -> {
                    ActionPill("Update", null, actions.update, Modifier.fillMaxWidth(), filled = true)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextAction("Later", { close {} })
                        TextAction("Skip This Version", { close(actions.skip) })
                    }
                }
                is UpdateStep.Downloading -> {
                    Status("Downloading… ${Format.bytes(s.read)} of ${Format.bytes(s.total)}")
                    Spacer(Modifier.height(10.dp))
                    ProgressBar(s.fraction)
                    Spacer(Modifier.height(14.dp))
                    ActionPill("Cancel", null, actions.cancel, Modifier.fillMaxWidth())
                }
                UpdateStep.Verifying -> Working("Verifying…")
                UpdateStep.Installing -> Working("Waiting for Android to install it…")
                UpdateStep.NeedsPermission -> {
                    Status("To install its own updates, Oriel needs Android's permission to install apps. Turn on Allow from this source, then come back.")
                    Spacer(Modifier.height(14.dp))
                    ActionPill("Open Settings", null, actions.openSettings, Modifier.fillMaxWidth(), filled = true)
                    Spacer(Modifier.height(6.dp))
                    TextAction("Later", { close {} })
                }
                is UpdateStep.Failed -> {
                    Status(s.message, primary = true)
                    Spacer(Modifier.height(14.dp))
                    if (s.message == Updates.UNVERIFIED) {
                        Status("Nothing was installed. You can get Oriel from its GitHub releases page instead.")
                        Spacer(Modifier.height(14.dp))
                        ActionPill("Close", null, { close {} }, Modifier.fillMaxWidth())
                    } else {
                        ActionPill("Try Again", null, actions.update, Modifier.fillMaxWidth(), filled = true)
                        Spacer(Modifier.height(6.dp))
                        TextAction("Later", { close {} })
                    }
                }
            }
        }
    }
}

@Composable
private fun Status(text: String, primary: Boolean = false) {
    Text(
        text,
        style = if (primary) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
        color = if (primary) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun Working(text: String) {
    Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        ActivityIndicator()
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

/** A thin capsule filling with primary, as iOS's progress view. */
@Composable
private fun ProgressBar(fraction: Float) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val fill = MaterialTheme.colorScheme.primary
    val progress by rememberUpdatedState(fraction)
    Box(
        Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(track)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) }
            .drawBehind {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(fill, size = Size(size.width * progress, size.height), cornerRadius = r)
            },
    )
}
