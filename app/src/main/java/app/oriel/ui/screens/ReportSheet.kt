package app.oriel.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.oriel.BuildConfig
import app.oriel.ui.AppState
import app.oriel.ui.ReportRequest
import app.oriel.ui.components.LocalSheetClose
import app.oriel.ui.components.OrielIcons
import app.oriel.ui.components.OrielSheet
import app.oriel.ui.components.RowDivider
import app.oriel.ui.components.SettingsGroup
import app.oriel.ui.components.SettingsRow
import app.oriel.ui.components.SheetTitle
import app.oriel.ui.components.SwitchRow
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.haptics.rememberHaptics
import app.oriel.util.CrashLog
import app.oriel.util.CrashRecord
import app.oriel.util.IssueLink
import app.oriel.util.ProblemReport
import app.oriel.util.ReportInput
import app.oriel.util.ReportKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class Stored(val crashes: List<CrashRecord>, val trail: List<String>) {
    val isEmpty get() = crashes.isEmpty() && trail.isEmpty()
}

/**
 * "Report a problem": what kind, what happened, the video, and the diagnostics kept on the
 * phone (previewable, word for word), sent as a prefilled GitHub issue or through the share
 * sheet. Nothing is sent from here: both hand the text to another app the person finishes in.
 */
@Composable
fun ReportSheet(app: AppState, request: ReportRequest, onDismiss: () -> Unit) {
    val graph = app.graph
    val context = app.activity
    val settings by graph.settings.state.collectAsState()
    val signedIn by graph.account.signedIn.collectAsState()
    var kind by remember { mutableStateOf(request.kind) }
    var description by remember { mutableStateOf(request.description) }
    var link by remember { mutableStateOf(request.videoLink.orEmpty()) }
    var include by remember { mutableStateOf(true) }
    var previewing by remember { mutableStateOf(false) }
    var stored by remember { mutableStateOf<Stored?>(null) }
    var cleared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { stored = withContext(Dispatchers.IO) { Stored(CrashLog.crashes(context), CrashLog.trail(context)) } }
    val device = remember { CrashLog.device(context) }

    fun input() = ReportInput(
        kind = kind,
        description = description,
        videoLink = link.trim().takeIf { it.isNotEmpty() },
        includeDiagnostics = include,
        header = device,
        settings = ProblemReport.settingsSummary(settings, signedIn, graph.telemetry.available),
        crashes = stored?.crashes.orEmpty(),
        trail = stored?.trail.orEmpty(),
    )
    // A GitHub form needs words; a crash speaks for itself.
    val ready = description.isNotBlank() || kind == ReportKind.CRASH

    OrielSheet(onDismiss, maxHeight = 760.dp) {
        val close = LocalSheetClose.current
        if (previewing) {
            SheetTitle("Preview", onBack = { previewing = false })
            val text = remember(kind, description, link, include, stored, settings, signedIn) { ProblemReport.compose(input()) }
            SettingsGroup(top = 8.dp, footer = "This is exactly what Share sends. A GitHub issue gets the same text, split into the form's fields.") {
                SelectionContainer {
                    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                }
            }
            return@OrielSheet
        }

        SheetTitle("Report a problem", trailing = { SheetTextButton("Cancel") { close {} } })
        SettingsGroup(header = "What kind of problem", top = 8.dp) {
            ReportKind.entries.forEachIndexed { i, k ->
                if (i > 0) RowDivider(inset = false)
                KindRow(k.label, k == kind) { kind = k }
            }
        }
        SettingsGroup(
            header = "What happened?",
            footer = when (kind) {
                ReportKind.BROKEN -> "What you did, what you saw, and what you expected instead."
                ReportKind.PLAYBACK -> "The message on screen, if any, and what you tried: retry, another quality, signing in."
                ReportKind.CRASH -> "What you were doing when it closed, if you remember."
            },
        ) {
            ReportField(description, { description = it }, "Describe the problem", "What happened?", minLines = 4)
        }
        SettingsGroup(
            header = "Video link",
            footer = if (kind == ReportKind.PLAYBACK) "The video that won't play. It's the only thing about what you watch that goes in the report." else "Optional: a video where it happens.",
        ) {
            ReportField(link, { link = it }, "https://youtu.be/…", "Video link", singleLine = true)
        }
        val held = stored
        SettingsGroup(
            header = "Diagnostics",
            footer = when {
                cleared -> "Diagnostics cleared."
                !include -> "Only what you wrote and the video link are sent."
                else -> "App and Android version, device, a few settings (never your account), " +
                    (held?.let { "${count(it.crashes.size, "crash", "crashes")} and ${count(it.trail.size, "recent error", "recent errors")}" } ?: "recent crashes and errors") +
                    ". Cookies, email addresses, stream addresses and long tokens are taken out."
            },
        ) {
            SwitchRow("Include diagnostics", include, { include = it }, OrielIcons.Info)
            RowDivider()
            SettingsRow("Preview report", OrielIcons.Report, chevron = true, onClick = { previewing = true })
            if (held != null && !held.isEmpty) {
                RowDivider()
                SettingsRow("Clear diagnostics", OrielIcons.Delete, destructive = true, onClick = {
                    CrashLog.clear(context)
                    stored = Stored(emptyList(), emptyList())
                    cleared = true
                })
            }
        }
        SettingsGroup(
            header = "Send",
            footer = if (!ready) "Describe what happened to send the report." else "GitHub opens in your browser with the form filled in. You'll need a GitHub account, and nothing is posted until you submit it there. Share sends the same report by email or another app.",
        ) {
            SettingsRow("Open a GitHub issue", OrielIcons.OpenExternal, enabled = ready, onClick = {
                val issue = IssueLink.build(
                    kind = kind,
                    description = description,
                    videoLink = link,
                    version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    device = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}",
                    diagnostics = if (include) ProblemReport.diagnostics(input()) else null,
                    crashSignature = if (kind == ReportKind.CRASH) stored?.crashes?.firstOrNull()?.signature else null,
                )
                if (issue.truncated) {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Oriel problem report", ProblemReport.compose(input())))
                }
                close {
                    if (issue.truncated) app.notice("The report was too long for a link, so it's copied. Paste it into the issue where it asks.")
                    app.openExternally(issue.url)
                }
            })
            RowDivider()
            SettingsRow("Share…", OrielIcons.Share, subtitle = "Email or another app", enabled = ready, onClick = {
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Oriel problem report: ${kind.label}")
                    .putExtra(Intent.EXTRA_TEXT, ProblemReport.compose(input()))
                close { runCatching { context.startActivity(Intent.createChooser(send, "Share problem report")) } }
            })
        }
    }
}

private fun count(n: Int, one: String, many: String) = when (n) {
    0 -> "no $many"
    1 -> "1 $one"
    else -> "$n $many"
}

/** One kind of problem: a radio row, announced as selected or not. */
@Composable
private fun KindRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected, role = Role.RadioButton) { if (!selected) haptics.play(Haptic.Select); onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        if (selected) Icon(OrielIcons.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
    }
}

/** A text field drawn as a grouped row: placeholder in the secondary colour, named for TalkBack by [label]. */
@Composable
private fun ReportField(value: String, onChange: (String) -> Unit, placeholder: String, label: String, singleLine: Boolean = false, minLines: Int = 1) {
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp)) {
        if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = if (singleLine) 1 else 12,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = if (singleLine) KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false)
            else KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        )
    }
}

/** A text button in a sheet's title bar, with a 44dp hit area. */
@Composable
private fun SheetTextButton(text: String, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Box(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).clickable { haptics.play(Haptic.Tap); onClick() }.padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}
