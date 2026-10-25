package com.example.myjelly

import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.ScaleGestureDetector
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/**
 * MX Player-style player with a custom control layout:
 * top bar (back, title, audio/subtitle tracks, quality, menu), shortcut circles (audio output, speed,
 * screenshot, audio-only, rotate, more), seek bar, and bottom row (lock, prev/play/next, aspect, PiP).
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private val accent = 0xFF4FB3FF.toInt()

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var hud: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var controls: FrameLayout
    private lateinit var lockOverlay: ImageView
    private lateinit var titleView: TextView
    private lateinit var qualityLabel: TextView
    private lateinit var speedBtn: TextView
    private lateinit var phonesBtn: ImageView
    private lateinit var chevBtn: ImageView
    private lateinit var loopBtn: ImageView
    private lateinit var skipBtn: TextView
    private lateinit var seek: SeekBar
    private lateinit var curTime: TextView
    private lateinit var durTime: TextView
    private lateinit var playBtn: ImageView
    private lateinit var prevBtn: ImageView
    private lateinit var nextBtn: ImageView
    private lateinit var audio: AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private var itemId = ""
    private var sourceId = ""
    private var sessionId = UUID.randomUUID().toString().replace("-", "")
    private var subs: List<MediaItem.SubtitleConfiguration> = emptyList()
    private var quality = Quality.ORIGINAL
    private var audioMode = AudioMode.ORIGINAL
    private var siblings: List<Item> = emptyList()
    private var siblingIndex = -1

    private var locked = false
    private var longPressing = false
    private var controlsVisible = false
    private var scrubbing = false
    private var audioOnly = false
    private var loopOne = false
    private var speed = 1f

    private enum class Mode { NONE, SEEK, BRIGHT, VOLUME }
    private var mode = Mode.NONE
    private var startPos = 0L
    private var seekTarget = 0L
    private var brightness = 0.5f
    private var volF = 0f

    private val isTranscoding get() = quality != Quality.ORIGINAL || audioMode != AudioMode.ORIGINAL

    private val hideHud = Runnable { hud.visibility = View.GONE }
    private val autoHide = Runnable { hideControls() }
    private val hideLockOverlay = Runnable { lockOverlay.visibility = View.GONE }
    private val progressTick = object : Runnable {
        override fun run() { report("progress"); handler.postDelayed(this, 10_000) }
    }
    private val uiTick = object : Runnable {
        override fun run() {
            if (controlsVisible && !scrubbing) updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    // ================================================================= lifecycle
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
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        buildUi()
        setupGestures()
        attachPlayerListener()
        handler.post(uiTick)
        showControls()
        loadAndPlay()
    }

    // ================================================================= UI
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun circleBg() = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x99181E30.toInt()) }

    private fun icon(res: Int, size: Int = 48, pad: Int = 12) = ImageView(this).apply {
        setImageResource(res); setColorFilter(Color.WHITE)
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    private fun circle(res: Int, size: Int = 44) = ImageView(this).apply {
        setImageResource(res); setColorFilter(Color.WHITE)
        setPadding(dp(10), dp(10), dp(10), dp(10)); background = circleBg()
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply { rightMargin = dp(12) }
    }

    private fun textCircle(label: String) = TextView(this).apply {
        text = label; setTextColor(Color.WHITE); textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.CENTER; background = circleBg()
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) }
    }

    private fun act(v: View, f: () -> Unit) = v.setOnClickListener { scheduleHide(); f() }

    private fun buildUi() {
        // ---- top bar
        val backBtn = icon(R.drawable.ic_back)
        titleView = TextView(this).apply {
            text = intent.getStringExtra("title") ?: ""
            setTextColor(Color.WHITE); textSize = 17f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(4), 0, dp(8), 0); gravity = Gravity.CENTER_VERTICAL
        }
        val audioTrackBtn = icon(R.drawable.ic_music)
        val subBtn = icon(R.drawable.ic_subtitles)
        qualityLabel = TextView(this).apply {
            text = qualityText(); setTextColor(Color.WHITE); textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER; setPadding(dp(10), 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(-2, dp(48))
        }
        val moreBtn = icon(R.drawable.ic_more)
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(16))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0))
            addView(backBtn)
            addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
            addView(audioTrackBtn); addView(subBtn); addView(qualityLabel); addView(moreBtn)
        }

        // ---- shortcut circles
        val tuneBtn = circle(R.drawable.ic_tune)
        speedBtn = textCircle(speedText(speed))
        val shotBtn = circle(R.drawable.ic_camera)
        phonesBtn = circle(R.drawable.ic_headphones)
        val rotateBtn = circle(R.drawable.ic_rotate)
        chevBtn = circle(R.drawable.ic_chevron, 32).apply { setPadding(dp(4), dp(4), dp(4), dp(4)) }
        loopBtn = circle(R.drawable.ic_repeat).apply { visibility = View.GONE }
        skipBtn = textCircle("+90s").apply { textSize = 12f; visibility = View.GONE }
        val shortcuts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(tuneBtn); addView(speedBtn); addView(shotBtn); addView(phonesBtn)
            addView(rotateBtn); addView(chevBtn); addView(loopBtn); addView(skipBtn)
        }

        // ---- bottom: seek row
        curTime = TextView(this).apply {
            text = "00:00"; setTextColor(Color.WHITE); textSize = 14f; minWidth = dp(48); gravity = Gravity.CENTER
        }
        durTime = TextView(this).apply {
            text = "--:--"; setTextColor(Color.WHITE); textSize = 14f; minWidth = dp(48); gravity = Gravity.CENTER
        }
        seek = SeekBar(this).apply {
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(0x55FFFFFF)
            secondaryProgressTintList = ColorStateList.valueOf(0x88FFFFFF.toInt())
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) curTime.text = fmt(p.toLong())
            }
            override fun onStartTrackingTouch(s: SeekBar) { scrubbing = true; handler.removeCallbacks(autoHide) }
            override fun onStopTrackingTouch(s: SeekBar) {
                scrubbing = false; player.seekTo(s.progress.toLong()); scheduleHide()
            }
        })
        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            addView(curTime)
            addView(seek, LinearLayout.LayoutParams(0, dp(36), 1f))
            addView(durTime)
        }

        // ---- bottom: button row
        val lockBtn = icon(R.drawable.ic_lock_open)
        prevBtn = icon(R.drawable.ic_prev, 48, 10)
        playBtn = icon(R.drawable.ic_pause, 64, 12)
        nextBtn = icon(R.drawable.ic_next, 48, 10)
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(prevBtn, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(28) })
            addView(playBtn, LinearLayout.LayoutParams(dp(64), dp(64)))
            addView(nextBtn, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(28) })
        }
        val aspectBtn = icon(R.drawable.ic_aspect)
        val pipBtn = icon(R.drawable.ic_pip)
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(aspectBtn); addView(pipBtn, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(8) })
        }
        val buttonRow = FrameLayout(this).apply {
            setPadding(dp(16), 0, dp(16), dp(8))
            addView(lockBtn, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.CENTER_VERTICAL))
            addView(center, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(right, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
        }
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(24), 0, 0)
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0, 0xCC000000.toInt()))
            addView(seekRow); addView(buttonRow)
        }

        controls = FrameLayout(this).apply {
            addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(shortcuts, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START)
                .apply { topMargin = dp(64); leftMargin = dp(16) })
            addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }

        hud = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 22f; setPadding(40, 24, 40, 24)
            setBackgroundColor(0x99000000.toInt()); visibility = View.GONE
        }
        spinner = ProgressBar(this).apply {
            isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(accent); visibility = View.GONE
        }
        lockOverlay = ImageView(this).apply {
            setImageResource(R.drawable.ic_lock); setColorFilter(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12)); background = circleBg(); visibility = View.GONE
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(playerView, FrameLayout.LayoutParams(-1, -1))
            addView(spinner, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
            addView(controls, FrameLayout.LayoutParams(-1, -1))
            addView(hud, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(lockOverlay, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.START)
                .apply { leftMargin = dp(32); bottomMargin = dp(32) })
        }
        setContentView(root)

        // ---- actions
        act(backBtn) { finish() }
        act(audioTrackBtn) { showTrackDialog(C.TRACK_TYPE_AUDIO, "Audio track") }
        act(subBtn) { showTrackDialog(C.TRACK_TYPE_TEXT, "Subtitles") }
        act(qualityLabel) { showPlaybackSettings(showVideo = true, showAudio = false) }
        act(moreBtn) { showPlaybackSettings(showVideo = true, showAudio = true) }
        act(tuneBtn) { showPlaybackSettings(showVideo = false, showAudio = true) }
        act(speedBtn) { showSpeedDialog() }
        act(shotBtn) { takeScreenshot() }
        act(phonesBtn) { toggleAudioOnly() }
        act(rotateBtn) { rotateScreen() }
        act(chevBtn) {
            val open = loopBtn.visibility != View.VISIBLE
            loopBtn.visibility = if (open) View.VISIBLE else View.GONE
            skipBtn.visibility = if (open) View.VISIBLE else View.GONE
            chevBtn.rotation = if (open) 180f else 0f
        }
        act(loopBtn) {
            loopOne = !loopOne
            player.repeatMode = if (loopOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            loopBtn.setColorFilter(if (loopOne) accent else Color.WHITE)
            showHud(if (loopOne) "Repeat: on" else "Repeat: off")
        }
        act(skipBtn) { seekBy(90_000); showHud("+90s") }
        act(lockBtn) { toggleLock() }
        act(prevBtn) {
            if (siblingIndex > 0) switchTo(siblings[siblingIndex - 1].id) else player.seekTo(0)
        }
        act(nextBtn) {
            if (siblingIndex >= 0 && siblingIndex < siblings.size - 1) switchTo(siblings[siblingIndex + 1].id)
            else showHud("No next episode")
        }
        act(playBtn) { if (player.isPlaying) player.pause() else player.play() }
        act(aspectBtn) { cycleAspect() }
        act(pipBtn) { enterPip() }
        lockOverlay.setOnClickListener { toggleLock() }
    }

    private fun qualityText() = if (quality == Quality.ORIGINAL) "ORIG" else quality.label.substringBefore(' ')
    private fun speedText(f: Float) = (if (f % 1f == 0f) f.toInt().toString() else f.toString()) + "X"

    private fun showControls() {
        if (locked) return
        controlsVisible = true; controls.visibility = View.VISIBLE
        updateProgress(); scheduleHide()
    }

    private fun hideControls() {
        controlsVisible = false; controls.visibility = View.GONE
        handler.removeCallbacks(autoHide)
    }

    private fun scheduleHide() {
        handler.removeCallbacks(autoHide)
        if (player.isPlaying) handler.postDelayed(autoHide, 4000)
    }

    private fun updateProgress() {
        val dur = player.duration
        if (dur > 0) seek.max = dur.toInt()
        seek.progress = player.currentPosition.toInt().coerceAtLeast(0)
        seek.secondaryProgress = player.bufferedPosition.toInt().coerceAtLeast(0)
        curTime.text = fmt(player.currentPosition)
        durTime.text = if (dur > 0) fmt(dur) else "--:--"
    }

    private fun updateNav() {
        nextBtn.alpha = if (siblingIndex >= 0 && siblingIndex < siblings.size - 1) 1f else 0.35f
    }

    // ================================================================= playback
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

    private fun attachPlayerListener() {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playBtn.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                if (isPlaying) { if (controlsVisible) scheduleHide() } else handler.removeCallbacks(autoHide)
                report("progress")
            }
            override fun onPlaybackStateChanged(state: Int) {
                spinner.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (state == Player.STATE_READY) updateProgress()
                if (state == Player.STATE_ENDED) {
                    if (siblingIndex >= 0 && siblingIndex < siblings.size - 1) switchTo(siblings[siblingIndex + 1].id)
                    else finish()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                showHud(if (isTranscoding) "Transcode failed (${error.errorCodeName}). Try another quality."
                        else "Playback error (${error.errorCodeName})", 5000)
            }
        })
    }

    private fun loadAndPlay() = lifecycleScope.launch {
        try {
            val item = Api.item(itemId)
            val src = item.mediaSources?.firstOrNull() ?: error("No media source")
            sourceId = src.id
            titleView.text = if (item.type == "Episode" && item.seriesName != null)
                "${item.seriesName} · S${item.parentIndex ?: 0}:E${item.index ?: 0} · ${item.name}" else item.name
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

            // previous/next episode list
            siblings = emptyList(); siblingIndex = -1
            if (item.type == "Episode" && item.parentId != null) {
                siblings = runCatching { Api.children(item.parentId) }.getOrDefault(emptyList())
                    .filter { it.type == "Episode" }
                siblingIndex = siblings.indexOfFirst { it.id == itemId }
            }
            updateNav()
        } catch (e: Exception) { showHud("Error: ${e.message}", 4000) }
    }

    private fun switchTo(newId: String) {
        report("stop")
        handler.removeCallbacks(progressTick)
        sourceId = ""; itemId = newId
        sessionId = UUID.randomUUID().toString().replace("-", "")
        player.stop(); player.clearMediaItems()
        loadAndPlay()
    }

    private fun applyPlayback() {
        if (sourceId.isEmpty()) return
        val pos = player.currentPosition
        player.setMediaItem(buildMediaItem(), pos)
        player.prepare(); player.play()
        qualityLabel.text = qualityText()
        showHud("${quality.label} · Audio: ${audioMode.label}", 2000)
    }

    // ================================================================= dialogs
    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    private fun showPlaybackSettings(showVideo: Boolean, showAudio: Boolean) {
        val pad = dp(16)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        fun header(t: String) = TextView(this).apply {
            text = t; setTextColor(accent); textSize = 14f; setPadding(0, pad / 2, 0, pad / 4)
        }
        val qGroup = if (showVideo) RadioGroup(this).apply {
            Quality.values().forEachIndexed { i, q -> addView(RadioButton(context).apply { id = 1000 + i; text = q.label }) }
            check(1000 + quality.ordinal)
        } else null
        val aGroup = if (showAudio) RadioGroup(this).apply {
            AudioMode.values().forEachIndexed { i, a -> addView(RadioButton(context).apply { id = 2000 + i; text = a.label }) }
            check(2000 + audioMode.ordinal)
        } else null
        if (qGroup != null) { box.addView(header("VIDEO QUALITY")); box.addView(qGroup) }
        if (aGroup != null) { box.addView(header("AUDIO OUTPUT")); box.addView(aGroup) }
        box.addView(TextView(this).apply {
            text = "Quality and audio changes use server transcoding. Audio sets the maximum channels; " +
                "sources with fewer channels are not upmixed. Original = direct play."
            textSize = 11f; setTextColor(0xFF999999.toInt()); setPadding(0, pad / 2, 0, pad / 2)
        })
        dialog().setTitle("Playback settings")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply") { _, _ ->
                val nq = qGroup?.let { Quality.values().getOrElse(it.checkedRadioButtonId - 1000) { quality } } ?: quality
                val na = aGroup?.let { AudioMode.values().getOrElse(it.checkedRadioButtonId - 2000) { audioMode } } ?: audioMode
                if (nq != quality || na != audioMode) {
                    quality = nq; audioMode = na
                    getSharedPreferences("player", MODE_PRIVATE).edit()
                        .putString("quality", quality.name).putString("audio", audioMode.name).apply()
                    applyPlayback()
                }
            }.show()
    }

    private fun showSpeedDialog() {
        val opts = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        dialog().setTitle("Playback speed")
            .setSingleChoiceItems(opts.map { speedText(it) }.toTypedArray(), opts.indexOf(speed)) { d, which ->
                speed = opts[which]; player.setPlaybackSpeed(speed)
                speedBtn.text = speedText(speed); d.dismiss()
            }.show()
    }

    private fun showTrackDialog(type: Int, title: String) {
        val groups = player.currentTracks.groups.filter { it.type == type && it.isSupported }
        val isText = type == C.TRACK_TYPE_TEXT
        if (groups.isEmpty()) {
            showHud(if (isText) "No subtitles" else "No other audio tracks" + if (isTranscoding) " (transcoding)" else "")
            return
        }
        val labels = ArrayList<String>()
        if (isText) labels.add("Off")
        groups.forEachIndexed { i, g ->
            val f = g.getTrackFormat(0)
            val lang = f.language?.let { Locale(it).displayLanguage }?.takeIf { it.isNotBlank() }
            labels.add(listOfNotNull(f.label, lang).distinct().joinToString(" · ").ifEmpty { "Track ${i + 1}" })
        }
        val textOff = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val sel = groups.indexOfFirst { it.isSelected }
        val checked = if (isText) (if (textOff || sel < 0) 0 else sel + 1) else maxOf(sel, 0)
        dialog().setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which ->
                val b = player.trackSelectionParameters.buildUpon()
                if (isText) {
                    if (which == 0) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    else b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(groups[which - 1].mediaTrackGroup, 0))
                } else {
                    b.setOverrideForType(TrackSelectionOverride(groups[which].mediaTrackGroup, 0))
                }
                player.trackSelectionParameters = b.build(); d.dismiss()
            }.show()
    }

    // ================================================================= buttons
    private fun toggleAudioOnly() {
        audioOnly = !audioOnly
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly).build()
        phonesBtn.setColorFilter(if (audioOnly) accent else Color.WHITE)
        showHud(if (audioOnly) "Audio only" else "Video on")
    }

    private fun rotateScreen() {
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        requestedOrientation = if (portrait) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }

    private fun cycleAspect() {
        val modes = intArrayOf(AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_FILL,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
        val names = arrayOf("Fit", "Stretch", "Crop")
        val n = (modes.indexOf(playerView.resizeMode) + 1) % modes.size
        playerView.resizeMode = modes[n]; showHud(names[n])
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= 26) enterPictureInPictureMode(PictureInPictureParams.Builder().build())
    }

    private fun takeScreenshot() {
        val sv = playerView.videoSurfaceView as? SurfaceView
        if (sv == null || sv.width == 0 || sv.height == 0) { showHud("Screenshot unavailable"); return }
        if (Build.VERSION.SDK_INT < 29) { showHud("Screenshots need Android 10+"); return }
        val bmp = Bitmap.createBitmap(sv.width, sv.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(sv, bmp, { result ->
            if (result == PixelCopy.SUCCESS) saveBitmap(bmp) else showHud("Screenshot failed")
        }, handler)
    }

    @RequiresApi(29)
    private fun saveBitmap(bmp: Bitmap) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Alleria_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AlleriaFilmotheque")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            showHud("Screenshot saved")
        } else showHud("Couldn't save screenshot")
    }

    private fun toggleLock() {
        locked = !locked
        if (locked) { hideControls(); showLockOverlay(); showHud("Locked") }
        else { lockOverlay.visibility = View.GONE; showControls(); showHud("Unlocked") }
    }

    private fun showLockOverlay() {
        lockOverlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideLockOverlay); handler.postDelayed(hideLockOverlay, 2000)
    }

    // ================================================================= gestures
    private fun setupGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                if (d.scaleFactor > 1.03f && playerView.resizeMode != AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
                    playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM; showHud("Crop")
                } else if (d.scaleFactor < 0.97f && playerView.resizeMode != AspectRatioFrameLayout.RESIZE_MODE_FIT) {
                    playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; showHud("Fit")
                }
                return true
            }
        })
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (locked) showLockOverlay()
                else if (controlsVisible) hideControls() else showControls()
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
                if (longPressing) { longPressing = false; player.setPlaybackSpeed(speed); hud.visibility = View.GONE }
            }
            true
        }
    }

    private fun seekBy(ms: Long) =
        player.seekTo((player.currentPosition + ms).coerceIn(0L, player.duration.coerceAtLeast(0L)))

    private fun showHud(text: String, hideAfter: Long = 800) {
        hud.text = text; hud.visibility = View.VISIBLE
        handler.removeCallbacks(hideHud); handler.postDelayed(hideHud, hideAfter)
    }

    private fun fmt(ms: Long): String {
        val s = (ms.coerceAtLeast(0L)) / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
        else "%02d:%02d".format(s / 60, s % 60)
    }

    // ================================================================= reporting & lifecycle
    private fun report(kind: String) {
        if (sourceId.isEmpty()) return
        val id = itemId; val src = sourceId; val sid = sessionId
        val pos = player.currentPosition; val paused = !player.isPlaying; val tc = isTranscoding
        lifecycleScope.launch { Api.report(kind, id, src, pos, paused, tc, sid) }
    }

    override fun onUserLeaveHint() {
        if (player.isPlaying) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        if (isInPip) { hideControls(); lockOverlay.visibility = View.GONE }
        else if (!locked) showControls()
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
