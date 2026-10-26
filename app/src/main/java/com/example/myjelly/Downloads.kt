package com.example.myjelly

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class DownloadRec(
    val id: String, val title: String, val file: String, val dmId: Long, val runtimeTicks: Long = 0L
)

data class DlState(val status: Int, val done: Long, val total: Long)

/** Offline downloads using Android's DownloadManager (files live in the app's private movie folder). */
object Downloads {
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(DownloadRec.serializer())

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("downloads", Context.MODE_PRIVATE)

    fun list(ctx: Context): List<DownloadRec> = runCatching {
        json.decodeFromString(ser, prefs(ctx).getString("list", "[]") ?: "[]")
    }.getOrDefault(emptyList())

    private fun save(ctx: Context, l: List<DownloadRec>) {
        prefs(ctx).edit().putString("list", json.encodeToString(ser, l)).apply()
    }

    fun state(ctx: Context, rec: DownloadRec): DlState {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val c = dm.query(DownloadManager.Query().setFilterById(rec.dmId))
        c.use {
            if (it.moveToFirst()) {
                return DlState(
                    it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                )
            }
        }
        val f = File(rec.file)
        return if (f.exists() && f.length() > 0) DlState(DownloadManager.STATUS_SUCCESSFUL, f.length(), f.length())
        else DlState(-1, 0L, 0L)
    }

    fun remove(ctx: Context, rec: DownloadRec) {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        runCatching { dm.remove(rec.dmId) }
        runCatching { File(rec.file).delete() }
        save(ctx, list(ctx).filter { it.id != rec.id })
    }

    /** Returns a short message for the user. */
    suspend fun start(ctx: Context, item: Item): String {
        try {
            val existing = list(ctx).firstOrNull { it.id == item.id }
            if (existing != null) {
                val st = state(ctx, existing).status
                if (st != DownloadManager.STATUS_FAILED && st != -1) return "Already in Downloads"
                remove(ctx, existing)
            }
            val full = Api.item(item.id)
            val container = full.mediaSources?.firstOrNull()?.container ?: "mkv"
            val ext = when {
                container.contains("mp4") -> "mp4"
                container.contains("matroska") || container.contains("mkv") -> "mkv"
                container.contains("webm") -> "webm"
                else -> container.substringBefore(',')
            }
            val title = if (item.seriesName != null) "${item.seriesName} · ${item.name}" else item.name
            val name = "alleria_${item.id}.$ext"
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: return "Storage unavailable"
            val target = File(dir, name)
            if (target.exists()) target.delete()
            val req = DownloadManager.Request(Uri.parse("${Api.base}/Items/${item.id}/Download?api_key=${Api.token}"))
                .setTitle(title)
                .setDescription("Alleria Filmotheque")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_MOVIES, name)
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val dmId = dm.enqueue(req)
            save(ctx, list(ctx) + DownloadRec(item.id, title, target.absolutePath, dmId, full.runTimeTicks ?: item.runTimeTicks ?: 0L))
            return "Download started"
        } catch (e: Exception) {
            return "Couldn't start download (${e.message})"
        }
    }
}
