package app.oriel.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.oriel.BuildConfig
import app.oriel.ui.AlertAction
import app.oriel.ui.AlertSpec
import app.oriel.ui.AppState
import kotlinx.coroutines.launch
import app.oriel.ui.ReportRequest
import app.oriel.ui.components.ActionPill
import app.oriel.ui.components.GlassChoiceRow
import app.oriel.ui.components.GlassPane
import app.oriel.ui.components.GlassRow
import app.oriel.ui.components.Glyphs
import app.oriel.ui.components.LocalSheetClose
import app.oriel.ui.components.PAGE_GUTTER
import app.oriel.ui.components.PaneHeader
import app.oriel.ui.components.OrielIcons
import app.oriel.ui.components.OrielSheet
import app.oriel.ui.components.SheetAction
import app.oriel.ui.components.SheetTitle
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
        settings = ProblemReport.settingsSummary(settings, signedIn, graph.telemetry.available, premium = graph.account.active.value.premiumFormats),
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
            PaneHeader("Exactly what's sent", "Share: sends this text as it is.\nGitHub: gets the same text, split into the form's fields.")
            GlassPane(dividers = false) {
                SelectionContainer {
                    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                }
            }
            return@OrielSheet
        }

        SheetTitle("Report a problem", trailing = { SheetAction("Cancel") { close {} } })
        PaneHeader("What kind of problem")
        GlassPane(dividers = false) {
            Column(Modifier.padding(horizontal = 3.dp)) {
                ReportKind.entries.forEach { k -> GlassChoiceRow(k.label, k == kind) { kind = k } }
            }
        }
        PaneHeader("What happened?")
        GlassPane(dividers = false) {
            // What to write is the field's own prompt, gone once there are words in it.
            ReportField(
                description, { description = it },
                when (kind) {
                    ReportKind.BROKEN -> "What you did, what you saw, and what you expected instead."
                    ReportKind.PLAYBACK -> "The message on screen, if any, and what you tried: retry, another quality, signing in."
                    ReportKind.CRASH -> "What you were doing when it closed, if you remember."
                },
                "What happened?", minLines = 4,
            )
        }
        PaneHeader(
            "Video link",
            if (kind == ReportKind.PLAYBACK) "The video that won't play. It's the only thing about what you watch that goes in the report." else "Optional: a video where it happens. It's the only thing about what you watch that goes in the report.",
        )
        GlassPane(dividers = false) {
            ReportField(link, { link = it }, "https://youtu.be/…", "Video link", singleLine = true)
        }
        val held = stored
        PaneHeader(
            if (cleared) "Diagnostics, cleared" else "Diagnostics",
            "Included: app and Android version, device, a few settings, " +
                (held?.let { "${count(it.crashes.size, "crash", "crashes")} and ${count(it.trail.size, "recent error", "recent errors")}" } ?: "recent crashes and errors") +
                ".\nNever included: your account, cookies, email addresses, stream addresses and long tokens.\nSwitched off: only what you wrote and the video link are sent.",
        )
        GlassPane {
            GlassRow("Include diagnostics", Glyphs.LineBug, onChange = { include = it }, checked = include)
            GlassRow("Preview report", Glyphs.LineDoc, chevron = true, onClick = { previewing = true })
            if (held != null && !held.isEmpty) {
                // What's cleared can't be sent afterwards: asked first, as every other clear is.
                GlassRow("Clear diagnostics", Glyphs.LineTrash, destructive = true, onClick = {
                    app.alert = AlertSpec("Clear diagnostics?", "The crashes and errors kept on this phone are deleted, and can't go in a report.", listOf(
                        AlertAction("Cancel"),
                        AlertAction("Clear", destructive = true) {
                            stored = Stored(emptyList(), emptyList())
                            cleared = true
                            // The app's scope: closing the sheet mustn't cut the clear short.
                            app.scope.launch(Dispatchers.IO) { CrashLog.clear(context) }
                        },
                    ))
                })
            }
        }
        PaneHeader(
            if (ready) "Send" else "Describe what happened to send",
            "GitHub: opens in your browser with the form filled in. You need a GitHub account, and nothing is posted until you submit it there.\nShare: sends the same report by email or another app.",
        )
        Column(Modifier.padding(horizontal = PAGE_GUTTER).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionPill("Open a GitHub issue", OrielIcons.OpenExternal, enabled = ready, filled = true, modifier = Modifier.fillMaxWidth(), onClick = {
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
            ActionPill("Share…", OrielIcons.Share, enabled = ready, modifier = Modifier.fillMaxWidth(), onClick = {
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

/** A text field drawn in a pane: its prompt in the secondary colour, named for TalkBack by [label]. */
@Composable
private fun ReportField(value: String, onChange: (String) -> Unit, placeholder: String, label: String, singleLine: Boolean = false, minLines: Int = 1) {
    Box(Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(horizontal = 16.dp, vertical = 14.dp)) {
        if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f), maxLines = if (singleLine) 1 else 4)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = if (singleLine) 1 else 12,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(app.oriel.ui.theme.accent()),
            keyboardOptions = if (singleLine) KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false)
            else KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        )
    }
}
