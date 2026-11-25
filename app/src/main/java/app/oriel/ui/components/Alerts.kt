package app.oriel.ui.components

import app.oriel.ui.AlertAction
import android.os.Build
import android.view.WindowManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import app.oriel.ui.theme.LocalReduceBlur
import kotlin.math.roundToInt
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.util.lerp
import app.oriel.ui.theme.LocalLiquidMotion
import app.oriel.ui.theme.Sp
import app.oriel.ui.theme.blobRectPath
import app.oriel.ui.theme.smoothstep
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.oriel.ui.AlertSpec
import app.oriel.ui.AppState
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.haptics.rememberHaptics
import app.oriel.ui.theme.LocalIsDark
import app.oriel.ui.theme.fade
import app.oriel.ui.theme.motion
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.delay

private val ALERT_CORNER = 24.dp

/** A sheet's material with the page blurred behind it, and the solid one for when it can't be. */
@Composable
private fun sheetFill(blurred: Boolean): Color = when {
    !blurred -> if (LocalIsDark.current) Color(0xFF232127) else Color(0xFFF7F7FA)
    LocalIsDark.current -> Color(0xFF201E24).copy(alpha = 0.78f)
    else -> Color(0xFFF7F7FA).copy(alpha = 0.84f)
}

/**
 * Blurs what's behind the sheet's window as the sheet comes in ([progress], 0 to 1), as the
 * account card blurs the page under it. Reports through [onBlurred] once the window took it:
 * only then is the sheet drawn see-through.
 */
@Composable
private fun SheetBlurBehind(progress: () -> Float, onBlurred: () -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || LocalReduceBlur.current) return
    val view = LocalView.current
    val radius = with(LocalDensity.current) { 26.dp.toPx() }
    val blurred by rememberUpdatedState(onBlurred)
    LaunchedEffect(view) {
        val window = (view as? DialogWindowProvider)?.window ?: (view.parent as? DialogWindowProvider)?.window ?: return@LaunchedEffect
        // Off in battery saver, and on devices that can't blur across windows.
        if (view.context.getSystemService(WindowManager::class.java)?.isCrossWindowBlurEnabled != true) return@LaunchedEffect
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        blurred()
        // In a few steps: each is a window relayout, and the eye can't tell them from a ramp.
        snapshotFlow { (progress().coerceIn(0f, 1f) * 6f).roundToInt() }.collect { step ->
            window.attributes = window.attributes.also { it.blurBehindRadius = (radius * step / 6f).roundToInt() }
        }
    }
}

/** An iOS alert with one text field: a title, the field pre-filled with [initial], Cancel and [confirm]. */
@Composable
fun TextPromptDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(initial, androidx.compose.ui.text.TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { focus.requestFocus() } }
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    val done = { if (text.text.isNotBlank()) onConfirm(text.text.trim()) }
    AlertCard(visible, onDismiss) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 18.dp, start = 16.dp, end = 16.dp))
        Spacer(Modifier.height(12.dp))
        BasicTextField(
            text, { text = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(app.oriel.ui.theme.accent()),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { done() }),
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(alertFieldFill()).padding(horizontal = 10.dp, vertical = 9.dp).focusRequester(focus),
        )
        Spacer(Modifier.height(16.dp))
        AlertButtons(listOf(AlertAction("Cancel") { onDismiss() }, AlertAction(confirm, emphasized = true) { done() }))
    }
}

/** iOS-style text prompt for a new playlist's name, with where it should live. */
@Composable
fun NewPlaylistDialog(signedIn: Boolean, onDismiss: () -> Unit, onCreate: (name: String, onAccount: Boolean, private: Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var onAccount by remember { mutableStateOf(signedIn) }
    var private by remember { mutableStateOf(true) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { focus.requestFocus() } }
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    AlertCard(visible, onDismiss) {
        Text("New playlist", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 18.dp, start = 16.dp, end = 16.dp))
        Spacer(Modifier.height(12.dp))
        BasicTextField(
            name, { name = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(app.oriel.ui.theme.accent()),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onCreate(name.trim(), onAccount, private) }),
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(alertFieldFill()).padding(horizontal = 10.dp, vertical = 9.dp).focusRequester(focus),
            decorationBox = { inner ->
                Box { if (name.isEmpty()) Text("Title", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant); inner() }
            },
        )
        if (signedIn) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.padding(horizontal = 16.dp)) {
                SegmentedControl(listOf(true, false), onAccount, { if (it) "YouTube" else "This device" }, { onAccount = it }, Modifier.weight(1f))
            }
            if (onAccount) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.padding(horizontal = 16.dp)) {
                    SegmentedControl(listOf(true, false), private, { if (it) "Private" else "Public" }, { private = it }, Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        AlertButtons(
            listOf(
                AlertAction("Cancel") { onDismiss() },
                AlertAction("Create", emphasized = true) { if (name.isNotBlank()) onCreate(name.trim(), onAccount, private) },
            ),
        )
    }
}

// ---- iOS alerts --------------------------------------------------------------------------

/** An alert's text field: a veil of the text colour, which shows on the card in both modes. */
@Composable
private fun alertFieldFill(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (LocalIsDark.current) 0.10f else 0.06f)

