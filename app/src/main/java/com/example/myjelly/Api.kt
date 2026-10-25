package com.example.myjelly

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object Api {
    const val SERVER = "https://flix.alleriafilmotheque.fyi"
    var base = SERVER
    var token = ""
    var userId = ""
    var username = ""

    /** Optional: URL that receives show requests as JSON (e.g. a Discord webhook). Empty = use the share sheet. */
    const val REQUEST_WEBHOOK = ""

    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("alleria", Context.MODE_PRIVATE)
        base = p.getString("server", SERVER) ?: SERVER
        token = p.getString("token", "") ?: ""
        userId = p.getString("user", "") ?: ""
        username = p.getString("username", "") ?: ""
    }

    private fun save(ctx: Context) = ctx.getSharedPreferences("alleria", Context.MODE_PRIVATE).edit()
        .putString("server", base).putString("token", token).putString("user", userId).putString("username", username).apply()

    fun logout(ctx: Context) { token = ""; userId = ""; username = ""; save(ctx) }

    fun normalize(raw: String): String {
        var s = raw.trim().trimEnd('/')
        if (s.isNotEmpty() && !s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        return s
    }

    fun setServer(ctx: Context, raw: String) { base = normalize(raw); save(ctx) }

    /** Checks a server URL without logging in; returns "Name (vX.Y.Z)". */
    suspend fun ping(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(normalize(url) + "/System/Info/Public").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val info = json.decodeFromString<PublicInfo>(r.body?.string().orEmpty())
            "${info.name} (v${info.version})"
        }
    }

    private fun auth(): String {
        val s = "MediaBrowser Client=\"Alleria Filmotheque\", Device=\"Android\", DeviceId=\"alleria-android\", Version=\"1.0\""
        return if (token.isNotEmpty()) "$s, Token=\"$token\"" else s
    }

    private suspend fun call(method: String, path: String, body: String? = null): String =
        withContext(Dispatchers.IO) {
            val rb = if (method == "GET") null
            else (body ?: "").toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(base + path).header("Authorization", auth())
                .method(method, rb).build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                r.body?.string().orEmpty()
            }
        }

    suspend fun login(ctx: Context, user: String, pw: String) {
        token = ""
        val body = buildJsonObject { put("Username", user); put("Pw", pw) }.toString()
        val r = json.decodeFromString<AuthResult>(call("POST", "/Users/AuthenticateByName", body))
        token = r.token; userId = r.user.id; username = user
        save(ctx)
    }

    // ---- account ----
    suspend fun forgotPassword(user: String): ForgotResult {
        val body = buildJsonObject { put("EnteredUsername", user) }.toString()
        return json.decodeFromString(call("POST", "/Users/ForgotPassword", body))
    }

    suspend fun redeemPin(pin: String): PinResult {
        val body = buildJsonObject { put("Pin", pin) }.toString()
        return json.decodeFromString(call("POST", "/Users/ForgotPassword/Pin", body))
    }

    suspend fun changePassword(currentPw: String, newPw: String) {
        val body = buildJsonObject { put("CurrentPw", currentPw); put("NewPw", newPw) }.toString()
        call("POST", "/Users/$userId/Password", body)
    }

    // ---- show requests ----
    fun requestText(title: String, year: String, category: String): String =
        "New request from ${username.ifEmpty { "a user" }}: $title" +
            (if (year.isNotEmpty()) " ($year)" else "") + " - $category"

    /** Returns true if delivered to REQUEST_WEBHOOK, false if no webhook is configured. */
    suspend fun sendRequest(title: String, year: String, category: String): Boolean {
        if (REQUEST_WEBHOOK.isEmpty()) return false
        val body = buildJsonObject {
            put("content", requestText(title, year, category))
            put("title", title); put("year", year); put("category", category); put("requestedBy", username)
        }.toString()
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(REQUEST_WEBHOOK)
                .post(body.toRequestBody("application/json".toMediaType())).build()
            http.newCall(req).execute().use { r -> if (!r.isSuccessful) error("HTTP ${r.code}") }
        }
        return true
    }

    suspend fun views(): List<Item> =
        json.decodeFromString<ItemsResult>(call("GET", "/Users/$userId/Views")).items

    suspend fun resume(): List<Item> = json.decodeFromString<ItemsResult>(
        call("GET", "/Users/$userId/Items/Resume?limit=12&mediaTypes=Video&fields=Overview")).items

    /** Random movies/series that have backdrops, for the home carousel. */
    suspend fun featured(): List<Item> = json.decodeFromString<ItemsResult>(
        call("GET", "/Items?userId=$userId&recursive=true&includeItemTypes=Movie,Series&imageTypes=Backdrop&sortBy=Random&limit=7&fields=Overview")
    ).items

    suspend fun latest(parentId: String): List<Item> = json.decodeFromString<List<Item>>(
        call("GET", "/Users/$userId/Items/Latest?parentId=$parentId&limit=16&fields=Overview"))

    suspend fun children(parentId: String): List<Item> = json.decodeFromString<ItemsResult>(
        call("GET", "/Items?userId=$userId&parentId=$parentId&sortBy=ParentIndexNumber,IndexNumber,SortName&fields=Overview")
    ).items

    suspend fun item(id: String): Item =
        json.decodeFromString(call("GET", "/Users/$userId/Items/$id"))

    /** Episode to play for a Series (next up, else first) or a Season (first). */
    suspend fun nextEpisode(id: String, type: String): Item? {
        if (type == "Series") {
            val up = runCatching {
                json.decodeFromString<ItemsResult>(call("GET", "/Shows/NextUp?userId=$userId&seriesId=$id&limit=1")).items
            }.getOrNull()
            if (!up.isNullOrEmpty()) return up.first()
        }
        return json.decodeFromString<ItemsResult>(call("GET",
            "/Items?userId=$userId&parentId=$id&recursive=true&includeItemTypes=Episode&sortBy=ParentIndexNumber,IndexNumber&limit=1")
        ).items.firstOrNull()
    }

    // ---- images ----
    fun poster(i: Item) = "$base/Items/${i.id}/Images/Primary?maxHeight=500&quality=90"
    fun heroImage(i: Item): String =
        if (i.type == "Episode" && i.seriesId != null) "$base/Items/${i.seriesId}/Images/Primary?maxHeight=900&quality=90"
        else "$base/Items/${i.id}/Images/Primary?maxHeight=900&quality=90"
    fun landscape(i: Item): String = when {
        !i.backdropTags.isNullOrEmpty() -> "$base/Items/${i.id}/Images/Backdrop?maxWidth=600&quality=85"
        i.imageTags?.containsKey("Thumb") == true -> "$base/Items/${i.id}/Images/Thumb?maxWidth=600"
        else -> "$base/Items/${i.id}/Images/Primary?maxWidth=600"
    }
    /** Tiny image; upscaled + blurred in the UI for the home-page background. */
    fun blurBg(i: Item): String =
        if (!i.backdropTags.isNullOrEmpty()) "$base/Items/${i.id}/Images/Backdrop?maxWidth=96&quality=40"
        else "$base/Items/${i.id}/Images/Primary?maxWidth=96&quality=40"
    fun backdrop(i: Item): String = when {
        !i.backdropTags.isNullOrEmpty() -> "$base/Items/${i.id}/Images/Backdrop?maxWidth=1280&quality=85"
        !i.parentBackdropItemId.isNullOrEmpty() -> "$base/Items/${i.parentBackdropItemId}/Images/Backdrop?maxWidth=1280&quality=85"
        else -> "$base/Items/${i.id}/Images/Primary?maxWidth=1280"
    }

    // ---- playback ----
    fun streamUrl(id: String, sourceId: String) =
        "$base/Videos/$id/stream?static=true&MediaSourceId=$sourceId&api_key=$token"

    /** HLS transcode URL honoring the chosen quality (max height/bitrate) and audio channel layout. */
    fun hlsUrl(id: String, sourceId: String, q: Quality, a: AudioMode, sessionId: String): String {
        val sb = StringBuilder("$base/Videos/$id/master.m3u8")
        sb.append("?MediaSourceId=$sourceId&DeviceId=alleria-android&PlaySessionId=$sessionId&api_key=$token")
        sb.append("&SegmentContainer=ts&MinSegments=2&BreakOnNonKeyFrames=true&AudioCodec=aac")
        if (q == Quality.ORIGINAL) sb.append("&VideoCodec=h264,hevc&AllowVideoStreamCopy=true")
        else sb.append("&VideoCodec=h264&AllowVideoStreamCopy=false&MaxHeight=${q.height}&VideoBitrate=${q.videoBitrate}")
        if (a == AudioMode.ORIGINAL) sb.append("&AllowAudioStreamCopy=true")
        else sb.append("&AllowAudioStreamCopy=false&MaxAudioChannels=${a.channels}" +
            "&TranscodingMaxAudioChannels=${a.channels}&AudioBitrate=${a.bitrate}")
        return sb.toString()
    }

    fun subtitleUrl(id: String, sourceId: String, index: Int) =
        "$base/Videos/$id/$sourceId/Subtitles/$index/Stream.srt?api_key=$token"

    /** kind: "start" | "progress" | "stop" */
    suspend fun report(kind: String, itemId: String, sourceId: String, posMs: Long, paused: Boolean,
                       transcoding: Boolean = false, sessionId: String = "") {
        val path = when (kind) {
            "start" -> "/Sessions/Playing"
            "progress" -> "/Sessions/Playing/Progress"
            else -> "/Sessions/Playing/Stopped"
        }
        val body = buildJsonObject {
            put("ItemId", itemId); put("MediaSourceId", sourceId)
            put("PositionTicks", posMs * 10_000); put("IsPaused", paused)
            put("PlayMethod", if (transcoding) "Transcode" else "DirectPlay")
            if (sessionId.isNotEmpty()) put("PlaySessionId", sessionId)
        }.toString()
        runCatching { call("POST", path, body) }
    }
}
