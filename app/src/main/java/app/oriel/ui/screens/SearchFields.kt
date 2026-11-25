package app.oriel.ui.screens

import app.oriel.ui.glass.glassContentColor
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.BasicTextField
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.haptics.rememberHaptics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.oriel.ui.components.OrielIcons
import app.oriel.ui.components.PAGE_GUTTER

/** A suggestion with what you typed in the regular weight and YouTube's completion in bold, as YouTube shows them. */
internal fun completion(typed: String, suggestion: String): AnnotatedString = buildAnnotatedString {
    if (typed.isNotEmpty() && suggestion.startsWith(typed, ignoreCase = true)) {
        append(suggestion.substring(0, typed.length))
        withStyle(SpanStyle(fontWeight = FontWeight.W700)) { append(suggestion.substring(typed.length)) }
    } else {
        withStyle(SpanStyle(fontWeight = FontWeight.W700)) { append(suggestion) }
    }
}

@Composable
internal fun SuggestionRow(text: AnnotatedString, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, onFill: (() -> Unit)?, onRemove: (() -> Unit)? = null) {
    val haptics = rememberHaptics()
    // The row's end buttons keep their 18-20dp glyphs in 44dp targets; the row stays 52dp tall.
    Row(
        Modifier.fillMaxWidth().clickable { haptics.play(Haptic.Tap); onClick() }.heightIn(min = 52.dp)
            .padding(start = PAGE_GUTTER, end = PAGE_GUTTER - 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (onRemove != null) Icon(OrielIcons.Close, "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(50)).clickable { haptics.play(Haptic.Tap); onRemove() }.padding(13.dp))
        if (onFill != null) Icon(OrielIcons.Fill, "Use", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(50)).clickable { haptics.play(Haptic.Tap); onFill() }.padding(12.dp))
    }
}

/**
 * The field the tab bar's Search droplet becomes (liquid motion with glass): bare text on the
 * droplet's glass, then Clear once there's text, or [idleTrailing] (the microphone) while empty.
 */
@Composable
internal fun BottomSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    modifier: Modifier,
    focusRequester: FocusRequester,
    onFocus: (Boolean) -> Unit,
    idleTrailing: (@Composable () -> Unit)?,
) {
    val haptics = rememberHaptics()
    val ink = glassContentColor()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text("Search YouTube", style = MaterialTheme.typography.bodyLarge, color = ink.copy(alpha = 0.55f), maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = ink),
                cursorBrush = SolidColor(app.oriel.ui.theme.accent()),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(value) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).onFocusChanged { onFocus(it.isFocused) },
            )
        }
        if (value.isNotEmpty()) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = "Clear") { haptics.play(Haptic.Tap); onValueChange("") }, contentAlignment = Alignment.Center) {
                Icon(OrielIcons.Close, "Clear", tint = ink.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
            }
        } else idleTrailing?.invoke()
    }
}
