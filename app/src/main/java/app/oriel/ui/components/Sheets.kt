package app.oriel.ui.components

import app.oriel.ui.AlertAction
import app.oriel.util.Thumbnails
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.MutableTransitionState
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.oriel.core.model.Channel
import app.oriel.core.model.Item
import app.oriel.core.model.Playlist
import app.oriel.core.model.Post
import app.oriel.core.model.Video
import app.oriel.ui.AlertSpec
import app.oriel.ui.AppState
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.haptics.rememberHaptics
import app.oriel.ui.theme.LocalIsDark
import app.oriel.ui.theme.fade
import app.oriel.ui.theme.motion
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay

/**
 * Closes the sheet it's read inside: slides it down, then runs [after]. Rows that end the
 * sheet call this rather than the dismiss callback, which would drop it with no exit.
 */
val LocalSheetClose = staticCompositionLocalOf<(after: () -> Unit) -> Unit> { { it() } }

/**
 * BitChord's sheet: the page colour, a 28dp top radius and iOS's grabber, a 36 x 5 capsule.
 * Every sheet in the app is one of these. [scrollable] wraps the content in a scroll; sheets
 * that bring their own list (chapters, comments) pass false.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrielSheet(onDismiss: () -> Unit, scrollable: Boolean = true, maxHeight: Dp = 680.dp, content: @Composable ColumnScope.() -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismiss)
    val close: (() -> Unit) -> Unit = remember(state) {
        { after -> scope.launch { state.hide() }.invokeOnCompletion { dismiss(); after() } }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = 0.36f),
        dragHandle = { Grabber { close {} } },
    ) {
        CompositionLocalProvider(LocalSheetClose provides close) {
            val body = Modifier.fillMaxWidth().heightIn(max = maxHeight)
            Column(
                (if (scrollable) body.verticalScroll(rememberScrollState()) else body).navigationBarsPadding().padding(bottom = 12.dp),
                content = content,
            )
        }
    }
}

/** iOS's grabber. Drawn only; for TalkBack it's the sheet's close, since the drag has no other equivalent. */
@Composable
private fun Grabber(onDismiss: () -> Unit) {
    Box(
        Modifier.padding(top = 7.dp, bottom = 9.dp).size(36.dp, 5.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f))
            .semantics {
                contentDescription = "Close sheet"
                onClick("Close") { onDismiss(); true }
                dismiss { onDismiss(); true }
            },
    )
}

/**
 * A sheet's title row, as iOS's sheet navigation bar: an optional back chevron, the title
 * centred, and an optional trailing action. The sides are measured first and the title gets
 * what's left, so a wide action never runs under it.
 */
@Composable
fun SheetTitle(title: String, onBack: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val haptics = rememberHaptics()
    CenteredTitleRow(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 8.dp, vertical = 2.dp),
        start = onBack?.let { back ->
            {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable { haptics.play(Haptic.Tap); back() },
                    contentAlignment = Alignment.Center,
                ) { Icon(OrielIcons.Back, "Back", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) }
            }
        },
        title = { Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) },
        end = trailing,
    )
}

/** A text button in a sheet's title bar (Cancel, Done, New), with a 44dp hit area. */
@Composable
fun SheetAction(text: String, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Box(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).clickable { haptics.play(Haptic.Tap); onClick() }.padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
    }
}

/** The header of an item's sheet: its picture and title, as iOS context menus preview what they act on. */
@Composable
private fun ItemHeader(item: Item) {
    Row(Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        when (item) {
            is Video -> RemoteImage(Thumbnails.of(item, 240), Modifier.width(96.dp).height(54.dp), RoundedCornerShape(8.dp))
            is Channel -> Avatar(item.avatar, 54.dp, name = item.title)
            is Playlist -> RemoteImage(item.thumbnail, Modifier.width(96.dp).height(54.dp), RoundedCornerShape(8.dp))
            is Post -> Avatar(item.author?.avatar, 54.dp, name = item.author?.name)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = when (item) {
                is Video -> item.channel?.name
                is Channel -> item.subscribersText ?: item.handle
                is Playlist -> listOfNotNull(item.owner?.name, item.countText).joinToString(" · ")
                is Post -> item.author?.name
            }
            sub?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        }
    }
}

