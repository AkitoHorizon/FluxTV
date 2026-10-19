package com.flux.tv.data

import android.net.Uri
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import com.flux.tv.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.URLEncoder

/**
 * Client de l'API Xtream Codes (player_api.php).
 * - Les grosses listes sont mises en cache sur disque (démarrage instantané).
 * - Le JSON est lu en flux (JsonReader) : peu de mémoire, même avec 50 000 entrées.
 */
class XtreamApi(val server: String, user: String, pass: String) {

    private val http = App.http
    private val base = "$server/player_api.php?username=${URLEncoder.encode(user, "UTF-8")}" +
        "&password=${URLEncoder.encode(pass, "UTF-8")}"
    private val u = Uri.encode(user)
    private val p = Uri.encode(pass)

    fun liveUrl(id: String) = "$server/live/$u/$p/$id.${Prefs.liveFormat}"
    fun movieUrl(id: String, ext: String) = "$server/movie/$u/$p/$id.$ext"
    fun episodeUrl(id: String, ext: String) = "$server/series/$u/$p/$id.$ext"

    // ------------------------------------------------------------------ réseau

    private fun fetch(url: String): String =
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Erreur serveur (HTTP ${r.code})")
            r.body?.string() ?: ""
        }

    private fun cached(action: String): File {
        val dir = File(App.instance.cacheDir, "api").apply { mkdirs() }
        val f = File(dir, "$action.json")
        val fresh = f.exists() && f.length() > 2 &&
            System.currentTimeMillis() - f.lastModified() < CACHE_TTL
        if (fresh) return f
        return try {
            val tmp = File(dir, "$action.tmp")
            http.newCall(Request.Builder().url("$base&action=$action").build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Erreur serveur (HTTP ${r.code})")
                val body = r.body ?: throw IOException("Réponse vide")
                tmp.outputStream().buffered(1 shl 16).use { out -> body.byteStream().copyTo(out, 1 shl 16) }
            }
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) throw IOException("Écriture du cache impossible")
            f
        } catch (e: IOException) {
            // Réseau en panne : on se rabat sur l'ancien cache s'il existe
            if (f.exists() && f.length() > 2) f else throw e
        }
    }

    // ------------------------------------------------------------------ compte

    suspend fun auth(): AccountInfo = withContext(Dispatchers.IO) {
        val o = try {
            JSONObject(fetch(base))
        } catch (e: JSONException) {
            throw IOException("Ce serveur ne répond pas comme un serveur Xtream Codes")
        }
        val ui = o.optJSONObject("user_info") ?: throw IOException("Réponse invalide du serveur")
        if (ui.optInt("auth", 0) != 1) throw IOException("Identifiant ou mot de passe incorrect")
        AccountInfo(
            ui.str("status") ?: "",
            ui.str("exp_date")?.toLongOrNull(),
            ui.str("active_cons") ?: "?",
            ui.str("max_connections") ?: "?",
        )
    }

    // ------------------------------------------------------------------ listes

    suspend fun categories(kind: Kind): List<Category> = withContext(Dispatchers.IO) {
        val action = when (kind) {
            Kind.LIVE -> "get_live_categories"
            Kind.MOVIE -> "get_vod_categories"
            Kind.SERIES -> "get_series_categories"
        }
        val out = ArrayList<Category>()
        readArray(cached(action)) { m ->
            out.add(Category(m["category_id"] ?: "", m["category_name"]?.trim() ?: "Sans nom"))
        }
        out
    }

    suspend fun items(kind: Kind): List<Item> = withContext(Dispatchers.IO) {
        val action = when (kind) {
            Kind.LIVE -> "get_live_streams"
            Kind.MOVIE -> "get_vod_streams"
            Kind.SERIES -> "get_series"
        }
        val out = ArrayList<Item>(4096)
        readArray(cached(action)) { m ->
            val id = (if (kind == Kind.SERIES) m["series_id"] else m["stream_id"]) ?: return@readArray
            out.add(
                Item(
                    kind = kind,
                    id = id,
                    name = (m["name"] ?: m["title"] ?: "").trim(),
                    icon = (m["stream_icon"] ?: m["cover"])?.takeIf { it.startsWith("http") },
                    categoryId = m["category_id"] ?: "",
                    ext = m["container_extension"]?.takeIf { it.isNotBlank() } ?: "mp4",
                    num = m["num"]?.toDoubleOrNull()?.toInt() ?: 0,
                    rating = (m["rating"] ?: m["rating_5based"])?.takeIf { it.isNotBlank() && it != "0" && it != "0.0" },
                    added = (m["added"] ?: m["last_modified"])?.toLongOrNull() ?: 0L,
                    plot = m["plot"]?.takeIf { it.isNotBlank() },
                    backdrop = m["backdrop_path"]?.takeIf { it.startsWith("http") },
                    year = (m["releaseDate"] ?: m["release_date"] ?: m["year"])?.take(4)?.takeIf { it.isNotBlank() },
                )
            )
        }
        out
    }

    // ------------------------------------------------------------------ fiches

    suspend fun vodInfo(id: String): Details = withContext(Dispatchers.IO) {
        val o = JSONObject(fetch("$base&action=get_vod_info&vod_id=$id"))
        val i = o.optJSONObject("info") ?: JSONObject()
        Details(
            plot = i.str("plot") ?: i.str("description"),
            genre = i.str("genre"),
            duration = i.str("duration"),
            director = i.str("director"),
            cast = i.str("cast") ?: i.str("actors"),
            rating = i.str("rating"),
            year = (i.str("releasedate") ?: i.str("release_date"))?.take(4),
            backdrop = firstBackdrop(i),
        )
    }

    suspend fun seriesInfo(id: String): Details = withContext(Dispatchers.IO) {
        val o = JSONObject(fetch("$base&action=get_series_info&series_id=$id"))
        val i = o.optJSONObject("info") ?: JSONObject()
        val seasons = sortedMapOf<Int, List<Episode>>()
        when (val eps = o.opt("episodes")) {
            is JSONObject -> eps.keys().forEach { k ->
                val arr = eps.optJSONArray(k) ?: return@forEach
                val s = k.toIntOrNull() ?: 0
                val list = parseEpisodes(arr, s)
                if (list.isNotEmpty()) seasons[s] = list
            }
            is JSONArray -> for (n in 0 until eps.length()) {
                val arr = eps.optJSONArray(n) ?: continue
                val list = parseEpisodes(arr, n + 1)
                if (list.isNotEmpty()) seasons[list[0].season] = list
            }
        }
        Details(
            plot = i.str("plot"),
            genre = i.str("genre"),
            duration = i.str("episode_run_time")?.let { "$it min" },
            director = i.str("director"),
            cast = i.str("cast"),
            rating = i.str("rating"),
            year = (i.str("releaseDate") ?: i.str("release_date"))?.take(4),
            backdrop = firstBackdrop(i),
            seasons = seasons,
        )
    }

    private fun parseEpisodes(arr: JSONArray, season: Int): List<Episode> =
        (0 until arr.length()).mapNotNull { n ->
            val e = arr.optJSONObject(n) ?: return@mapNotNull null
            val info = e.optJSONObject("info")
            Episode(
                id = e.str("id") ?: return@mapNotNull null,
                season = e.str("season")?.toIntOrNull() ?: season,
                num = e.str("episode_num")?.toIntOrNull() ?: (n + 1),
                title = e.str("title") ?: "Épisode ${n + 1}",
                ext = e.str("container_extension") ?: "mp4",
                image = info?.str("movie_image")?.takeIf { it.startsWith("http") },
                plot = info?.str("plot"),
                duration = info?.str("duration"),
            )
        }.sortedBy { it.num }

    suspend fun shortEpg(streamId: String): List<EpgEntry> = withContext(Dispatchers.IO) {
        val o = JSONObject(fetch("$base&action=get_short_epg&stream_id=$streamId&limit=4"))
        val arr = o.optJSONArray("epg_listings") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { n ->
            val e = arr.optJSONObject(n) ?: return@mapNotNull null
            EpgEntry(
                title = b64(e.optString("title")),
                start = e.optString("start_timestamp").toLongOrNull() ?: 0L,
                end = e.optString("stop_timestamp").toLongOrNull() ?: 0L,
                desc = b64(e.optString("description")).takeIf { it.isNotBlank() },
            )
        }
    }

    // ------------------------------------------------------------------ outils

    private fun b64(s: String): String =
        try { String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8).trim() } catch (e: IllegalArgumentException) { s }

    private fun JSONObject.str(k: String): String? {
        val v = opt(k)
        if (v == null || v == JSONObject.NULL) return null
        return v.toString().trim().takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun firstBackdrop(o: JSONObject): String? = when (val b = o.opt("backdrop_path")) {
        is JSONArray -> if (b.length() > 0) b.optString(0) else null
        is String -> b
        else -> null
    }?.takeIf { it.startsWith("http") }

    /** Lecture en flux d'un tableau JSON d'objets "plats". */
    private fun readArray(file: File, onObj: (HashMap<String, String>) -> Unit) {
        JsonReader(BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8), 1 shl 16)).use { jr ->
            jr.isLenient = true
            if (jr.peek() != JsonToken.BEGIN_ARRAY) return   // ex : {} quand la liste est vide
            jr.beginArray()
            while (jr.hasNext()) {
                if (jr.peek() != JsonToken.BEGIN_OBJECT) { jr.skipValue(); continue }
                val m = HashMap<String, String>(24)
                jr.beginObject()
                while (jr.hasNext()) {
                    val k = jr.nextName()
                    when (jr.peek()) {
                        JsonToken.STRING, JsonToken.NUMBER -> m[k] = jr.nextString()
                        JsonToken.BOOLEAN -> m[k] = jr.nextBoolean().toString()
                        JsonToken.NULL -> jr.nextNull()
                        JsonToken.BEGIN_ARRAY -> {
                            var first: String? = null
                            jr.beginArray()
                            while (jr.hasNext()) {
                                if (first == null && jr.peek() == JsonToken.STRING) first = jr.nextString() else jr.skipValue()
                            }
                            jr.endArray()
                            if (first != null) m[k] = first
                        }
                        else -> jr.skipValue()
                    }
                }
                jr.endObject()
                onObj(m)
            }
            jr.endArray()
        }
    }

    companion object {
        private const val CACHE_TTL = 6L * 60 * 60 * 1000   // 6 h
    }
}
