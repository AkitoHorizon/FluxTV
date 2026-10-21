package com.flux.tv.data

import android.net.Uri
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
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
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Client de l'API Xtream Codes (player_api.php).
 * - Les grosses listes sont mises en cache sur disque (démarrage instantané).
 * - Le JSON est lu en flux (JsonReader) : peu de mémoire, même avec 50 000 entrées.
 */
class XtreamApi(val server: String, user: String, pass: String) {

    private val http = App.http
    /** Les grosses listes (films, séries, chaînes) peuvent mettre plus d'une minute à être générées par le serveur. */
    private val slowHttp = App.http.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()
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
        val t0 = System.currentTimeMillis()
        return try {
            val tmp = File(dir, "$action.tmp")
            slowHttp.newCall(Request.Builder().url("$base&action=$action").build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("le serveur a répondu HTTP ${r.code}")
                val body = r.body ?: throw IOException("réponse vide")
                tmp.outputStream().buffered(1 shl 16).use { out -> body.byteStream().copyTo(out, 1 shl 16) }
            }
            Log.i(TAG, "$action : ${tmp.length()} octets en ${System.currentTimeMillis() - t0} ms")
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) throw IOException("écriture du cache impossible")
            f
        } catch (e: IOException) {
            Log.w(TAG, "$action échec après ${System.currentTimeMillis() - t0} ms", e)
            // Réseau en panne : on se rabat sur l'ancien cache s'il existe
            if (f.exists() && f.length() > 2) f
            else throw IOException("${label(action)} : ${explain(e)}", e)
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
        try { readArrayRaw(file, onObj) } catch (e: Exception) {
            if (e is IOException && e !is java.io.EOFException && e !is android.util.MalformedJsonException) throw e
            val head = file.inputStream().use { i -> ByteArray(120).let { b -> String(b, 0, i.read(b).coerceAtLeast(0)) } }
            file.delete()   // on ne garde pas une réponse illisible en cache
            throw IOException("${label(file.nameWithoutExtension)} : réponse illisible du serveur (début : « ${head.trim().take(80)} »)", e)
        }
    }

    private fun readArrayRaw(file: File, onObj: (HashMap<String, String>) -> Unit) {
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

    // ------------------------------------------------------------------ diagnostic

    class Probe(val action: String, val ok: Boolean, val text: String)

    /** Télécharge chaque liste sans la garder et rapporte code HTTP, taille, durée et nombre d'entrées. */
    suspend fun probeCatalog(): List<Probe> = withContext(Dispatchers.IO) {
        listOf("get_live_categories", "get_live_streams", "get_vod_categories", "get_vod_streams",
            "get_series_categories", "get_series").map { a -> probe(a) }
    }

    private fun probe(action: String): Probe {
        val t0 = System.currentTimeMillis()
        return try {
            slowHttp.newCall(Request.Builder().url("$base&action=$action").build()).execute().use { r ->
                val body = r.body
                val bytes = body?.byteStream()?.let { input ->
                    var n = 0L; var head = ""
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val k = input.read(buf); if (k < 0) break
                        if (n == 0L) head = String(buf, 0, minOf(k, 200))
                        n += k
                    }
                    n to head
                } ?: (0L to "")
                val ms = System.currentTimeMillis() - t0
                val head = bytes.second.trim()
                val kind = when {
                    head.startsWith("[") -> "liste JSON"
                    head.startsWith("{") -> "objet JSON (pas une liste) : « ${head.take(60)} »"
                    head.isEmpty() -> "vide"
                    else -> "pas du JSON : « ${head.take(60)} »"
                }
                val ok = r.isSuccessful && head.startsWith("[")
                Probe(action, ok, "HTTP ${r.code} · ${bytes.first / 1024} Ko · ${ms / 1000.0} s · $kind")
            }
        } catch (e: IOException) {
            Probe(action, false, "échec après ${(System.currentTimeMillis() - t0) / 1000.0} s : ${explain(e)}")
        }.also { Log.i(TAG, "probe $action -> ${it.text}") }
    }

    private fun label(action: String) = when (action) {
        "get_live_categories" -> "Catégories des chaînes"
        "get_live_streams" -> "Liste des chaînes"
        "get_vod_categories" -> "Catégories des films"
        "get_vod_streams" -> "Liste des films"
        "get_series_categories" -> "Catégories des séries"
        "get_series" -> "Liste des séries"
        else -> action
    }

    private fun explain(e: IOException): String = when (e) {
        is SocketTimeoutException -> "le serveur met trop de temps à répondre (délai dépassé)"
        is UnknownHostException -> "serveur introuvable (${e.message})"
        is ConnectException -> "connexion refusée ou bloquée"
        is javax.net.ssl.SSLException -> "erreur de sécurité TLS (${e.message})"
        else -> e.message ?: e.javaClass.simpleName
    }

    companion object {
        private const val TAG = "FluxAPI"
        private const val CACHE_TTL = 6L * 60 * 60 * 1000   // 6 h
    }
}
