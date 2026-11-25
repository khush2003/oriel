package app.oriel.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import app.oriel.data.Profile
import app.oriel.ui.AlertAction
import app.oriel.ui.AlertSpec
import app.oriel.ui.AppState
import app.oriel.ui.components.ActivityIndicator
import app.oriel.ui.components.ActionPill
import app.oriel.ui.components.Avatar
import app.oriel.ui.components.DestructiveRed
import app.oriel.ui.components.OrielIcons
import app.oriel.ui.components.PillSize
import app.oriel.ui.glass.LocalAppBackdrop
import app.oriel.ui.glass.backdrop.drawPlainBackdrop
import app.oriel.ui.glass.backdrop.effects.blur
import app.oriel.ui.glass.glassSurface
import app.oriel.ui.glass.isGlassSupported
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.haptics.rememberHaptics
import app.oriel.ui.navigation.LoginRoute
import app.oriel.ui.navigation.SettingsRoute
import app.oriel.ui.theme.LocalIsDark
import app.oriel.ui.theme.LocalReduceAnimation
import app.oriel.ui.theme.LocalReduceBlur
import app.oriel.ui.theme.MenuSpring
import kotlinx.coroutines.launch

/**
 * The account card: a floating glass card that grows out of the avatar that opened it, over the
 * page blurred and dimmed, as BitChord's account selector floats. The account in use at the
 * top; a tap on another switches to it there and then; Add, Manage (which turns each account's
 * row into its sign-out) and Settings below. A flick up, a tap outside or Back puts it away.
 */
@Composable
fun AccountSwitcher(app: AppState) {
    val open = app.accounts
    val graph = app.graph
    val profiles by graph.account.profiles.collectAsState()
    val active by graph.account.active.collectAsState()
    val signedIn by graph.account.signedIn.collectAsState()
    val expired by graph.account.sessionExpired.collectAsState()
    val switching by graph.account.switching.collectAsState()
    var managing by remember { mutableStateOf(false) }
    var switchingTo by remember { mutableStateOf<String?>(null) }
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()

    val reduceMotion = LocalReduceAnimation.current
    val reduceBlur = LocalReduceBlur.current || !isGlassSupported()
    val progress = remember { Animatable(0f) }
    val drag = remember { Animatable(0f) }
    LaunchedEffect(open) {
        if (open) {
            managing = false
            drag.snapTo(0f)
            progress.animateTo(1f, if (reduceMotion) tween(140, easing = LinearEasing) else MenuSpring)
        } else {
            // A spring, not a tween: reopened mid-close, it carries on from where it is.
            progress.animateTo(0f, if (reduceMotion) tween(110, easing = FastOutSlowInEasing) else spring(dampingRatio = 1f, stiffness = 600f))
            switchingTo = null
        }
    }
    if (!open && progress.value == 0f) return
    val dismiss = { app.accounts = false }
    BackHandler(enabled = open) { if (managing) managing = false else dismiss() }

    val dark = LocalIsDark.current
    val scrim = when {
        reduceBlur && dark -> Color.Black.copy(alpha = 0.62f)
        reduceBlur -> Color.Black.copy(alpha = 0.36f)
        dark -> Color.Black.copy(alpha = 0.38f)
        else -> Color.Black.copy(alpha = 0.16f)
    }
    val backdrop = LocalAppBackdrop.current
    val density = LocalDensity.current
    val blurPx = with(density) { 24.dp.toPx() } * 0.33f
    val dismissPx = with(density) { 72.dp.toPx() }
    var cardBounds by remember { mutableStateOf<Rect?>(null) }
    val shape = RoundedCornerShape(28.dp)

    Box(
        Modifier
            .fillMaxSize()
            .semantics { paneTitle = "Accounts"; isTraversalGroup = true },
    ) {
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }
                .then(
                    if (!reduceBlur && backdrop != null) {
                        Modifier.drawPlainBackdrop(backdrop = backdrop, shape = { RectangleShape }, effects = { blur(blurPx * progress.value.coerceIn(0.05f, 1f)) })
                    } else Modifier,
                )
                .background(scrim)
                .clickable(remember { MutableInteractionSource() }, indication = null, enabled = open, onClickLabel = "Close accounts", onClick = dismiss),
        )
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp)
                .padding(horizontal = 12.dp)
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .onGloballyPositioned { cardBounds = it.boundsInWindow() }
                .graphicsLayer {
                    val p = progress.value
                    // Out of the avatar that opened it: scaled from there, and drawn in as it grows.
                    val anchor = app.accountsAnchor
                    val card = cardBounds
                    if (anchor != null && card != null && card.width > 0f && !reduceMotion) {
                        transformOrigin = TransformOrigin(
                            ((anchor.center.x - card.left) / card.width).coerceIn(0f, 1f),
                            ((anchor.center.y - card.top) / card.height).coerceIn(0f, 1f),
                        )
                    } else transformOrigin = TransformOrigin(0.5f, 0f)
                    val scale = if (reduceMotion) 1f else lerp(0.2f, 1f, p.coerceAtLeast(0f))
                    scaleX = scale
                    scaleY = scale
                    alpha = (p * 2.5f).coerceIn(0f, 1f)
                    // Follows the finger up freely; down, it gives a little and no more.
                    val d = drag.value
                    translationY = if (d < 0f) d else d / (1f + d / 120f)
                }
                .then(if (reduceMotion) Modifier else Modifier.shadow(24.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f)))
                .glassSurface(shape, panel = true)
                .draggable(
                    rememberDraggableState { delta -> scope.launch { drag.snapTo(drag.value + delta) } },
                    Orientation.Vertical,
                    enabled = open,
                    onDragStopped = { velocity ->
                        if (drag.value < -dismissPx || velocity < -900f) dismiss()
                        else drag.animateTo(0f, MenuSpring, initialVelocity = velocity)
                    },
                )
                .clickable(remember { MutableInteractionSource() }, indication = null, enabled = open) {},
        ) {
            AccountCardContent(
                profiles = profiles,
                activeId = active.id,
                signedIn = signedIn,
                expired = signedIn && expired,
                managing = managing,
                switchingTo = switchingTo.takeIf { switching },
                canAdd = graph.account.canAddAccount,
                onSwitch = { id ->
                    if (switching) return@AccountCardContent
                    switchingTo = id
                    app.scope.launch {
                        val ok = graph.account.switchTo(id)
                        if (!ok) {
                            switchingTo = null
                            app.notice("Couldn't switch accounts. Try again.")
                            return@launch
                        }
                        haptics.play(Haptic.Select)
                        dismiss()
                        app.backToTabRoot()
                    }
                },
                onSignIn = { dismiss(); app.navigate(LoginRoute(reauth = active.id.takeIf { !active.isGuest })) },
                onSignOut = { profile -> confirmSignOut(app, profile, profile.id == active.id) },
                onAdd = { dismiss(); app.navigate(LoginRoute()) },
                onManage = { haptics.play(Haptic.Tap); managing = !managing },
                onSettings = { dismiss(); app.navigate(SettingsRoute) },
            )
        }
    }
}

