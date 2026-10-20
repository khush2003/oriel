package app.oriel.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.ClosedCaptionDisabled
import androidx.compose.material.icons.automirrored.rounded.Comment
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
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
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.WatchLater
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Stream
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Public

/** One place for every glyph, so screens say what they mean and the set can be redrawn in one file. */
object OrielIcons {
    val Home = Icons.Outlined.Home
    val HomeSelected = Icons.Rounded.Home
    val Shorts = Icons.Outlined.Bolt
    val ShortsSelected = Icons.Rounded.Bolt
    val Subscriptions = Icons.Outlined.Subscriptions
    val SubscriptionsSelected = Icons.Rounded.Subscriptions
    val You = Icons.Outlined.AccountCircle
    val YouSelected = Icons.Rounded.AccountCircle
    val Search = Icons.Rounded.Search
    val Play = Icons.Rounded.PlayArrow
    val Pause = Icons.Rounded.Pause
    val Next = Icons.Rounded.SkipNext
    val Previous = Icons.Rounded.SkipPrevious
    val Replay = Icons.Rounded.Replay
    val Close = Icons.Rounded.Close
    // iOS's "…", not Android's kebab.
    val More = Icons.Rounded.MoreHoriz
    val MoreHoriz = Icons.Rounded.MoreHoriz
    val Like = Glyphs.Like
    val Liked = Glyphs.Liked
    val Dislike = Glyphs.Dislike
    val Disliked = Glyphs.Disliked
    val Share = Glyphs.Share
    val Save = Glyphs.Save
    val Saved = Glyphs.Saved
    val AddToPlaylist = Icons.AutoMirrored.Rounded.PlaylistAdd
    val Download = Glyphs.Download
    val WatchLater = Icons.Rounded.WatchLater
    val Schedule = Icons.Rounded.Schedule
    val History = Icons.Rounded.History
    val Library = Icons.Rounded.VideoLibrary
    val Playlist = Icons.AutoMirrored.Rounded.PlaylistPlay
    val Queue = Icons.AutoMirrored.Rounded.QueueMusic
    val Chapters = Icons.AutoMirrored.Rounded.FormatListBulleted
    val Settings = Icons.Rounded.Settings
    val Tune = Icons.Rounded.Tune
    val Sort = Icons.AutoMirrored.Rounded.Sort
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
    val Xmark = Glyphs.Xmark
    val Expand = Glyphs.Expand
    val Autoplay = Icons.Rounded.PlayCircleOutline
    val Filter = Icons.Rounded.Tune
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
    val Copy = Icons.Rounded.ContentCopy
    val Link = Icons.Rounded.Link
    val OpenExternal = Icons.AutoMirrored.Rounded.OpenInNew
    val SignIn = Icons.AutoMirrored.Rounded.Login
    val SignOut = Icons.AutoMirrored.Rounded.Logout
    val Language = Icons.Rounded.Language
    val Heart = Icons.Rounded.Favorite
    val Mic = Icons.Rounded.Mic
    val Insights = Icons.Rounded.Insights
    val Sound = Icons.Rounded.MusicNote
    val Fill = Icons.Rounded.NorthWest
    val Person = Icons.Rounded.Person
    val Live = Icons.Rounded.LiveTv
    val Private = Icons.Rounded.Lock
    val Public = Icons.Rounded.Public
}
