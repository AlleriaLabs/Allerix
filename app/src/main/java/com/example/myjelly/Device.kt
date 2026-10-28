package com.example.myjelly

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities

data class VideoCodecCap(val mime: String, val label: String, val hwMaxHeight: Int, val anyMaxHeight: Int)

data class DeviceCaps(
    val displayWidth: Int, val displayHeight: Int, val maxRefresh: Float,
    val hdrTypes: List<String>, val video: List<VideoCodecCap>, val audioCodecs: List<String>
)

data class NetInfo(val label: String, val metered: Boolean, val cellular: Boolean)
data class BatteryInfo(val percent: Int, val charging: Boolean, val powerSave: Boolean, val thermal: Int)
data class AudioRoute(val label: String, val maxChannels: Int)
data class AutoPolicy(val maxHeight: Int, val maxBitrate: Int, val notes: List<String>)

/** Reads what this phone can do (decoders, display, audio output, network, battery) so playback can adapt. */
@OptIn(UnstableApi::class)
object Device {
    private var cached: DeviceCaps? = null

    private val jfName = mapOf(
        "video/avc" to "h264", "video/hevc" to "hevc",
        "video/x-vnd.on2.vp9" to "vp9", "video/av01" to "av1"
    )

    fun heightLabel(h: Int): String = when {
        h >= 4000 -> "8K"
        h >= 2000 -> "4K"
        h >= 1400 -> "1440p"
        h >= 1000 -> "1080p"
        h >= 700 -> "720p"
        h > 0 -> "${h}p"
        else -> "not supported"
    }

    fun caps(ctx: Context): DeviceCaps {
        val c = cached
        if (c != null) return c
        val d = detect(ctx.applicationContext)
        cached = d
        return d
    }