@Composable
internal fun AlertCard(visible: MutableTransitionState<Boolean>, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val liquid = LocalLiquidMotion.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // iOS's alert: blooms in from a touch larger with a soft spring, and on the way out
        // shrinks a touch and fades quickly. Kept composed until the exit has played. Liquid,
        // it blooms out of a drop at the centre instead ([alertBloom]).
        AnimatedVisibility(
            visible,
            enter = if (liquid) fadeIn(Sp.Fade.unit) else fadeIn(motion(spring(stiffness = 900f))) + scaleIn(motion(spring(dampingRatio = 0.78f, stiffness = 520f)), initialScale = 1.14f),
            exit = if (liquid) fadeOut(Sp.FadeOut.unit) else fadeOut(fade(140)) + scaleOut(fade(160), targetScale = 0.94f),
        ) {
            // The sheets' material: glass over the blurred page, where the window can blur.
            var blurred by remember { mutableStateOf(false) }
            SheetBlurBehind(progress = { 1f }, onBlurred = { blurred = true })
            val fill = sheetFill(blurred)
            val shape = RoundedCornerShape(ALERT_CORNER)
            CompositionLocalProvider(LocalOnGlass provides true) {
                Column(
                    Modifier.widthIn(max = 290.dp).fillMaxWidth(0.76f)
                        .then(if (liquid) Modifier.alertBloom(this, fill) else Modifier.clip(shape).background(fill).gloss(shape)),
                    content = content,
                )
            }
        }
    }
}

/** The liquid alert's way in: a drop at the centre that swells into the card; out, it draws back in. Draw phase only. */
@Composable
private fun Modifier.alertBloom(scope: androidx.compose.animation.AnimatedVisibilityScope, fill: Color): Modifier {
    val shown = androidx.compose.animation.EnterExitState.Visible
    val pw = scope.transition.animateFloat({ if (targetState == shown) Sp.Bouncy.unit else Sp.MenuClose.unit }, label = "bloomW") { if (it == shown) 1f else 0f }
    val ph = scope.transition.animateFloat({ if (targetState == shown) Sp.SheetW.unit else Sp.MenuClose.unit }, label = "bloomH") { if (it == shown) 1f else 0f }
    return this
        .graphicsLayer {
            val w = pw.value
            val h = ph.value
            val p = minOf(w, h).coerceIn(0f, 1f)
            val s = lerp(0.82f, 1f, minOf(w, h))
            scaleX = s
            scaleY = s
            val drop = minOf(size.width, size.height) * 0.34f
            val cx = size.width / 2f
            val cy = size.height / 2f
            val hw = lerp(drop / 2f, size.width / 2f, w.coerceAtLeast(0f))
            val hh = lerp(drop / 2f, size.height / 2f, h.coerceAtLeast(0f))
            val radius = lerp(drop / 2f, ALERT_CORNER.toPx(), smoothstep(0f, 1f, p))
            val swell = kotlin.math.sin(p * kotlin.math.PI.toFloat())
            val bs = swell * 10.dp.toPx()
            val bt = swell * 8.dp.toPx()
            shape = app.oriel.ui.theme.PathShape { out, _ -> blobRectPath(out, cx - hw, cy - hh, cx + hw, cy + hh, radius, bs, bt) }
            clip = true
        }
        .background(fill)
        // The text, once the drop has mostly opened.
        .graphicsLayer { alpha = smoothstep(0.55f, 0.9f, minOf(pw.value, ph.value)) }
}

@Composable
internal fun AlertButtons(actions: List<AlertAction>) {
    val divider = MaterialTheme.colorScheme.outlineVariant
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(divider))
    if (actions.size == 2) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            actions.forEachIndexed { i, a ->
                if (i > 0) Box(Modifier.width(0.5.dp).fillMaxHeight().background(divider))
                AlertButton(a, Modifier.weight(1f).fillMaxHeight())
            }
        }
    } else {
        actions.forEachIndexed { i, a ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(0.5.dp).background(divider))
            AlertButton(a, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun AlertButton(action: AlertAction, modifier: Modifier) {
    val haptics = rememberHaptics()
    Box(
        modifier.heightIn(min = 46.dp).clickable(role = Role.Button) { haptics.play(Haptic.Tap); action.onClick() }.padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            action.label,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (action.emphasized) FontWeight.W600 else FontWeight.W400),
            color = if (action.destructive) DestructiveRed else MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
    }
}

/** Shows [AppState.alert] as an iOS alert: centred card, title, message, hairline-separated buttons. */
@Composable
fun AlertHost(app: AppState) {
    val current = app.alert
    var shown by remember { mutableStateOf<AlertSpec?>(null) }
    val visible = remember { MutableTransitionState(false) }
    if (current != null && current !== shown) { shown = current; visible.targetState = true }
    if (current == null) visible.targetState = false
    val spec = shown ?: return
    // Gone once the exit has finished; until then the last alert stays up, leaving.
    if (current == null && visible.isIdle && !visible.currentState) {
        SideEffect { shown = null }
        return
    }
    AlertCard(visible, { app.alert = null }) {
        Text(spec.title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 18.dp, start = 16.dp, end = 16.dp))
        spec.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, start = 16.dp, end = 16.dp)) }
        Spacer(Modifier.height(16.dp))
        AlertButtons(spec.actions.map { a -> a.copy(onClick = { app.alert = null; a.onClick() }) })
    }
}
