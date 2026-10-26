package com.example.myjelly

import android.app.AlertDialog
import android.app.Dialog
import android.app.PictureInPictureParams
import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
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
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/**
 * Player styled after the Alleria mockup: top bar, big centre controls (10s back / play / 10s forward),
 * red seek bar, and a glass panel with Lock, Audio & Subtitles, Playback Speed and More.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private val accent = 0xFFE50914.toInt()

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var hud: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var controls: FrameLayout
    private lateinit var lockOverlay: ImageView
    private lateinit var titleView: TextView
    private lateinit var seek: SeekBar
    private lateinit var curTime: TextView
    private lateinit var durTime: TextView
    private lateinit var playBtn: ImageView
    private lateinit var audio: AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private var itemId = ""
    private var offlinePath: String? = null
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
    private var autoPlayNext = true
    private var speed = 1f

    private enum class Mode { NONE, SEEK, BRIGHT, VOLUME }
    private var mode = Mode.NONE
    private var startPos = 0L
    private var seekTarget = 0L
    private var brightness = 0.5f
    private var volF = 0f

    private val isTranscoding get() = offlinePath == null && (quality != Quality.ORIGINAL || audioMode != AudioMode.ORIGINAL)
    private val hasNext get() = siblingIndex >= 0 && siblingIndex < siblings.size - 1
    private val hasPrev get() = siblingIndex > 0

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
        offlinePath = intent.getStringExtra("file")
        audio = getSystemService(AUDIO_SERVICE) as AudioManager

        val prefs = getSharedPreferences("player", MODE_PRIVATE)
        quality = runCatching { Quality.valueOf(prefs.getString("quality", "ORIGINAL") ?: "ORIGINAL") }
            .getOrDefault(Quality.ORIGINAL)
        audioMode = runCatching { AudioMode.valueOf(prefs.getString("audio", "ORIGINAL") ?: "ORIGINAL") }
            .getOrDefault(AudioMode.ORIGINAL)
        speed = prefs.getFloat("speed", 1f)
        autoPlayNext = prefs.getBoolean("autoplay", true)

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
        player.setPlaybackSpeed(speed)

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

    // ================================================================= UI building
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun circleBg() = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x99181E30.toInt()) }

    private fun icon(res: Int, size: Int = 48, pad: Int = 12) = ImageView(this).apply {
        setImageResource(res); setColorFilter(Color.WHITE)
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    /** 10-second skip button: a circular arrow with "10" in the middle. */
    private fun skipButton(forward: Boolean): FrameLayout = FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(58), dp(58))
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_replay); setColorFilter(Color.WHITE)
            scaleX = if (forward) -1f else 1f
        }, FrameLayout.LayoutParams(-1, -1))
        addView(TextView(context).apply {
            text = "10"; setTextColor(Color.WHITE); textSize = 12f
            setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
    }

    private fun panelItem(res: Int, label: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        setPadding(0, dp(10), 0, dp(10))
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        addView(ImageView(context).apply { setImageResource(res); setColorFilter(Color.WHITE) },
            LinearLayout.LayoutParams(dp(24), dp(24)))
        addView(TextView(context).apply {
            text = label; setTextColor(0xFFCCCCCC.toInt()); textSize = 11f
            gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0)
        })
    }

    private fun buildUi() {
        // ---- top bar
        val backBtn = icon(R.drawable.ic_back)
        titleView = TextView(this).apply {
            text = intent.getStringExtra("title") ?: ""
            setTextColor(Color.WHITE); textSize = 17f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(4), 0, dp(8), 0); gravity = Gravity.CENTER_VERTICAL
        }
        val moreTop = icon(R.drawable.ic_more)
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(18))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0))
            addView(backBtn)
            addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
            addView(moreTop)
        }

        // ---- centre controls
        val rewBtn = skipButton(false)
        playBtn = icon(R.drawable.ic_pause, 76, 14)
        val fwdBtn = skipButton(true)
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(rewBtn, LinearLayout.LayoutParams(dp(58), dp(58)).apply { rightMargin = dp(44) })
            addView(playBtn, LinearLayout.LayoutParams(dp(76), dp(76)))
            addView(fwdBtn, LinearLayout.LayoutParams(dp(58), dp(58)).apply { leftMargin = dp(44) })
        }

        // ---- seek bar + times
        curTime = TextView(this).apply { text = "00:00"; setTextColor(Color.WHITE); textSize = 13f }
        durTime = TextView(this).apply { text = "--:--"; setTextColor(Color.WHITE); textSize = 13f }
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
        val timesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), 0, dp(20), dp(8))
            addView(curTime, LinearLayout.LayoutParams(0, -2, 1f))
            addView(durTime)
        }

        // ---- glass action panel
        val lockItem = panelItem(R.drawable.ic_lock_open, "Lock")
        val audioItem = panelItem(R.drawable.ic_subtitles, "Audio &\nSubtitles")
        val speedItem = panelItem(R.drawable.ic_schedule, "Playback\nSpeed")
        val moreItem = panelItem(R.drawable.ic_more_horiz, "More")
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(0xCC141418.toInt()); cornerRadius = dp(18).toFloat(); setStroke(dp(1), 0x33FFFFFF)
            }
            addView(lockItem); addView(audioItem); addView(speedItem); addView(moreItem)
        }
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(28), 0, 0)
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0, 0xDD000000.toInt()))
            addView(seek, LinearLayout.LayoutParams(-1, dp(32)).apply { setMargins(dp(8), 0, dp(8), 0) })
            addView(timesRow)
            addView(panel, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(4), dp(16), dp(12)) })
        }

        controls = FrameLayout(this).apply {
            addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(center, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }

        hud = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 20f; setPadding(40, 24, 40, 24)
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
        act(moreTop) { showMoreSheet() }
        act(rewBtn) { seekBy(-10_000); showHud("-10s") }
        act(fwdBtn) { seekBy(10_000); showHud("+10s") }
        act(playBtn) { if (player.isPlaying) player.pause() else player.play() }
        act(lockItem) { toggleLock() }
        act(audioItem) { showAudioSubsSheet() }
        act(speedItem) { showSpeedSheet() }
        act(moreItem) { showMoreSheet() }
        lockOverlay.setOnClickListener { toggleLock() }
    }

    private fun act(v: View, f: () -> Unit) = v.setOnClickListener { scheduleHide(); f() }

    // ---- bottom sheets (dark glass, rounded top corners)
    private fun sheet(title: String, content: (LinearLayout, Dialog) -> Unit) {
        val d = Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar)
        val r = dp(22).toFloat()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(22))
            background = GradientDrawable().apply {
                setColor(0xF2141418.toInt()); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = title; setTextColor(Color.WHITE); textSize = 18f; setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(context).apply {
                text = "✕"; setTextColor(Color.WHITE); textSize = 20f
                setPadding(dp(12), dp(6), dp(4), dp(6)); setOnClickListener { d.dismiss() }
            })
        }
        box.addView(head)
        content(box, d)
        d.setContentView(ScrollView(this).apply { addView(box) })
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        d.show()
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text; setTextColor(Color.WHITE); textSize = 15f
        setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(16), 0, dp(4))
    }

    private fun radioGroup(base: Int, labels: List<String>, checked: Int, onPick: (Int) -> Unit): RadioGroup =
        RadioGroup(this).apply {
            labels.forEachIndexed { i, l ->
                addView(RadioButton(context).apply {
                    id = base + i; text = l; setTextColor(Color.WHITE); textSize = 15f
                    buttonTintList = ColorStateList.valueOf(accent); setPadding(dp(8), dp(10), 0, dp(10))
                })
            }
            if (checked >= 0) check(base + checked)
            setOnCheckedChangeListener { _, id -> onPick(id - base) }
        }

    private fun sheetRow(label: String, value: String, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(14), 0, dp(14))
        addView(TextView(context).apply { text = label; setTextColor(Color.WHITE); textSize = 16f },
            LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(context).apply { text = value; setTextColor(0xFF9A9AA0.toInt()); textSize = 14f })
        setOnClickListener { onClick() }
    }

    private fun trackLabel(g: Tracks.Group, i: Int): String {
        val f = g.getTrackFormat(0)
        val lang = f.language?.let { Locale(it).displayLanguage }?.takeIf { it.isNotBlank() }
        return listOfNotNull(f.label, lang).distinct().joinToString(" · ").ifEmpty { "Track ${i + 1}" }
    }

    private fun showAudioSubsSheet() = sheet("Audio & Subtitles") { box, _ ->
        val audioGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
        val textGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
        box.addView(sectionLabel("Audio"))
        if (audioGroups.isEmpty()) {
            box.addView(TextView(this).apply { text = "No audio tracks"; setTextColor(0xFF9A9AA0.toInt()) })
        } else {
            val labels = audioGroups.mapIndexed { i, g -> trackLabel(g, i) }
            val sel = audioGroups.indexOfFirst { it.isSelected }.coerceAtLeast(0)
            box.addView(radioGroup(100, labels, sel) { i ->
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(audioGroups[i].mediaTrackGroup, 0)).build()
            })
        }
        box.addView(sectionLabel("Subtitles"))
        val labels = listOf("Off") + textGroups.mapIndexed { i, g -> trackLabel(g, i) }
        val textOff = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val selText = textGroups.indexOfFirst { it.isSelected }
        val checked = if (textOff || selText < 0) 0 else selText + 1
        box.addView(radioGroup(200, labels, checked) { i ->
            val b = player.trackSelectionParameters.buildUpon()
            if (i == 0) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            else b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(textGroups[i - 1].mediaTrackGroup, 0))
            player.trackSelectionParameters = b.build()
        })
        if (isTranscoding) box.addView(TextView(this).apply {
            text = "While transcoding, only one audio track is available. Choose Original quality and audio to switch tracks."
            setTextColor(0xFF777777.toInt()); textSize = 11f; setPadding(0, dp(12), 0, 0)
        })
    }

    private fun showSpeedSheet() = sheet("Playback Speed") { box, dlg ->
        val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val labels = speeds.map { speedText(it) }
        box.addView(radioGroup(100, labels, speeds.indexOfFirst { it == speed }.coerceAtLeast(0)) { i ->
            speed = speeds[i]; player.setPlaybackSpeed(speed); dlg.dismiss()
        })
    }

    private fun showQualitySheet() = sheet("Video quality") { box, dlg ->
        val all = Quality.values()
        box.addView(radioGroup(100, all.map { it.label }, quality.ordinal) { i ->
            applyChoice(all[i], audioMode); dlg.dismiss()
        })
    }

    private fun showAudioOutputSheet() = sheet("Audio output") { box, dlg ->
        val all = AudioMode.values()
        box.addView(radioGroup(100, all.map { it.label }, audioMode.ordinal) { i ->
            applyChoice(quality, all[i]); dlg.dismiss()
        })
        box.addView(TextView(this).apply {
            text = "Audio sets the maximum channels; sources with fewer channels are not upmixed."
            setTextColor(0xFF777777.toInt()); textSize = 11f; setPadding(0, dp(12), 0, 0)
        })
    }

    private fun showMoreSheet() = sheet("More") { box, dlg ->
        fun row(label: String, value: String = "", onClick: () -> Unit) {
            box.addView(sheetRow(label, value) { dlg.dismiss(); onClick() })
        }
        row("Video quality", qualityText()) { showQualitySheet() }
        row("Audio output", audioMode.label) { showAudioOutputSheet() }
        row("Aspect ratio", aspectName()) { cycleAspect() }
        row("Audio only", if (audioOnly) "On" else "Off") { toggleAudioOnly() }
        row("Repeat", if (loopOne) "On" else "Off") { toggleLoop() }
        row("Screenshot") { takeScreenshot() }
        row("Picture-in-picture") { enterPip() }
        row("Rotate screen") { rotateScreen() }
        row("Skip +90s") { seekBy(90_000); showHud("+90s") }
        if (hasPrev) row("Previous episode") { switchTo(siblings[siblingIndex - 1].id) }
        if (hasNext) row("Next episode") { switchTo(siblings[siblingIndex + 1].id) }
    }

    private fun qualityText() = if (quality == Quality.ORIGINAL) "Original" else quality.label.substringBefore(' ')
    private fun speedText(f: Float) = (if (f % 1f == 0f) f.toInt().toString() else f.toString()) + "x"

    // ================================================================= controls visibility
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

    // ================================================================= playback
    private suspend fun buildMediaItem(): MediaItem {
        val b = MediaItem.Builder().setSubtitleConfigurations(subs)
        if (!isTranscoding) {
            b.setUri(Api.streamUrl(itemId, sourceId))
        } else {
            val t = Api.transcodeUrl(itemId, sourceId, quality, audioMode, sessionId)
            sessionId = t.sessionId
            b.setUri(t.url).setMimeType(MimeTypes.APPLICATION_M3U8)
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
                    if (autoPlayNext && hasNext) switchTo(siblings[siblingIndex + 1].id) else finish()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                val http = generateSequence<Throwable>(error) { it.cause }
                    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                val detail = http?.let { " [HTTP ${it.responseCode} ${it.dataSpec.uri.lastPathSegment}]" } ?: ""
                showHud(if (isTranscoding) "Transcode failed: ${error.errorCodeName}$detail"
                        else "Playback error: ${error.errorCodeName}$detail", 8000)
            }
        })
    }

    private fun loadAndPlay() = lifecycleScope.launch {
        val file = offlinePath
        if (file != null) {
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(file))))
            player.prepare(); player.playWhenReady = true
            return@launch
        }
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

            siblings = emptyList(); siblingIndex = -1
            if (item.type == "Episode" && item.parentId != null) {
                siblings = runCatching { Api.children(item.parentId) }.getOrDefault(emptyList())
                    .filter { it.type == "Episode" }
                siblingIndex = siblings.indexOfFirst { it.id == itemId }
            }
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

    private fun applyChoice(nq: Quality, na: AudioMode) {
        if (nq == quality && na == audioMode) return
        quality = nq; audioMode = na
        getSharedPreferences("player", MODE_PRIVATE).edit()
            .putString("quality", quality.name).putString("audio", audioMode.name).apply()
        applyPlayback()
    }

    private fun applyPlayback() {
        if (sourceId.isEmpty()) return
        val pos = player.currentPosition
        showHud("Switching to ${quality.label} · Audio: ${audioMode.label}…", 2500)
        lifecycleScope.launch {
            val mi = buildMediaItem()
            player.setMediaItem(mi, pos)
            player.prepare(); player.play()
        }
    }

    // ================================================================= misc buttons
    private fun toggleAudioOnly() {
        audioOnly = !audioOnly
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly).build()
        showHud(if (audioOnly) "Audio only" else "Video on")
    }

    private fun toggleLoop() {
        loopOne = !loopOne
        player.repeatMode = if (loopOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        showHud(if (loopOne) "Repeat: on" else "Repeat: off")
    }

    private fun rotateScreen() {
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        requestedOrientation = if (portrait) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }

    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )
    private val resizeNames = arrayOf("Fit", "Stretch", "Crop")

    private fun aspectName(): String {
        val i = resizeModes.indexOfFirst { it == playerView.resizeMode }
        return resizeNames[if (i < 0) 0 else i]
    }

    private fun cycleAspect() {
        val i = resizeModes.indexOfFirst { it == playerView.resizeMode }
        val n = (i + 1) % resizeModes.size
        playerView.resizeMode = resizeModes[n]; showHud(resizeNames[n])
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
                    e.x < w / 3f -> { seekBy(-10_000); showHud("-10s") }
                    e.x > w * 2 / 3f -> { seekBy(10_000); showHud("+10s") }
                    else -> if (player.isPlaying) player.pause() else player.play()
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (locked) return
                longPressing = true; player.setPlaybackSpeed(2f); showHud("2x", 100_000)
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
                        showHud("Brightness ${(brightness * 100).toInt()}%")
                    }
                    Mode.VOLUME -> {
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        volF = (volF + dy / playerView.height * max * 1.5f).coerceIn(0f, max.toFloat())
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, volF.toInt(), 0)
                        showHud("Volume ${(volF / max * 100).toInt()}%")
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
        val s = ms.coerceAtLeast(0L) / 1000
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
        if (!::player.isInitialized) { super.onDestroy(); return }
        if (sourceId.isNotEmpty()) {
            val pos = player.currentPosition; val id = itemId; val src = sourceId
            val tc = isTranscoding; val sid = sessionId
            CoroutineScope(Dispatchers.IO).launch { Api.report("stop", id, src, pos, true, tc, sid) }
        }
        player.release()
        super.onDestroy()
    }
}
