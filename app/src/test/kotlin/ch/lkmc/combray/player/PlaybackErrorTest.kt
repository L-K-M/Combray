package ch.lkmc.combray.player

import androidx.media3.common.PlaybackException
import ch.lkmc.combray.core.media.MediaRefusal
import ch.lkmc.combray.core.media.MediaRefusedException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ProtocolException
import java.net.SocketTimeoutException

class PlaybackErrorTest {
    @Test
    fun aRefusalAnywhereInTheCausesIsARefusal() {
        // Media3 wraps the client's exception (HttpDataSourceException, then the playback error).
        val wrapped = IOException("open failed", MediaRefusedException(MediaRefusal.MIXED_CONTENT))
        assertEquals(MediaFailure.REFUSED, mediaFailure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, wrapped))
        assertEquals(MediaFailure.REFUSED, mediaFailure(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, MediaRefusedException(MediaRefusal.NOT_WEB)))
    }

    @Test
    fun networkErrorsIncludeOkHttpsRedirectLimit() {
        val tooMany = IOException(ProtocolException("Too many follow-up requests: 21"))
        assertEquals(MediaFailure.NETWORK, mediaFailure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, tooMany))
        assertEquals(MediaFailure.NETWORK, mediaFailure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, SocketTimeoutException()))
        assertEquals(MediaFailure.NETWORK, mediaFailure(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, null))
    }

    @Test
    fun statusFormatAndDecoderErrorsHaveTheirOwnMessages() {
        assertEquals(MediaFailure.HTTP_STATUS, mediaFailure(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, IOException()))
        assertEquals(MediaFailure.FORMAT, mediaFailure(PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE, null))
        assertEquals(MediaFailure.FORMAT, mediaFailure(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, null))
        assertEquals(MediaFailure.FORMAT, mediaFailure(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, null))
        assertEquals(MediaFailure.DECODER, mediaFailure(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, null))
        assertEquals(MediaFailure.DECODER, mediaFailure(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, null))
        assertEquals(MediaFailure.OTHER, mediaFailure(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, null))
        assertEquals(MediaFailure.OTHER, mediaFailure(PlaybackException.ERROR_CODE_UNSPECIFIED, null))
        // 1003 is a player-internal timeout (a stuck decoder or surface), not the network's 2002.
        assertEquals(MediaFailure.OTHER, mediaFailure(PlaybackException.ERROR_CODE_TIMEOUT, null))
    }

    @Test
    fun aCauseChainThatLoopsEnds() {
        val first = IOException("first")
        val second = IOException("second", first)
        first.initCause(second)
        assertEquals(MediaFailure.NETWORK, mediaFailure(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, first))
    }
}