/**
 * What the card shows, from state alone. [expired]: YouTube signed the profile in use out.
 * [switchingTo]: the profile a switch is under way to, whose row shows it.
 */
@Composable
fun ColumnScope.AccountCardContent(
    profiles: List<Profile>,
    activeId: String,
    signedIn: Boolean,
    expired: Boolean,
    managing: Boolean,
    switchingTo: String?,
    canAdd: Boolean,
    onSwitch: (String) -> Unit,
    onSignIn: () -> Unit,
    onSignOut: (Profile) -> Unit,
    onAdd: () -> Unit,
    onManage: () -> Unit,
    onSettings: () -> Unit,
) {
    val active = profiles.firstOrNull { it.id == activeId } ?: return
    // The Guest last among the others: an account is what's usually switched to.
    val others = profiles.filter { it.id != activeId }.sortedWith(compareBy<Profile> { it.isGuest }.thenBy { it.addedAt })
    Column(Modifier.verticalScroll(rememberScrollState())) {
        ActiveHeader(active, signedIn, expired, managing, onSignIn, onSignOut)
        if (others.isNotEmpty()) {
            CardDivider()
            Text(
                "Switch to",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 2.dp).semantics { heading() },
            )
            others.forEach { profile ->
                OtherRow(
                    profile,
                    managing = managing,
                    switching = switchingTo == profile.id,
                    enabled = switchingTo == null,
                    onClick = { onSwitch(profile.id) },
                    onSignOut = { onSignOut(profile) },
                )
            }
        }
        CardDivider(top = 6.dp)
        CardAction(OrielIcons.Add, "Add account", enabled = canAdd && switchingTo == null, detail = if (canAdd) null else "Up to 5 accounts", onClick = onAdd)
        if (profiles.any { !it.isGuest }) CardAction(OrielIcons.Edit, if (managing) "Done" else "Manage accounts", enabled = switchingTo == null, onClick = onManage)
        CardAction(OrielIcons.Settings, "Settings", enabled = switchingTo == null, onClick = onSettings)
        Spacer(Modifier.height(8.dp))
    }
}

private val CARD_INSET = 20.dp
private val ROW_AVATAR = 40.dp

