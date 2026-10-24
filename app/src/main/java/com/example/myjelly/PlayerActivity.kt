package com.example.myjelly

import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.abs

/**
 * MX Player-style player + quality/audio settings (server-side HLS transcoding).
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var hud: TextView
    private lateinit var lockBtn: TextView
    private lateinit var settingsBtn: TextView
    private lateinit var audio: AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private var itemId = ""
    private var sourceId = ""
    private val sessionId = UUID.randomUUID().toString().replace("-", "")
    private var subs: List<MediaItem.SubtitleConfiguration> = emptyList()
    private var quality = Quality.ORIGINAL
    private var audioMode = AudioMode.ORIGINAL
    private var locked = false
    private var longPressing = false

    private enum class Mode { NONE, SEEK, BRIGHT, VOLUME }
    private var mode = Mode.NONE
    private var startPos = 0L
    private var seekTarget = 0L
    private var brightness = 0.5f
    private var volF = 0f

    private val isTranscoding get() = quality != Quality.ORIGINAL || audioMode != AudioMode.ORIGINAL

    private val hideHud = Runnable { hud.visibility = View.GONE }
    private val progressTick = object : Runnable {
        override fun run() {
            report("progress")
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.load(this)
        itemId = intent.getStringExtra("id") ?: return finish()
        audio = getSystemService(AUDIO_SERVICE) as AudioManager

        val prefs = getSharedPreferences("player", MODE_PRIVATE)
        quality = runCatching { Quality.valueOf(prefs.getString("quality", "ORIGINAL") ?: "ORIGINAL") }
            .getOrDefault(Quality.ORIGINAL)
        audioMode = runCatching { AudioMode.valueOf(prefs.getString("audio", "ORIGINAL") ?: "ORIGINAL") }
            .getOrDefault(AudioMode.ORIGINAL)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        player = ExoPlayer.Builder(
            this,
            DefaultRenderersFactory(this)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        ).setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build()

        playerView = PlayerView(this).apply {
            player = this@PlayerActivity.player
            setShowSubtitleButton(true)
            controllerShowTimeoutMs = 3000
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        hud = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 22f; setPadding(40, 24, 40, 24)
            setBackgroundColor(0x99000000.toInt()); visibility = View.GONE
        }
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        fun pill(label: String) = TextView(this).apply {
            text = label; setTextColor(Color.WHITE); textSize = 14f
            setPadding(dp(14), dp(8), dp(14), dp(8)); visibility = View.GONE
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xB3000000.toInt()); cornerRadius = dp(20).toFloat()
            }
        }
        lockBtn = pill("🔓 Lock").apply { setOnClickListener { toggleLock() } }
        settingsBtn = pill("⚙ Quality & Audio").apply { setOnClickListener { showPlaybackSettings() } }
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(settingsBtn, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
            addView(lockBtn, LinearLayout.LayoutParams(-2, -2))
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(playerView, FrameLayout.LayoutParams(-1, -1))
            addView(hud, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(bottomRow, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END)
                .apply { rightMargin = dp(20); bottomMargin = dp(96) })
        }
        setContentView(root)

        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            if (!locked) {
                val vis = if (v == View.VISIBLE) View.VISIBLE else View.GONE
                lockBtn.visibility = vis
                settingsBtn.visibility = vis
            }
        })
        setupGestures()
        loadAndPlay()
    }

    private fun buildMediaItem(): MediaItem {
        val b = MediaItem.Builder().setSubtitleConfigurations(subs)
        if (!isTranscoding) {
            b.setUri(Api.streamUrl(itemId, sourceId))
        } else {
            b.setUri(Api.hlsUrl(itemId, sourceId, quality, audioMode, sessionId))
                .setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        return b.build()
    }

    private fun loadAndPlay() = lifecycleScope.launch {
        try {
            val item = Api.item(itemId)
            val src = item.mediaSources?.firstOrNull() ?: error("No media source")
            sourceId = src.id
            subs = src.streams.filter { it.type == "Subtitle" && it.isExternal }.map {
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(Api.subtitleUrl(itemId, sourceId, it.index)))
                    .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                    .setLanguage(it.language).setLabel(it.title ?: it.language ?: "Subtitle").build()
            }
            player.setMediaItem(buildMediaItem())
            player.prepare()
            val resumeMs = (item.userData?.positionTicks ?: 0L) / 10_000
            if (resumeMs > 5_000) player.seekTo(resumeMs)
            player.playWhenReady = true
            report("start")
            handler.postDelayed(progressTick, 10_000)
            player.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { report("progress") }
                override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) finish() }
                override fun onPlayerError(error: PlaybackException) {
                    showHud(if (isTranscoding) "Transcode failed (${error.errorCodeName}). Try another quality in ⚙."
                            else "Playback error (${error.errorCodeName})", 5000)
                }
            })
        } catch (e: Exception) { showHud("Error: ${e.message}", 4000) }
    }

    // ---------- quality / audio settings ----------
    private fun showPlaybackSettings() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0)
        }
        fun header(t: String) = TextView(this).apply {
            text = t; setTextColor(0xFFE50914.toInt()); textSize = 14f; setPadding(0, pad / 2, 0, pad / 4)
        }
        val qGroup = RadioGroup(this)
        Quality.values().forEachIndexed { i, q ->
            qGroup.addView(RadioButton(this).apply { id = 1000 + i; text = q.label })
        }
        qGroup.check(1000 + quality.ordinal)
        val aGroup = RadioGroup(this)
        AudioMode.values().forEachIndexed { i, a ->
            aGroup.addView(RadioButton(this).apply { id = 2000 + i; text = a.label })
        }
        aGroup.check(2000 + audioMode.ordinal)
        val note = TextView(this).apply {
            text = "Quality and audio changes use server transcoding. Audio sets the maximum channels; " +
                "sources with fewer channels are not upmixed. Original = direct play (all embedded audio/subtitle tracks)."
            textSize = 11f; setTextColor(0xFF999999.toInt()); setPadding(0, pad / 2, 0, pad / 2)
        }
        box.addView(header("VIDEO QUALITY")); box.addView(qGroup)
        box.addView(header("AUDIO")); box.addView(aGroup)
        box.addView(note)

        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Playback settings")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply") { _, _ ->
                val nq = Quality.values().getOrElse(qGroup.checkedRadioButtonId - 1000) { quality }
                val na = AudioMode.values().getOrElse(aGroup.checkedRadioButtonId - 2000) { audioMode }
                if (nq != quality || na != audioMode) {
                    quality = nq; audioMode = na
                    getSharedPreferences("player", MODE_PRIVATE).edit()
                        .putString("quality", quality.name).putString("audio", audioMode.name).apply()
                    applyPlayback()
                }
            }
            .show()
    }

    private fun applyPlayback() {
        if (sourceId.isEmpty()) return
        val pos = player.currentPosition
        player.setMediaItem(buildMediaItem(), pos)
        player.prepare()
        player.play()
        showHud("${quality.label} · Audio: ${audioMode.label}", 2000)
    }

    // ---------- gestures ----------
    private fun setupGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                if (d.scaleFactor > 1.03f && playerView.resizeMode != AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
                    playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM; showHud("Zoom (crop)")
                } else if (d.scaleFactor < 0.97f && playerView.resizeMode != AspectRatioFrameLayout.RESIZE_MODE_FIT) {
                    playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; showHud("Fit")
                }
                return true
            }
        })
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (locked) {
                    lockBtn.visibility = View.VISIBLE
                    handler.postDelayed({ if (locked) lockBtn.visibility = View.GONE }, 2000)
                } else if (playerView.isControllerFullyVisible) playerView.hideController()
                else playerView.showController()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (locked) return true
                val w = playerView.width
                when {
                    e.x < w / 3f -> { seekBy(-10_000); showHud("⏪ -10s") }
                    e.x > w * 2 / 3f -> { seekBy(10_000); showHud("+10s ⏩") }
                    else -> if (player.isPlaying) player.pause() else player.play()
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (locked) return
                longPressing = true; player.setPlaybackSpeed(2f); showHud("2×", 100_000)
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (locked || e1 == null || scale.isInProgress || longPressing) return false
                if (mode == Mode.NONE) {
                    mode = when {
                        abs(dx) > abs(dy) -> { startPos = player.currentPosition; Mode.SEEK }
                        e1.x < playerView.width / 2f -> {
                            val b = window.attributes.screenBrightness
                            brightness = if (b < 0) 0.5f else b; Mode.BRIGHT
                        }
                        else -> { volF = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat(); Mode.VOLUME }
                    }
                }
                when (mode) {
                    Mode.SEEK -> {
                        val dur = player.duration.coerceAtLeast(0L)
                        seekTarget = (startPos + (e2.x - e1.x) * 120_000f / playerView.width).toLong().coerceIn(0L, dur)
                        val sign = if (seekTarget >= startPos) "+" else "-"
                        showHud("${fmt(seekTarget)}  ($sign${fmt(abs(seekTarget - startPos))})", 1500)
                    }
                    Mode.BRIGHT -> {
                        brightness = (brightness + dy / playerView.height).coerceIn(0.01f, 1f)
                        window.attributes = window.attributes.also { it.screenBrightness = brightness }
                        showHud("☀ ${(brightness * 100).toInt()}%")
                    }
                    Mode.VOLUME -> {
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        volF = (volF + dy / playerView.height * max * 1.5f).coerceIn(0f, max.toFloat())
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, volF.toInt(), 0)
                        showHud("🔊 ${(volF / max * 100).toInt()}%")
                    }
                    else -> {}
                }
                return true
            }
        })
        playerView.setOnTouchListener { _, ev ->
            scale.onTouchEvent(ev)
            gd.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (mode == Mode.SEEK) player.seekTo(seekTarget)
                mode = Mode.NONE
                if (longPressing) { longPressing = false; player.setPlaybackSpeed(1f); hud.visibility = View.GONE }
            }
            true
        }
    }

    private fun toggleLock() {
        locked = !locked
        lockBtn.text = if (locked) "🔒 Unlock" else "🔓 Lock"
        playerView.useController = !locked
        if (locked) {
            playerView.hideController()
            settingsBtn.visibility = View.GONE
            handler.postDelayed({ lockBtn.visibility = View.GONE }, 1500)
        }
        showHud(if (locked) "Locked" else "Unlocked")
    }

    private fun seekBy(ms: Long) =
        player.seekTo((player.currentPosition + ms).coerceIn(0L, player.duration.coerceAtLeast(0L)))

    private fun showHud(text: String, hideAfter: Long = 800) {
        hud.text = text; hud.visibility = View.VISIBLE
        handler.removeCallbacks(hideHud); handler.postDelayed(hideHud, hideAfter)
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
        else "%02d:%02d".format(s / 60, s % 60)
    }

    // ---------- reporting & lifecycle ----------
    private fun report(kind: String) {
        if (sourceId.isEmpty()) return
        val pos = player.currentPosition; val paused = !player.isPlaying
        val tc = isTranscoding
        lifecycleScope.launch { Api.report(kind, itemId, sourceId, pos, paused, tc, sessionId) }
    }

    override fun onUserLeaveHint() {
        if (Build.VERSION.SDK_INT >= 26 && player.isPlaying)
            enterPictureInPictureMode(PictureInPictureParams.Builder().build())
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        playerView.useController = !isInPip && !locked
        if (isInPip) { lockBtn.visibility = View.GONE; settingsBtn.visibility = View.GONE }
    }

    override fun onStop() {
        super.onStop()
        if (!isInPictureInPictureMode) player.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (sourceId.isNotEmpty()) {
            val pos = player.currentPosition; val id = itemId; val src = sourceId
            val tc = isTranscoding; val sid = sessionId
            CoroutineScope(Dispatchers.IO).launch { Api.report("stop", id, src, pos, true, tc, sid) }
        }
        player.release()
        super.onDestroy()
    }
}