    /** Codec name used by Jellyfin plus the tallest picture this phone should direct-play for it. */
    fun directPlayVideo(c: DeviceCaps): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        for (v in c.video) {
            val n = jfName[v.mime] ?: continue
            val h = when {
                v.hwMaxHeight > 0 -> v.hwMaxHeight
                (n == "h264" || n == "vp9") -> minOf(v.anyMaxHeight, 1080)
                else -> 0
            }
            if (h > 0) out.add(n to h)
        }
        if (out.none { it.first == "h264" }) out.add("h264" to 1080)
        return out
    }

    fun bestHeight(c: DeviceCaps): Int = directPlayVideo(c).maxOf { it.second }.coerceAtLeast(720)

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
        else !(info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android."))

    private fun decoders(): List<MediaCodecInfo> = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
    } catch (e: Exception) { emptyList() }

    private fun videoCaps(infos: List<MediaCodecInfo>): List<VideoCodecCap> {
        val types = listOf(
            "video/avc" to "H.264", "video/hevc" to "HEVC", "video/x-vnd.on2.vp9" to "VP9",
            "video/av01" to "AV1", "video/dolby-vision" to "Dolby Vision"
        )
        val sizes = listOf(4320 to 7680, 2160 to 3840, 1440 to 2560, 1080 to 1920, 720 to 1280)
        return types.map { (mime, label) ->
            var hw = 0
            var any = 0
            for (info in infos) {
                if (!info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) continue
                val vc = try { info.getCapabilitiesForType(mime).videoCapabilities } catch (e: Exception) { null }
                if (vc == null) continue
                var best = 0
                for ((h, w) in sizes) { if (vc.isSizeSupported(w, h)) { best = h; break } }
                if (best > any) any = best
                if (isHardware(info) && best > hw) hw = best
            }
            VideoCodecCap(mime, label, hw, any)
        }
    }

    private fun audioCodecs(ctx: Context, infos: List<MediaCodecInfo>): List<String> {
        val map = listOf(
            "audio/mp4a-latm" to "aac", "audio/mpeg" to "mp3", "audio/opus" to "opus", "audio/flac" to "flac",
            "audio/vorbis" to "vorbis", "audio/ac3" to "ac3", "audio/eac3" to "eac3", "audio/vnd.dts" to "dts",
            "audio/true-hd" to "truehd", "audio/alac" to "alac"
        )
        val out = ArrayList<String>()
        for ((mime, name) in map) {
            if (infos.any { i -> i.supportedTypes.any { it.equals(mime, ignoreCase = true) } }) out.add(name)
        }
        try {
            val ac = AudioCapabilities.getCapabilities(ctx)
            if (ac.supportsEncoding(C.ENCODING_AC3) && !out.contains("ac3")) out.add("ac3")
            if (ac.supportsEncoding(C.ENCODING_E_AC3) && !out.contains("eac3")) out.add("eac3")
            if (ac.supportsEncoding(C.ENCODING_DTS) && !out.contains("dts")) out.add("dts")
            if (ac.supportsEncoding(C.ENCODING_DOLBY_TRUEHD) && !out.contains("truehd")) out.add("truehd")
        } catch (e: Exception) { /* passthrough info unavailable */ }
        out.add("pcm_s16le"); out.add("pcm_s24le")
        return out
    }

    @Suppress("DEPRECATION")
    private fun detect(ctx: Context): DeviceCaps {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d = wm.defaultDisplay
        val dm = DisplayMetrics()
        d.getRealMetrics(dm)
        var rate = d.refreshRate
        try { for (m in d.supportedModes) if (m.refreshRate > rate) rate = m.refreshRate } catch (e: Exception) { }
        val hdr = ArrayList<String>()
        try {
            val types = d.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
            for (t in types) {
                when (t) {
                    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> hdr.add("Dolby Vision")
                    Display.HdrCapabilities.HDR_TYPE_HDR10 -> hdr.add("HDR10")
                    Display.HdrCapabilities.HDR_TYPE_HLG -> hdr.add("HLG")
                    Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> hdr.add("HDR10+")
                    else -> {}
                }
            }
        } catch (e: Exception) { }
        val infos = decoders()
        return DeviceCaps(
            maxOf(dm.widthPixels, dm.heightPixels), minOf(dm.widthPixels, dm.heightPixels), rate,
            hdr, videoCaps(infos), audioCodecs(ctx, infos)
        )
    }

    fun network(ctx: Context): NetInfo {
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val n = cm.activeNetwork ?: return NetInfo("Offline", false, false)
            val c = cm.getNetworkCapabilities(n) ?: return NetInfo("Unknown", false, false)
            val cell = c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            val wifi = c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val eth = c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            val metered = !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            val name = when {
                wifi -> "Wi-Fi"
                eth -> "Ethernet"
                cell -> "Mobile data"
                else -> "Network"
            }
            return NetInfo(if (metered) "$name (metered)" else name, metered, cell)
        } catch (e: Exception) {
            return NetInfo("Unknown", false, false)
        }
    }

    fun battery(ctx: Context): BatteryInfo {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermal = if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else 0
        return BatteryInfo(pct, charging, pm.isPowerSaveMode, thermal)
    }

    fun audioRoute(ctx: Context): AudioRoute {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val devs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            fun find(vararg types: Int): AudioDeviceInfo? = devs.firstOrNull { types.contains(it.type) }
            fun ch(d: AudioDeviceInfo, fallback: Int): Int = d.channelCounts.maxOrNull() ?: fallback
            find(AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC)?.let { return AudioRoute("HDMI / TV", ch(it, 6)) }
            find(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET)?.let { return AudioRoute("USB audio", ch(it, 2)) }
            find(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)?.let { return AudioRoute("Bluetooth", 2) }
            find(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET)?.let { return AudioRoute("Wired headphones", 2) }
        } catch (e: Exception) { }
        return AudioRoute("Phone speaker", 2)
    }

    /** Auto mode: never ask for more than this phone can decode, and ease off on mobile data / low battery / heat. */
    fun policy(ctx: Context, c: DeviceCaps, saveData: Boolean): AutoPolicy {
        var h = bestHeight(c)
        var br = 120_000_000
        val notes = ArrayList<String>()
        val net = network(ctx)
        val bat = battery(ctx)
        if (saveData && (net.cellular || net.metered)) {
            br = minOf(br, 8_000_000); h = minOf(h, 1080); notes.add("saving mobile data")
        }
        if ((bat.percent in 0..15 && !bat.charging) || bat.powerSave) {
            br = minOf(br, 12_000_000); h = minOf(h, 1080); notes.add("battery saver")
        }
        if (bat.thermal >= 3) {
            h = minOf(h, 720); notes.add("phone is hot")
        }
        return AutoPolicy(h, br, notes)
    }

    fun describe(ctx: Context, playing: String?): List<Pair<String, String>> {
        val c = caps(ctx)
        val net = network(ctx)
        val bat = battery(ctx)
        val route = audioRoute(ctx)
        val saveData = ctx.getSharedPreferences("player", Context.MODE_PRIVATE).getBoolean("save_data", true)
        val pol = policy(ctx, c, saveData)
        val rows = ArrayList<Pair<String, String>>()
        if (playing != null) rows.add("Now playing" to playing)
        rows.add("Phone" to "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
        rows.add("Display" to "${c.displayWidth}×${c.displayHeight} · up to ${c.maxRefresh.toInt()} Hz")
        rows.add("HDR" to (if (c.hdrTypes.isEmpty()) "Not supported by the display" else c.hdrTypes.joinToString(", ")))
        rows.add("Video decoders" to c.video.joinToString("\n") { v ->
            v.label + ": " + when {
                v.hwMaxHeight > 0 -> heightLabel(v.hwMaxHeight) + " (hardware)"
                v.anyMaxHeight > 0 -> heightLabel(v.anyMaxHeight) + " (software)"
                else -> "not supported"
            }
        })
        rows.add("Audio codecs" to c.audioCodecs.filter { !it.startsWith("pcm") }.joinToString(", ").uppercase())
        rows.add("Audio output" to "${route.label} · up to ${route.maxChannels} channels")
        rows.add("Network" to net.label)
        val batText = (if (bat.percent >= 0) "${bat.percent}%" else "unknown") +
            (if (bat.charging) " · charging" else "") + (if (bat.powerSave) " · battery saver" else "") +
            (if (bat.thermal >= 3) " · hot" else "")
        rows.add("Battery" to batText)
        rows.add("Auto mode limit" to "up to ${heightLabel(pol.maxHeight)} · ${pol.maxBitrate / 1_000_000} Mbps" +
            (if (pol.notes.isEmpty()) "" else " (" + pol.notes.joinToString(", ") + ")"))
        return rows
    }
}