@Composable
private fun ActiveHeader(profile: Profile, signedIn: Boolean, expired: Boolean, managing: Boolean, onSignIn: () -> Unit, onSignOut: (Profile) -> Unit) {
    val name = profileName(profile)
    val detail = when {
        profile.isGuest -> "Your library on this device only"
        expired -> "Signed out by YouTube"
        !signedIn -> "Signed out"
        else -> profile.info?.handle ?: profile.info?.email
    }
    Column(Modifier.fillMaxWidth().padding(start = CARD_INSET, end = 12.dp, top = 18.dp, bottom = 14.dp).semantics(mergeDescendants = true) { selected = true }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                ProfileDisc(profile, 56.dp)
                // The current profile, marked as iOS marks a chosen row.
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(20.dp)
                        .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(OrielIcons.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(11.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (expired) DestructiveRed else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (managing && !profile.isGuest) SignOutButton(name) { onSignOut(profile) }
        }
        // Signed out here, or by YouTube: one tap back in, to this account.
        if (!profile.isGuest && (!signedIn || expired) && !managing) {
            Spacer(Modifier.height(12.dp))
            ActionPill("Sign in again", OrielIcons.SignIn, onSignIn, filled = true, size = PillSize.Compact, modifier = Modifier.padding(start = 56.dp + 14.dp))
        }
    }
}

@Composable
private fun OtherRow(profile: Profile, managing: Boolean, switching: Boolean, enabled: Boolean, onClick: () -> Unit, onSignOut: () -> Unit) {
    val haptics = rememberHaptics()
    val name = profileName(profile)
    val status = when {
        profile.isGuest -> "On this device only"
        !profile.signedIn -> "Signed out"
        else -> profile.info?.handle ?: profile.info?.email
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .graphicsLayer { alpha = if (enabled || switching) 1f else 0.45f }
            .clickable(enabled = enabled && !managing, role = Role.Button, onClickLabel = "Switch to $name") { haptics.play(Haptic.Tap); onClick() }
            .padding(start = CARD_INSET, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileDisc(profile, ROW_AVATAR)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        when {
            switching -> ActivityIndicator(Modifier.padding(end = 8.dp), size = 20.dp)
            managing && !profile.isGuest -> SignOutButton(name, onSignOut)
        }
    }
}

/** Manage's per-account action, as iOS edit mode puts a red action at the row's end. */
@Composable
private fun SignOutButton(name: String, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = "Sign out of $name") { haptics.play(Haptic.Tap); onClick() }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.clip(CircleShape).background(DestructiveRed.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text("Sign out", style = MaterialTheme.typography.labelLarge, color = DestructiveRed)
        }
    }
}

@Composable
private fun CardAction(icon: ImageVector, label: String, enabled: Boolean = true, detail: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(label, detail).joinToString(", ") }
            .padding(horizontal = CARD_INSET),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // In the avatars' column, so the labels line up with the names above.
        Box(
            Modifier.size(ROW_AVATAR).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun CardDivider(top: Dp = 0.dp) {
    Box(Modifier.padding(top = top).fillMaxWidth().height(0.5.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)))
}

private fun profileName(profile: Profile) = if (profile.isGuest) "Guest" else profile.info?.name?.takeIf { it.isNotBlank() } ?: "YouTube account"

/** A profile's picture, or its initial (the Guest's glyph) on a disc that shows against the glass. */
@Composable
private fun ProfileDisc(profile: Profile, size: Dp) {
    val url = profile.info?.avatar.takeUnless { profile.isGuest }
    if (url != null) {
        Avatar(url, size, name = profile.info?.name)
        return
    }
    val initial = profile.info?.name?.takeIf { !profile.isGuest && it.isNotBlank() }?.take(1)?.uppercase()
    if (initial == null) {
        Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(OrielIcons.Person, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(size * 0.55f))
        }
        return
    }
    val (top, bottom) = DISC_TINTS[Math.floorMod(profile.id.hashCode(), DISC_TINTS.size)]
    Box(Modifier.size(size).clip(CircleShape).background(Brush.verticalGradient(listOf(top, bottom))), contentAlignment = Alignment.Center) {
        Text(
            initial,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = MaterialTheme.typography.titleMedium.fontSize * (size / ROW_AVATAR)),
            color = Color.White,
        )
    }
}

/** iOS contact-style gradients, one per account and the same every time; white text reads on each. */
private val DISC_TINTS = listOf(
    Color(0xFF6E8BFF) to Color(0xFF4458D6),
    Color(0xFFFF8A65) to Color(0xFFE0533A),
    Color(0xFF4FC3A1) to Color(0xFF23926F),
    Color(0xFFB98CFF) to Color(0xFF7D4FD6),
    Color(0xFFFFB547) to Color(0xFFE08A12),
    Color(0xFF5EC4E8) to Color(0xFF2A8FB8),
)

/**
 * Marks an avatar as where the account card opens from: its bounds are kept as it's laid out,
 * for [AppState.openAccounts].
 */
fun Modifier.accountsAnchor(holder: AnchorHolder): Modifier = onGloballyPositioned { holder.bounds = it.boundsInWindow() }

class AnchorHolder {
    var bounds: Rect? = null
}

private fun confirmSignOut(app: AppState, profile: Profile, active: Boolean) {
    val name = profile.info?.name ?: "this account"
    val then = if (active) " You'll be switched to Guest." else ""
    val actions = buildList {
        add(AlertAction("Cancel"))
        if (profile.signedIn) add(AlertAction("Sign out") { app.scope.launch { app.graph.account.signOut(profile.id, keepLibrary = true) } })
        add(AlertAction("Remove from this device", destructive = true) { app.scope.launch { app.graph.account.signOut(profile.id, keepLibrary = false) } })
    }
    app.alert = AlertSpec(
        if (profile.signedIn) "Sign out of $name?" else "Remove $name?",
        (if (profile.signedIn) "Signing out keeps this account's library on this device for when you sign in again. " else "") +
            "Removing deletes its likes, history and playlists from this device. Nothing changes on YouTube.$then",
        actions,
    )
}
