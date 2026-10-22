package app.oriel.ui.navigation

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable

// Tabs
@Serializable data object HomeRoute
@Serializable data class ShortsRoute(val startId: String? = null)
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
@Serializable data object LoginRoute
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
        // The rest are objects: the class is the route.
        else -> true
    }
}