/**
 * What can be done with an item, as rows: shared by the long-press menu
 * (lifted over the blurred page) and the ⋮ sheet.
 */
@Composable
fun ColumnScope.ItemActions(app: AppState, item: Item, dismiss: () -> Unit) {
    val library by app.graph.library.state.collectAsState()
    fun run(block: () -> Unit) { dismiss(); block() }
    when (item) {
        is Video -> {
            MenuRow("Play next", OrielIcons.Queue, { run { app.connection.playNext(listOf(item)); app.notice("Playing next") } })
            MenuRow("Add to queue", OrielIcons.Playlist, { run { app.connection.addToQueue(listOf(item)); app.notice("Added to queue") } })
            MenuGroupBand()
            val later = library.watchLater.any { it.id == item.id }
            MenuRow(if (later) "Remove from Watch later" else "Save to Watch later", OrielIcons.WatchLater, { run { app.graph.actions.toggleWatchLater(item) } })
            MenuRow("Save to playlist", OrielIcons.Save, { run { app.saveTarget = item } })
            MenuRow("Listen in the background", OrielIcons.AudioOnly, {
                run {
                    app.openVideo(item, expand = false)
                    app.connection.setAudioOnly(true)
                }
            })
            MenuGroupBand()
            MenuRow("Share", OrielIcons.Share, { run { app.share(item) } })
            item.channel?.id?.let { id -> MenuRow("Go to channel", OrielIcons.Person, { run { app.openChannel(id) } }) }
            if (library.history.any { it.video.id == item.id }) {
                MenuRow("Remove from history", OrielIcons.History, { run { app.graph.library.removeHistory(item.id) } }, destructive = true)
            }
        }
        is Channel -> {
            val subscribed = app.graph.library.subscribedIds.collectAsState().value.contains(item.id)
            MenuRow(if (subscribed) "Unsubscribe" else "Subscribe", if (subscribed) OrielIcons.Bell else OrielIcons.BellOutline, { run { app.toggleSubscribe(item) } }, destructive = subscribed)
            MenuRow("Share", OrielIcons.Share, { run { app.share(item) } })
        }
        is Playlist -> {
            if (item.isMix) {
                MenuRow("Play mix", OrielIcons.Play, { run { app.openItem(item) } })
            } else {
                MenuRow("Open playlist", OrielIcons.Playlist, { run { app.openPlaylist(item.id) } })
                val saved = item.id in app.graph.library.savedPlaylistIds.collectAsState().value
                MenuRow(if (saved) "Remove from library" else "Save to library", OrielIcons.Library, { run { app.graph.actions.toggleSaved(item) } })
            }
            MenuRow("Share", OrielIcons.Share, { run { app.share(item) } })
        }
        is Post -> {
            item.video?.let { v -> MenuRow("Play video", OrielIcons.Play, { run { app.openVideo(v) } }) }
            item.author?.id?.let { id -> MenuRow("Go to channel", OrielIcons.Person, { run { app.openChannel(id) } }) }
            MenuRow("Share", OrielIcons.Share, { run { app.share(item) } })
        }
    }
}

