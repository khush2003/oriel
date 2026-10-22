package app.oriel.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.ClosedCaptionDisabled
import androidx.compose.material.icons.automirrored.rounded.Comment
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.WatchLater
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Stream
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Public

/** One place for every glyph, so screens say what they mean and the set can be redrawn in one file. */
object OrielIcons {
    val Home = Glyphs.Home
    val HomeSelected = Glyphs.HomeFilled
    val Shorts = Glyphs.Bolt
    val ShortsSelected = Glyphs.BoltFilled
    val Subscriptions = Glyphs.Subscriptions
    val SubscriptionsSelected = Glyphs.SubscriptionsFilled
    val You = Glyphs.Person
    val YouSelected = Glyphs.PersonFilled
    val Search = Icons.Rounded.Search
    val Play = Icons.Rounded.PlayArrow
    val Pause = Icons.Rounded.Pause
    val Next = Icons.Rounded.SkipNext
    val Previous = Icons.Rounded.SkipPrevious
    val Replay = Icons.Rounded.Replay
    val Close = Glyphs.Xmark
    // iOS's "…", not Android's kebab.
    val More = Icons.Rounded.MoreHoriz
    val Like = Glyphs.Like
    val Liked = Glyphs.Liked
    val Dislike = Glyphs.Dislike
    val Disliked = Glyphs.Disliked
    val Share = Glyphs.Share
    val Save = Glyphs.Save
    val Download = Glyphs.Download
    val WatchLater = Icons.Rounded.WatchLater
    val History = Icons.Rounded.History
    val Library = Icons.Rounded.VideoLibrary
    val Playlist = Icons.AutoMirrored.Rounded.PlaylistPlay
    val Queue = Icons.AutoMirrored.Rounded.QueueMusic
    val Chapters = Icons.AutoMirrored.Rounded.FormatListBulleted
    val Settings = Icons.Rounded.Settings
    val Tune = Icons.Rounded.Tune
    val Fullscreen = Icons.Rounded.Fullscreen
    val FullscreenExit = Icons.Rounded.FullscreenExit
    val Collapse = Icons.Rounded.KeyboardArrowDown
    val Captions = Icons.Rounded.ClosedCaption
    val CaptionsOff = Icons.Rounded.ClosedCaptionDisabled
    val Pip = Icons.Rounded.PictureInPictureAlt
    val FastForward = Icons.Rounded.FastForward
    val Speed = Icons.Rounded.Speed
    val Quality = Icons.Rounded.HighQuality
    val Stream = Icons.Rounded.Stream
    val AudioOnly = Icons.Rounded.Headphones
    val Loop = Icons.Rounded.RepeatOne
    val Repeat = Icons.Rounded.Repeat
    val Shuffle = Icons.Rounded.Shuffle
    val Sleep = Icons.Rounded.Bedtime
    val Comments = Glyphs.Comment
    val Verified = Icons.Rounded.Verified
    val Bell = Icons.Rounded.Notifications
    val BellOutline = Icons.Rounded.NotificationsNone
    val Chevron = Glyphs.Chevron
    // The one close glyph: Close and Xmark are the same drawing.
    val Xmark = Glyphs.Xmark
    val Autoplay = Icons.Rounded.PlayCircleOutline
    val Offline = Icons.Rounded.CloudOff
    val Globe = Icons.Rounded.Public
    val Info = Icons.Rounded.Info
    val Chart = Icons.Rounded.BarChart
    val Videos = Icons.Rounded.VideoLibrary
    val Edit = Icons.Rounded.Edit
    val Reorder = Icons.Rounded.SwapVert
    val DragHandle = Icons.Rounded.DragHandle
    val Back = Icons.AutoMirrored.Rounded.ArrowBackIos
    val Pin = Icons.Rounded.PushPin
    val Check = Icons.Rounded.Check
    val Add = Icons.Rounded.Add
    val Delete = Icons.Rounded.Delete
    val Link = Icons.Rounded.Link
    val OpenExternal = Icons.AutoMirrored.Rounded.OpenInNew
    val SignIn = Icons.AutoMirrored.Rounded.Login
    val SignOut = Icons.AutoMirrored.Rounded.Logout
    val Language = Icons.Rounded.Language
    val Heart = Icons.Rounded.Favorite
    val Mic = Icons.Rounded.Mic
    val Insights = Icons.Rounded.Insights
    val Report = Icons.Rounded.BugReport
    val Sound = Icons.Rounded.MusicNote
    val Fill = Icons.Rounded.NorthWest
    val Person = Icons.Rounded.Person
}
