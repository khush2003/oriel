package app.oriel.ui.screens

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.oriel.core.YouTube
import app.oriel.core.model.AccountInfo
import app.oriel.core.model.Chip
import app.oriel.core.model.Feed
import app.oriel.data.HomeComposer
import app.oriel.ui.LocalAppState
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.haptics.rememberHaptics
import app.oriel.ui.components.ActionPill
import app.oriel.ui.components.PillSize
import app.oriel.ui.components.Avatar
import app.oriel.ui.components.ChipsRow
import app.oriel.ui.components.OrielIcons
import app.oriel.ui.components.MessageState
import app.oriel.ui.components.PAGE_GUTTER
import app.oriel.ui.components.VideoCardSkeleton
import app.oriel.ui.components.feedShelves
import app.oriel.ui.navigation.LoginRoute
import app.oriel.ui.navigation.Tab

/**
 * Home. Signed in, it's YouTube's own recommendations with its topic chips.
 * Signed out YouTube sends an empty page, so Oriel composes one on the
 * device from what you watched and the channels you follow, plus trending
 * topics, and offers the same kind of chips.
 */
@Composable
fun HomeScreen() {
    val app = LocalAppState.current
    val graph = app.graph
    val signedIn by graph.account.signedIn.collectAsState()
    val account by graph.account.account.collectAsState()
    val model: FeedModel = viewModel(key = "home:$signedIn") {
        FeedModel(graph, cacheKey = "home-$signedIn", load = { fresh ->
            if (signedIn) graph.yt.browse(YouTube.HOME, fresh = fresh) else graph.homeComposer.compose(fresh)
        })
    }
    val state by model.state.collectAsState()
    // Another account signing in after this one reuses the signed-in model: its feed goes.
    val owner = accountOwner(account)
    LaunchedEffect(model, owner) {
        if (owner == null || model.owner == owner) return@LaunchedEffect
        val before = model.owner
        model.owner = owner
        if (before != null) { model.selectedChip = null; model.reload(fresh = true) }
    }
    var selected by model::selectedChip
    val baseChips = model.baseChips.ifEmpty { state.feed?.takeIf { selected == null }?.chips.orEmpty() }
    val bannerDismissed by graph.settings.state.collectSlice { it.signInBannerDismissed }
    val haptics = rememberHaptics()
    val feed = state.feed

    fun loaderFor(chip: Chip?): (suspend (Boolean) -> Feed)? {
        if (chip == null) return null
        if (!signedIn) {
            val topic = HomeComposer.topicOf(chip) ?: return null
            return { _ -> graph.yt.topic(topic) }
        }
        val token = chip.token ?: return null
        return { _ -> graph.yt.more(token) }
    }
    val chips = remember(signedIn, baseChips, selected) {
        if (signedIn) {
            baseChips.mapIndexed { i, c -> c.copy(selected = if (selected == null) i == 0 else c.title == selected) }
        } else HomeComposer.chips(selected?.let { s -> HomeComposer.chips().firstOrNull { it.title == s }?.token })
    }

    fun retry() {
        val chip = chips.firstOrNull { it.selected && it.title == selected }
        model.reload(true, loaderFor(chip), key = chip?.title)
    }

    PageScaffold(
        title = "Home",
        tab = Tab.HOME,
        refreshing = state.refreshing,
        onRefresh = ::retry,
        onNearEnd = model::loadMore,
        onShown = model::refreshIfStale,
        titleTrailing = {
            Box(
                Modifier.size(44.dp).clip(CircleShape)
                    .clickable(onClickLabel = "Open You") { haptics.play(Haptic.Tap); app.selectTab(Tab.YOU) }
                    .semantics { contentDescription = account?.name?.let { "Account, $it" } ?: "Account" },
                contentAlignment = Alignment.Center,
            ) { Avatar(account?.avatar, 34.dp, name = account?.name) }
        },
    ) {
        if (chips.isNotEmpty()) item(key = "chips", contentType = "chips") {
            ChipsRow(chips, onSelect = { chip ->
                val first = chips.firstOrNull()
                if (chip.title == first?.title || chip.title == selected) {
                    selected = null
                    model.reload(false)
                } else {
                    model.reload(false, loaderFor(chip) ?: return@ChipsRow, key = chip.title)
                    selected = chip.title
                }
            })
        }
        refreshNotice(state, onRetry = ::retry)
        if (!signedIn && !bannerDismissed) item(key = "signin", contentType = "banner") {
            SignInCard(onSignIn = { app.navigate(LoginRoute) }, onDismiss = { graph.settings.update { it.copy(signInBannerDismissed = true) } })
        }
        when {
            feed == null && state.loading -> items(5, key = { "sk$it" }, contentType = { "skeleton" }) { VideoCardSkeleton(it) }
            // A chip's page that failed is tried again as that chip, not as the whole feed under it.
            feed == null && state.error != null -> item(key = "error", contentType = "message") { MessageState(state.error!!, action = "Try again", onAction = ::retry) }
            feed != null && feed.isEmpty && !state.loading -> item(key = "empty", contentType = "message") {
                MessageState(feed.message ?: "Nothing here yet. Watch something or follow a few channels and Home fills in.", icon = OrielIcons.Home)
            }
            feed != null -> {
                if (state.loading) items(5, key = { "sk$it" }, contentType = { "skeleton" }) { VideoCardSkeleton(it) } else feedShelves(feed, keyPrefix = "home")
            }
        }
        feedFooter(state, onRetry = model::retryMore)
    }
}

@Composable
fun SignInCard(onSignIn: () -> Unit, onDismiss: (() -> Unit)?, text: String = "Sign in to get your recommendations, subscriptions feed, playlists and history from YouTube. Everything else works without an account.") {
    Column(
        Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(OrielIcons.Person, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text("Make it yours", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (onDismiss != null) Icon(OrielIcons.Close, "Dismiss", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onDismiss).padding(12.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        ActionPill("Sign in with Google", OrielIcons.SignIn, onSignIn, filled = true, size = PillSize.Medium)
    }
}

/** Who's signed in, to tell one account's feeds from another's. */
fun accountOwner(account: AccountInfo?): String? = account?.let { it.email ?: it.handle ?: it.name }