@Composable
fun ItemActionsSheet(app: AppState, item: Item, onDismiss: () -> Unit) {
    OrielSheet(onDismiss) {
        val close = LocalSheetClose.current
        ItemHeader(item)
        Spacer(Modifier.height(10.dp))
        Column(Modifier.padding(horizontal = PAGE_GUTTER).clip(GroupShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
            ItemActions(app, item) { close {} }
        }
    }
}

/**
 * Save to…: Watch later, the playlists kept on the device, and, signed in,
 * the account's own playlists with a tick on those already holding it.
 */
@Composable
fun SaveToPlaylistSheet(app: AppState, video: Video, onDismiss: () -> Unit) {
    val library by app.graph.library.state.collectAsState()
    val signedIn by app.graph.account.signedIn.collectAsState()
    var remote by remember { mutableStateOf<List<Pair<Playlist, Boolean>>?>(null) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(video.id, signedIn) { if (signedIn) remote = app.graph.actions.accountPlaylists(video.id) }

    // The sheet's own close, so finishing a new playlist slides it down rather than cutting it.
    // (A plain holder, not state: it's written while composing.)
    val closeSheet = remember { arrayOfNulls<(() -> Unit) -> Unit>(1) }
    OrielSheet(onDismiss) {
        closeSheet[0] = LocalSheetClose.current
        SheetTitle("Save to…", trailing = { SheetAction("New") { creating = true } })
        SettingsGroup(header = "On this device", top = 8.dp) {
            val later = library.watchLater.any { it.id == video.id }
            ChoiceRow("Watch later", later, multi = true) { app.graph.actions.toggleWatchLater(video) }
            library.localPlaylists.forEach { p ->
                RowDivider(inset = false)
                val has = p.videos.any { it.id == video.id }
                ChoiceRow(p.name, has, detail = "${p.videos.size}", multi = true) {
                    if (has) app.graph.library.removeFromLocalPlaylist(p.id, video.id) else app.graph.actions.addToLocalPlaylist(p, listOf(video))
                }
            }
        }
        if (signedIn) {
            SettingsGroup(header = "Your YouTube playlists", footer = "Changes here are made on your YouTube account.") {
                val list = remote
                when {
                    list == null -> Box(Modifier.fillMaxWidth().padding(18.dp), contentAlignment = Alignment.Center) { ActivityIndicator() }
                    list.isEmpty() -> Text("No playlists yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                    else -> list.forEachIndexed { i, (p, has) ->
                        if (i > 0) RowDivider(inset = false)
                        ChoiceRow(p.title, has, multi = true) {
                            remote = list.map { if (it.first.id == p.id) it.first to !has else it }
                            app.graph.actions.setInAccountPlaylist(p.id, video, !has, p.title)
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        NewPlaylistDialog(signedIn, onDismiss = { creating = false }) { name, onAccount, private ->
            creating = false
            if (onAccount) app.graph.actions.createAccountPlaylist(name, private, video)
            else app.graph.actions.addToLocalPlaylist(app.graph.library.createPlaylist(name), listOf(video))
            closeSheet[0]?.invoke {} ?: onDismiss()
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
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
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
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
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

/** An alert's text field: a veil of the text colour, which shows on the card in both modes (the page colour vanished on light). */
@Composable
private fun alertFieldFill(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (LocalIsDark.current) 0.10f else 0.06f)

@Composable
private fun AlertCard(visible: MutableTransitionState<Boolean>, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // iOS's alert: blooms in from a touch larger with a soft spring, and on the way out
        // shrinks a touch and fades quickly. Kept composed until the exit has played.
        AnimatedVisibility(
            visible,
            enter = fadeIn(motion(spring(stiffness = 900f))) + scaleIn(motion(spring(dampingRatio = 0.78f, stiffness = 520f)), initialScale = 1.14f),
            exit = fadeOut(fade(140)) + scaleOut(fade(160), targetScale = 0.94f),
        ) {
            Column(
                Modifier.widthIn(max = 290.dp).fillMaxWidth(0.76f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                content = content,
            )
        }
    }
}

@Composable
private fun AlertButtons(actions: List<AlertAction>) {
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

/** Brief notices: a glass banner that drops in under the status bar and leaves on its own. */
@Composable
fun NoticeHost(app: AppState, modifier: Modifier = Modifier) {
    val notice by app.notice.collectAsState()
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(notice?.id) {
        val n = notice ?: return@LaunchedEffect
        // Longer when TalkBack or the "time to take action" setting asks for it.
        val base = if (n.action != null) 4_500L else 2_800L
        delay(accessibility?.calculateRecommendedTimeoutMillis(base, containsIcons = false, containsText = true, containsControls = n.action != null) ?: base)
        app.clearNotice(n)
    }
    var last by remember { mutableStateOf<AppState.Notice?>(null) }
    SideEffect { if (notice != null) last = notice }
    val shownNotice = notice ?: last
    AnimatedVisibility(
        notice != null,
        enter = slideInVertically(motion(spring(dampingRatio = 0.8f, stiffness = 420f, visibilityThreshold = IntOffset.VisibilityThreshold))) { -it } + fadeIn(fade(180)),
        exit = slideOutVertically(motion(spring(dampingRatio = 1f, stiffness = 520f, visibilityThreshold = IntOffset.VisibilityThreshold))) { -it } + fadeOut(fade(200)),
        modifier = modifier,
    ) {
        shownNotice?.let { n ->
            NoticeBanner(n.text, Modifier, n.action, n.onAction?.let { act -> { app.clearNotice(n); act() } })
        }
    }
}

