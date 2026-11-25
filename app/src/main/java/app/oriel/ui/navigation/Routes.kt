package app.oriel.ui.navigation

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable

// Tabs
@Serializable data object HomeRoute
/** [queue]: the shorts to play after [startId], from the feed it was opened from; empty for the general sequence. */
@Serializable data class ShortsRoute(val startId: String? = null, val queue: List<String> = emptyList())

/** The shorts of [feed] after [tappedId], in the feed's order: where the Shorts page carries on from a tap. */
fun shortsAfter(tappedId: String, feed: List<String>, limit: Int = 60): List<String> {
    val at = feed.indexOf(tappedId)
    return if (at < 0) emptyList() else feed.drop(at + 1).filterNot { it == tappedId }.distinct().take(limit)
}
@Serializable data object SubscriptionsRoute
@Serializable data object YouRoute
@Serializable data class SearchRoute(val query: String? = null)

// Pages
@Serializable data class ChannelRoute(val id: String, val params: String? = null)
@Serializable data class PlaylistRoute(val id: String)
@Serializable data class LocalPlaylistRoute(val id: String)
@Serializable data class TopicRoute(val topic: String)
@Serializable data object HistoryRoute
@Serializable data object WatchLaterRoute
@Serializable data object LikedRoute
@Serializable data object ChannelsRoute
@Serializable data object PlaylistsRoute
@Serializable data object SettingsRoute
/** One of Settings' pages, by its [app.oriel.ui.screens.SettingsSection] name. */
@Serializable data class SettingsSectionRoute(val section: String)
/** Google's sign-in; [reauth] is the profile whose sign-in expired, when it's signing in again. */
@Serializable data class LoginRoute(val reauth: String? = null)
@Serializable data object NotificationsRoute

enum class Tab { HOME, SHORTS, SUBSCRIPTIONS, YOU }

/**
 * The tab this back-stack entry is the root of, if it is one. A short opened from a feed
 * is a page pushed on that feed's tab, not the Shorts tab: only the plain Shorts route is.
 */
fun NavBackStackEntry.tabRoot(): Tab? {
    val d = destination
    return when {
        d.hasRoute(HomeRoute::class) -> Tab.HOME
        d.hasRoute(ShortsRoute::class) -> if (toRoute<ShortsRoute>().startId == null) Tab.SHORTS else null
        d.hasRoute(SubscriptionsRoute::class) -> Tab.SUBSCRIPTIONS
        d.hasRoute(YouRoute::class) -> Tab.YOU
        else -> null
    }
}

/**
 * Whether this entry is [route] itself, arguments and all: another channel, playlist or search
 * is a new page on top, while the same one again is no push at all.
 */
fun NavBackStackEntry.isRoute(route: Any): Boolean {
    if (!destination.hasRoute(route::class)) return false
    return when (route) {
        is ShortsRoute -> toRoute<ShortsRoute>() == route
        is SearchRoute -> toRoute<SearchRoute>() == route
        is ChannelRoute -> toRoute<ChannelRoute>() == route
        is PlaylistRoute -> toRoute<PlaylistRoute>() == route
        is LocalPlaylistRoute -> toRoute<LocalPlaylistRoute>() == route
        is TopicRoute -> toRoute<TopicRoute>() == route
        is SettingsSectionRoute -> toRoute<SettingsSectionRoute>() == route
        // The rest are objects: the class is the route.
        else -> true
    }
}
