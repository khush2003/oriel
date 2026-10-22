package app.oriel.core.stream

import com.metrolist.innertubex.extraction.StreamAttemptDiagnostic
import com.metrolist.innertubex.extraction.StreamDiagnostics
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.extraction.StreamResolveException.Reason
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The messages and outcomes here are InnerTubeX 0.10's own strings: fixed messages such as
 * "No playable stream found for this track.", and "playability:<status>" per client attempt.
 */
class DescribeFailureTest {
    private fun failure(reason: Reason, message: String, vararg outcomes: String) = StreamResolveException(
        reason,
        message,
        null,
        StreamDiagnostics(outcomes.mapIndexed { i, o -> StreamAttemptDiagnostic("CLIENT$i", "client$i", "ua", o) }, false),
    )

    @Test fun loginRequiredEverywhereNamesPrivateAndTheBotCheck() {
        val e = failure(Reason.NO_PLAYABLE_STREAM, "No playable stream found for this track.", "playability:LOGIN_REQUIRED", "playability:LOGIN_REQUIRED")
        assertEquals(StreamResolver.SIGN_IN_WANTED, StreamResolver.describe(e))
    }

    @Test fun botCheckWordingInTheMessage() {
        val e = failure(Reason.UNKNOWN, "Sign in to confirm you’re not a bot")
        assertEquals(StreamResolver.BOT_CHECK, StreamResolver.describe(e))
    }

    @Test fun ageRestrictedIsNotTheBotCheck() {
        val e = failure(Reason.AGE_RESTRICTED, "This track is age-restricted.", "playability:LOGIN_REQUIRED", "playability:AGE_CHECK_REQUIRED")
        assertEquals("This video is age-restricted. Sign in to play it.", StreamResolver.describe(e))
    }

    @Test fun youTubesReasonsWhenTheMessageCarriesThem() {
        assertEquals(StreamResolver.PRIVATE, StreamResolver.describe(failure(Reason.UNAVAILABLE, "This video is private")))
        assertEquals(StreamResolver.REMOVED, StreamResolver.describe(failure(Reason.UNAVAILABLE, "This video has been removed by the uploader")))
        assertEquals(StreamResolver.REMOVED, StreamResolver.describe(failure(Reason.UNAVAILABLE, "This account has been terminated")))
        assertEquals(StreamResolver.NOT_IN_COUNTRY, StreamResolver.describe(failure(Reason.UNAVAILABLE, "The uploader has not made this video available in your country")))
    }

    @Test fun otherwiseTheReasonsText() {
        val unavailable = failure(Reason.UNAVAILABLE, "This track is unavailable.", "playability:UNPLAYABLE")
        assertEquals("This video isn't available in your region or was removed.", StreamResolver.describe(unavailable))
        val none = failure(Reason.NO_PLAYABLE_STREAM, "No playable stream found for this track.", "request:IOException")
        assertEquals("No playable stream found for this track.", StreamResolver.describe(none))
        // An unexpected failure filed under NETWORK that wasn't a connection problem.
        val parse = failure(Reason.NETWORK, "Unable to fetch stream data from YouTube.", "request:SerializationException")
        assertEquals("This video can't be played right now.", StreamResolver.describe(parse))
    }
}
