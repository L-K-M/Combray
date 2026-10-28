package ch.lkmc.combray.player

import androidx.annotation.StringRes
import androidx.media3.common.PlaybackException
import ch.lkmc.combray.R
import ch.lkmc.combray.core.media.refusalIn

/** Why the player stopped, as the player says it. Logged by name only. */
enum class MediaFailure(@StringRes val message: Int) {
    /** The network rules refused an address (ch.lkmc.combray.core.media.MediaPolicy). */
    REFUSED(R.string.player_error_refused),
    NETWORK(R.string.player_error_network),

    /** The server answered with an error status. */
    HTTP_STATUS(R.string.player_error_http),

    /** Not a file Media3 reads: an unknown container, or a playlist in C1. */
    FORMAT(R.string.player_error_format),
    DECODER(R.string.player_error_decoder),
    OTHER(R.string.player_error_other),
}

/** [PlaybackException]'s code groups: 2xxx input and output, 3xxx parsing, 4xxx decoding. */
private const val ERROR_CODE_GROUP = 1000
private const val IO_GROUP = 2
private const val PARSING_GROUP = 3
private const val DECODING_GROUP = 4

/** The failure behind a playback error with [errorCode] and [cause]: a refusal anywhere in the causes first. */
internal fun mediaFailure(errorCode: Int, cause: Throwable?): MediaFailure {
    if (cause != null && refusalIn(cause) != null) return MediaFailure.REFUSED
    return when {
        errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> MediaFailure.HTTP_STATUS
        errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> MediaFailure.FORMAT
        // OkHttp's "Too many follow-up requests" lands here too.
        errorCode / ERROR_CODE_GROUP == IO_GROUP -> MediaFailure.NETWORK
        errorCode / ERROR_CODE_GROUP == PARSING_GROUP -> MediaFailure.FORMAT
        errorCode / ERROR_CODE_GROUP == DECODING_GROUP -> MediaFailure.DECODER
        else -> MediaFailure.OTHER
    }
}

internal fun PlaybackException.failure(): MediaFailure = mediaFailure(errorCode, cause)
