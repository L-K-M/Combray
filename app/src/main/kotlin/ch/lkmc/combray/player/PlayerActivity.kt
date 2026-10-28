package ch.lkmc.combray.player

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import ch.lkmc.combray.CombrayApplication
import ch.lkmc.combray.R
import ch.lkmc.combray.core.media.PlaybackRequest
import ch.lkmc.combray.core.media.refusalIn
import ch.lkmc.combray.engine.MediaKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Where the player is, for the device checks; never an address. */
internal enum class PlaybackPhase { IDLE, BUFFERING, READY, ENDED, FAILED, RELEASED }

internal data class PlaybackSnapshot(
    val phase: PlaybackPhase = PlaybackPhase.IDLE,
    val positionMs: Long = 0,
    /** Null until the length is known. */
    val durationMs: Long? = null,
    val failure: MediaFailure? = null,
    /** From the tracks found, not from the element: a `<video>` often plays an audio-only file. */
    val hasVideo: Boolean = false,
    val firstFrameRendered: Boolean = false,
)

/**
 * The fullscreen player for one page's audio or video, opened by a tap (docs/VIDEO.md, phase C1).
 * An activity of its own: a player surface over the engine's surface in one window has no
 * defined stacking order, and rotation recreates neither this nor the browser.
 *
 * The player exists while the activity is started: [onStart] builds it, [onStop] saves its
 * position and releases it, so nothing plays in the background. It fetches through
 * [ch.lkmc.combray.core.media.MediaNetwork], which applies the network rules to every hop.
 * Errors are shown, never replaced by a silent fallback.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private val graph get() = (application as CombrayApplication).graph

    private var requestId: String? = null
    private lateinit var request: PlaybackRequest
    private lateinit var playerView: PlayerView
    private lateinit var close: ImageButton
    private lateinit var infoText: TextView
    private lateinit var errorText: TextView

    private var player: ExoPlayer? = null
    private var positionMs = 0L
    private var playWhenReady = true
    private var failure: MediaFailure? = null
    private var ticker: Job? = null

    private val state = MutableStateFlow(PlaybackSnapshot())

    /** For the device checks (MediaPlayerTest): the player's phase, position and tracks. */
    internal val snapshot: StateFlow<PlaybackSnapshot> = state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = publish()

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playerView.keepScreenOn = isPlaying && state.value.hasVideo
            if (isPlaying) startTicker() else ticker?.cancel()
            publish()
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (tracks.isEmpty) return
            val video = tracks.containsType(C.TRACK_TYPE_VIDEO)
            state.update { it.copy(hasVideo = video) }
            showLayout(video)
        }

        override fun onRenderedFirstFrame() = state.update { it.copy(firstFrameRendered = true) }

        override fun onPlayerError(error: PlaybackException) {
            val failed = error.failure()
            // The code and the refusal's name only: an exception's message or causes can name the address.
            val refusal = refusalIn(error)?.let { " ($it)" }.orEmpty()
            Log.w(LOG_TAG, "playback failed: ${error.errorCodeName}$refusal")
            showFailure(failed)
            publish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Light icons on the black window, whatever the app's theme.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_REQUEST)
        // After process death the request is gone: the browser comes back instead.
        val offered = id?.let(graph.playback::get)
        if (offered == null) {
            finish()
            return
        }
        requestId = id
        request = offered
        savedInstanceState?.let {
            positionMs = it.getLong(STATE_POSITION)
            playWhenReady = it.getBoolean(STATE_PLAY_WHEN_READY, true)
            failure = it.getString(STATE_FAILURE)?.let(MediaFailure::valueOf)
        }
        // TalkBack announces it as the window opens.
        title = getString(R.string.player_playing, request.name)
        setContentView(layout())
        failure?.let(::showFailure)
    }

    override fun onStart() {
        super.onStart()
        if (isFinishing || failure != null) return
        startPlayer()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        player?.let {
            positionMs = it.currentPosition
            playWhenReady = it.playWhenReady
        }
        outState.putLong(STATE_POSITION, positionMs)
        outState.putBoolean(STATE_PLAY_WHEN_READY, playWhenReady)
        failure?.let { outState.putString(STATE_FAILURE, it.name) }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) requestId?.let(graph.playback::remove)
    }

    /** Hides the playback controls, so a device check sees the video alone. */
    internal fun hideControls() = playerView.hideController()

    /** Shows the playback controls, and Close with them, for a device check. */
    internal fun showControls() = playerView.showController()

    private fun startPlayer() {
        // A frame the released player drew is not this one's.
        state.update { it.copy(firstFrameRendered = false) }
        val content = if (request.kind == MediaKind.VIDEO) C.AUDIO_CONTENT_TYPE_MOVIE else C.AUDIO_CONTENT_TYPE_MUSIC
        val attributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(content).build()
        // Decoder fallback: a decoder that fails to start gives way to the next, a software one included.
        val exo = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setAudioAttributes(attributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            // No analytics (IMPLEMENTATION_PLAN section 7): Media3 otherwise reports playback, format,
            // network-type and error events to Android's media metrics service, which Google may collect.
            .setUsePlatformDiagnostics(false)
            .build()
        exo.addListener(listener)
        // The client sets User-Agent and Referer on every hop itself.
        exo.setMediaSource(mediaSource(request, OkHttpDataSource.Factory(graph.media.callFactory(request))), positionMs)
        exo.playWhenReady = playWhenReady
        exo.prepare()
        playerView.player = exo
        player = exo
        publish()
    }

    private fun releasePlayer() {
        val exo = player ?: return
        positionMs = exo.currentPosition.coerceAtLeast(0)
        playWhenReady = exo.playWhenReady
        ticker?.cancel()
        exo.removeListener(listener)
        exo.release()
        player = null
        playerView.player = null
        playerView.keepScreenOn = false
        state.update { it.copy(phase = PlaybackPhase.RELEASED, positionMs = positionMs) }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = lifecycleScope.launch {
            while (isActive) {
                publish()
                delay(TICK_MS)
            }
        }
    }

    private fun publish() {
        val exo = player ?: return
        val phase = when {
            failure != null -> PlaybackPhase.FAILED
            exo.playbackState == Player.STATE_BUFFERING -> PlaybackPhase.BUFFERING
            exo.playbackState == Player.STATE_READY -> PlaybackPhase.READY
            exo.playbackState == Player.STATE_ENDED -> PlaybackPhase.ENDED
            else -> PlaybackPhase.IDLE
        }
        state.update {
            it.copy(
                phase = phase,
                positionMs = exo.currentPosition,
                durationMs = exo.duration.takeIf { duration -> duration != C.TIME_UNSET },
                failure = failure,
            )
        }
    }

    /** Video goes immersive; audio keeps its controls on screen, with its name and site. */
    private fun showLayout(video: Boolean) {
        if (failure != null) return
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        if (video) {
            infoText.visibility = View.GONE
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
            return
        }
        bars.show(WindowInsetsCompat.Type.systemBars())
        infoText.text = getString(R.string.player_audio_info, request.name, request.site)
        infoText.visibility = View.VISIBLE
        playerView.controllerShowTimeoutMs = 0
        playerView.controllerHideOnTouch = false
        playerView.showController()
    }

    private fun showFailure(failed: MediaFailure) {
        failure = failed
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        playerView.keepScreenOn = false
        playerView.useController = false
        infoText.visibility = View.GONE
        errorText.setText(failed.message)
        errorText.visibility = View.VISIBLE
        close.visibility = View.VISIBLE
    }

    private fun layout(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            setShowNextButton(false)
            setShowPreviousButton(false)
        }
        root.addView(playerView, FrameLayout.LayoutParams(MATCH, MATCH))

        // Above Media3's centre controls (play/pause and seeking), which stay on screen for audio.
        infoText = text().also { root.addView(it, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL)) }
        errorText = text().apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
            .also { root.addView(it, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER)) }

        close = ImageButton(this).apply {
            setImageResource(R.drawable.ic_player_close)
            contentDescription = getString(R.string.player_close)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(CLOSE_BACKGROUND)
            }
            setOnClickListener { finish() }
            // PlayerView hides its controller before the listener below exists, without a callback.
            visibility = View.GONE
        }
        root.addView(close, FrameLayout.LayoutParams(dp(CLOSE_SIZE_DP), dp(CLOSE_SIZE_DP), Gravity.BOTTOM or Gravity.END))
        // Close shows with the controls (and always after a failure).
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                close.visibility = if (failure != null) View.VISIBLE else visibility
            },
        )

        // Controls and Close stay clear of cutouts and of system bars while they show.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            playerView.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            val end = if (root.layoutDirection == View.LAYOUT_DIRECTION_RTL) safe.left else safe.right
            (close.layoutParams as ViewGroup.MarginLayoutParams).apply {
                marginEnd = dp(CLOSE_MARGIN_DP) + end
                bottomMargin = dp(CLOSE_ABOVE_CONTROLS_DP) + safe.bottom
            }
            close.requestLayout()
            (infoText.layoutParams as ViewGroup.MarginLayoutParams).topMargin = safe.top
            infoText.requestLayout()
            insets
        }
        return root
    }

    private fun text() = TextView(this).apply {
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, MESSAGE_TEXT_SP)
        val margin = dp(CLOSE_SIZE_DP)
        setPadding(margin, margin, margin, margin)
        visibility = View.GONE
    }

    private fun dp(value: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val EXTRA_REQUEST = "ch.lkmc.combray.player.REQUEST"
        private const val STATE_POSITION = "position"
        private const val STATE_PLAY_WHEN_READY = "playWhenReady"
        private const val STATE_FAILURE = "failure"
        private const val LOG_TAG = "CombrayPlayer"
        private const val TICK_MS = 250L
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val CLOSE_SIZE_DP = 48
        private const val CLOSE_MARGIN_DP = 8

        /** Above Media3's time bar and bottom controls (exo_styled_progress_margin_bottom and its bar). */
        private const val CLOSE_ABOVE_CONTROLS_DP = 104
        private const val CLOSE_BACKGROUND = 0x66000000
        private const val MESSAGE_TEXT_SP = 18f

        /** Opens the player for a request offered to [PlaybackRequests]; the Intent carries only its id. */
        fun intent(context: Context, requestId: String): Intent =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_REQUEST, requestId)
    }
}

/**
 * The media source for [request]. C1 plays progressive files only: a playlist fails as a format
 * error instead of reaching Media3's automatic source selection. C2 adds an HLS branch here
 * (with `media3-exoplayer-hls`).
 */
@OptIn(UnstableApi::class)
internal fun mediaSource(request: PlaybackRequest, factory: DataSource.Factory): MediaSource =
    ProgressiveMediaSource.Factory(factory)
        .setLoadErrorHandlingPolicy(RefusalsAreFinal)
        .createMediaSource(MediaItem.fromUri(request.url.toString()))

/** Media3 retries failed loads; a refused address is refused again, so it fails at once. */
@OptIn(UnstableApi::class)
private object RefusalsAreFinal : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (refusalIn(loadErrorInfo.exception) != null) C.TIME_UNSET else super.getRetryDelayMsFor(loadErrorInfo)
}
