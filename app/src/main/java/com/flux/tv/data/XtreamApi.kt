package com.flux.tv.data

import android.net.Uri
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import com.flux.tv.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
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
    /** Les grosses listes (films, séries, chaînes) peuvent mettre plusieurs minutes à être générées par le serveur. */
    private val syncHttp = App.http.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    /** Mêmes clients, mais les redirections sont suivies à la main (voir [exec]). */
    private val httpNR = App.http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val syncNR = syncHttp.newBuilder().followRedirects(false).followSslRedirects(false).build()

    /** Dernière redirection rencontrée (pour le diagnostic). */
    @Volatile var lastRedirect: String? = null
        private set

    /**
     * Suit les redirections en conservant tous les paramètres de la requête et la méthode (POST).
     * Certains serveurs Xtream redirigent vers un autre serveur en ne gardant que l'identifiant
     * et le mot de passe : la demande (« action ») se perd et seules les infos du compte reviennent.
     */
    private fun exec(client: OkHttpClient, req: Request): Response {
        var r = req
        repeat(8) {
            val resp = client.newCall(r).execute()
            if (!resp.isRedirect) return resp
            val loc = resp.header("Location")
            resp.close()
            val target = loc?.let { r.url.resolve(it) } ?: throw IOException("redirection sans adresse (HTTP ${resp.code})")
            val merged = target.newBuilder().apply {
                for (name in r.url.queryParameterNames) {
                    if (target.queryParameter(name) == null) r.url.queryParameterValues(name).forEach { v -> addQueryParameter(name, v) }
                }
            }.build()
            Log.i(TAG, "redirection ${r.url.host} -> ${merged.host}${merged.encodedPath}")
            lastRedirect = merged.host
            r = r.newBuilder().url(merged).build()
        }
        throw IOException("trop de redirections")
    }

    private val base = "$server/player_api.php?username=${URLEncoder.encode(user, "UTF-8")}" +
        "&password=${URLEncoder.encode(pass, "UTF-8")}"
    private val u = Uri.encode(user)
    private val p = Uri.encode(pass)
    private val userRaw = user
    private val passRaw = pass
    private val userQ = URLEncoder.encode(user, "UTF-8")
    private val passQ = URLEncoder.encode(pass, "UTF-8")
    private val serverHost = Uri.parse(server).host

    /** Adresse M3U fournie telle quelle si elle pointe ailleurs que le serveur (sinon adresse Xtream classique). */
    private fun directOr(direct: String?, built: String) =
        if (direct != null && Uri.parse(direct).host != serverHost) direct else built

    /** Adresse de lecture à conserver pour un titre (inutile si elle pointe vers le serveur habituel). */
    fun keepDirect(direct: String?): String? = direct?.takeIf { Uri.parse(it).host != serverHost }

    fun liveUrl(id: String, direct: String? = null) = directOr(direct, "$server/live/$u/$p/$id.${Prefs.liveFormat}")
    fun movieUrl(id: String, ext: String, direct: String? = null) = directOr(direct, "$server/movie/$u/$p/$id.$ext")
    fun episodeUrl(id: String, ext: String, direct: String? = null) = directOr(direct, "$server/series/$u/$p/$id.$ext")

    // ------------------------------------------------------------------ réseau

    private fun fetchUrl(url: String): String =
        exec(httpNR, Request.Builder().url(url).build()).use { r ->
            if (!r.isSuccessful) throw IOException("Erreur serveur (HTTP ${r.code})")
            r.body?.string() ?: ""
        }

    /**
     * Formes de requête à player_api.php. Certains serveurs ignorent « action » dans la forme
     * classique et renvoient seulement les infos du compte : on essaie alors les autres formes
     * utilisées par les lecteurs IPTV, et on retient celle qui marche.
     */
    /**
     * Serveurs à essayer : l'adresse saisie, puis l'adresse réelle annoncée par le serveur
     * à la connexion (server_info), si elle est différente.
     */
    private fun bases(): List<String> = listOfNotNull(server, Prefs.altServer.takeIf { it.isNotEmpty() && it != server })

    /** Combinaisons (serveur, forme de requête), celle qui a déjà marché en premier. */
    private fun modeOrder(): List<Int> {
        val all = bases().indices.flatMap { b -> (0..2).map { b * 3 + it } }
        val m = Prefs.apiMode
        return if (m in all) listOf(m) + all.filter { it != m } else all
    }

    private fun apiRequest(combo: Int, params: String): Request {
        val srv = bases().getOrElse(combo / 3) { server }
        val mode = combo % 3
        val url = "$srv/player_api.php"
        return when (mode) {
            1 -> Request.Builder().url(url).post(FormBody.Builder().apply {
                add("username", userRaw); add("password", passRaw)
                params.split('&').forEach { kv ->
                    val i = kv.indexOf('=')
                    if (i > 0) add(kv.substring(0, i), kv.substring(i + 1))
                }
            }.build()).build()
            2 -> Request.Builder().url("$url?$params&username=$userQ&password=$passQ").build()
            else -> Request.Builder().url("$url?username=$userQ&password=$passQ&$params").build()
        }
    }

    private fun remember(mode: Int) {
        if (Prefs.apiMode != mode) { Prefs.apiMode = mode; Log.i(TAG, "forme de requête retenue : ${modeName(mode)}") }
    }

    private fun fetch(params: String): String {
        for (mode in modeOrder()) {
            val text = exec(httpNR, apiRequest(mode, params)).use { r ->
                if (!r.isSuccessful) throw IOException("Erreur serveur (HTTP ${r.code})")
                r.body?.string() ?: ""
            }
            if (!isAccountEcho(text.take(400))) { remember(mode); return text }
        }
        throw AccountEchoException(lastRedirect)
    }

    /** Le serveur renvoie les infos du compte au lieu de la liste demandée. */
    class AccountEchoException(redirect: String? = null) : IOException(
        "le serveur ignore la demande et renvoie seulement les infos du compte" +
            (redirect?.let { " (redirigé vers un autre serveur)" } ?: "")
    )

    /**
     * Liste du catalogue stockée de façon permanente (dossier de l'appli, pas le cache que
     * Android peut vider) : une fois téléchargée, elle sert à chaque lancement sans attendre le serveur.
     * Si elle manque (ex. synchronisation passée), on la télécharge à la demande.
     */
    private fun cached(action: String): File {
        val f = catalogFile(action)
        if (f.exists()) return f
        try {
            download(action, null)
        } catch (e: IOException) {
            throw IOException("${label(action)} : ${explain(e)}", e)
        }
        return f
    }

    /**
     * Télécharge une liste du catalogue dans un fichier temporaire puis remplace l'ancienne
     * d'un coup (jamais de fichier à moitié écrit). Pas de durée maximale : on attend tant que
     * le serveur envoie des données, et jusqu'à 5 minutes de silence (temps de génération).
     */
    fun download(action: String, onBytes: ((Long) -> Unit)?): Long {
        val f = catalogFile(action)
        val tmp = File(f.parentFile, "$action.tmp")
        val t0 = System.currentTimeMillis()
        try {
            for (mode in modeOrder()) {
                save(apiRequest(mode, "action=$action"), tmp, onBytes)
                if (isAccountEcho(head(tmp))) {
                    Log.w(TAG, "$action : forme « ${modeName(mode)} » ignorée par le serveur")
                    continue
                }
                checkJson(action, tmp)
                remember(mode)
                if (!tmp.renameTo(f)) throw IOException("écriture du catalogue impossible")
                Log.i(TAG, "$action : ${f.length()} octets en ${System.currentTimeMillis() - t0} ms")
                return f.length()
            }
            throw AccountEchoException(lastRedirect)
        } catch (e: IOException) {
            tmp.delete()
            Log.w(TAG, "$action échec après ${System.currentTimeMillis() - t0} ms", e)
            throw e
        }
    }

    /** Écrit la réponse dans un fichier en signalant la progression. */
    private fun save(req: Request, out: File, onBytes: ((Long) -> Unit)?) {
        exec(syncNR, req).use { r ->
            if (!r.isSuccessful) throw IOException("le serveur a répondu HTTP ${r.code}")
            val body = r.body ?: throw IOException("réponse vide")
            out.outputStream().buffered(1 shl 16).use { o ->
                val input = body.byteStream()
                val buf = ByteArray(1 shl 16)
                var n = 0L; var lastTick = 0L
                while (true) {
                    val k = input.read(buf); if (k < 0) break
                    o.write(buf, 0, k); n += k
                    if (onBytes != null && n - lastTick > 256 * 1024) { lastTick = n; onBytes(n) }
                }
                onBytes?.invoke(n)
            }
        }
    }

    private fun head(f: File): String =
        f.inputStream().use { i -> ByteArray(400).let { b -> String(b, 0, i.read(b).coerceAtLeast(0)) } }.trim()

    // ------------------------------------------------------------------ playlist M3U (secours)

    /** Playlist complète (chaînes, films, séries) au format M3U, proposée par tous les serveurs Xtream. */
    fun m3uRequest(srv: String = server): Request =
        Request.Builder().url("$srv/get.php?username=$userQ&password=$passQ&type=m3u_plus&output=ts").build()

    /**
     * Construit tout le catalogue depuis la playlist M3U quand l'API ne donne pas les listes.
     * Les fichiers produits ont le même format que ceux de l'API : le reste de l'appli ne change pas.
     */
    fun importM3u(onBytes: (Long) -> Unit): M3uImport.Counts {
        val dir = catalogDir()
        val playlist = File(dir, "playlist.m3u.tmp")
        try {
            var last: IOException? = null
            for (srv in bases()) {
                try { save(m3uRequest(srv), playlist, onBytes); last = null; break } catch (e: IOException) { last = e }
            }
            last?.let { throw IOException("la liste complète (get.php) est refusée : ${it.message}", it) }
            return M3uImport.convert(playlist, dir)
        } finally {
            playlist.delete()
        }
    }

    /** Refuse une réponse qui n'est manifestement pas du JSON (page d'erreur, blocage…). */
    private fun checkJson(action: String, f: File) {
        val head = f.inputStream().use { i -> ByteArray(120).let { b -> String(b, 0, i.read(b).coerceAtLeast(0)) } }.trim()
        if (!head.startsWith("[") && !head.startsWith("{")) {
            if (head.startsWith("<")) throw IOException("le serveur a renvoyé une page web au lieu des données (début : « ${mask(head.take(80))} »)")
            throw IOException("réponse illisible du serveur (début : « ${mask(head.take(80))} »)")
        }
    }

    fun catalogFile(action: String) = File(catalogDir(), "$action.json")

    // ------------------------------------------------------------------ compte

    suspend fun auth(): AccountInfo = withContext(Dispatchers.IO) {
        val o = try {
            JSONObject(fetchUrl(base))
        } catch (e: JSONException) {
            throw IOException("Ce serveur ne répond pas comme un serveur Xtream Codes")
        }
        val ui = o.optJSONObject("user_info") ?: throw IOException("Réponse invalide du serveur")
        if (ui.optInt("auth", 0) != 1) throw IOException("Identifiant ou mot de passe incorrect")
        rememberServerInfo(o.optJSONObject("server_info"))
        AccountInfo(
            ui.str("status") ?: "",
            ui.str("exp_date")?.toLongOrNull(),
            ui.str("active_cons") ?: "?",
            ui.str("max_connections") ?: "?",
        )
    }

    /**
     * Adresse réelle annoncée par le serveur (server_info : url, port, protocole).
     * Beaucoup de lecteurs IPTV l'utilisent pour la suite ; on la garde comme second essai.
     */
    private fun rememberServerInfo(si: JSONObject?) {
        val host = si?.str("url")?.removePrefix("http://")?.removePrefix("https://")?.trimEnd('/') ?: return
        val proto = si.str("server_protocol")?.takeIf { it == "https" || it == "http" } ?: "http"
        val port = (if (proto == "https") si.str("https_port") else si.str("port"))?.takeIf { it != "0" }
        val alt = "$proto://$host" + (port?.takeUnless { (proto == "http" && it == "80") || (proto == "https" && it == "443") }?.let { ":$it" } ?: "")
        if (alt != server && alt != Prefs.altServer) {
            Prefs.altServer = alt
            Log.i(TAG, "adresse annoncée par le serveur : $proto://••• (port ${port ?: "défaut"})")
        }
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
                    direct = m["direct_source"]?.takeIf { it.startsWith("http") },
                )
            )
        }
        out
    }

    // ------------------------------------------------------------------ fiches

    suspend fun vodInfo(id: String): Details = withContext(Dispatchers.IO) {
        val o = JSONObject(fetch("action=get_vod_info&vod_id=$id"))
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
        // Série reconstituée depuis la playlist M3U : épisodes lus dans le fichier local
        if (id.startsWith(M3uImport.SERIES_PREFIX)) return@withContext M3uImport.seriesDetails(catalogDir(), id)
        val o = JSONObject(fetch("action=get_series_info&series_id=$id"))
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
        val o = JSONObject(fetch("action=get_short_epg&stream_id=$streamId&limit=4"))
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

    /** Interroge chaque liste (et la playlist M3U) sans rien garder, et décrit la réponse. */
    suspend fun probeCatalog(): List<Probe> = withContext(Dispatchers.IO) {
        ACTIONS.map { a -> probe(a) } + probeM3u()
    }

    private class Peek(val code: Int, val bytes: Long, val head: String, val redirected: String?)

    private fun peek(req: Request): Peek = exec(syncNR, req.also { lastRedirect = null }).use { r ->
        var n = 0L; var head = ""
        r.body?.byteStream()?.let { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val k = input.read(buf); if (k < 0) break
                if (n == 0L) head = String(buf, 0, minOf(k, 300))
                n += k
            }
        }
        val moved = lastRedirect?.let { if (it == serverHost) "même serveur" else it }
        Peek(r.code, n, head.trim(), moved)
    }

    private fun probe(action: String): Probe {
        val t0 = System.currentTimeMillis()
        return try {
            var last: Peek? = null
            for (mode in bases().indices.flatMap { b -> (0..2).map { b * 3 + it } }) {
                val pk = peek(apiRequest(mode, "action=$action"))
                last = pk
                if (pk.code in 200..299 && pk.head.startsWith("[")) {
                    val how = if (mode == 0) "" else " (${modeName(mode)})"
                    return Probe(action, true, "${size(pk.bytes)} · ${secs(t0)}$how" + redirectNote(pk))
                }
                if (!isAccountEcho(pk.head)) break   // autre problème : inutile d'essayer les autres formes
            }
            val pk = last!!
            val what = when {
                isAccountEcho(pk.head) -> "renvoie les infos du compte au lieu de la liste (${bases().size * 3} formes de requête essayées)"
                pk.head.startsWith("{") -> "objet JSON au lieu d'une liste : « ${mask(pk.head.take(70))} »"
                pk.head.isEmpty() -> "réponse vide"
                pk.head.startsWith("<") -> "page web au lieu des données : « ${mask(pk.head.take(70))} »"
                else -> "pas du JSON : « ${mask(pk.head.take(70))} »"
            }
            Probe(action, false, "HTTP ${pk.code} · ${size(pk.bytes)} · ${secs(t0)} · $what" + redirectNote(pk))
        } catch (e: IOException) {
            Probe(action, false, "échec après ${secs(t0)} : ${explain(e)}")
        }.also { Log.i(TAG, "probe $action -> ${it.text}") }
    }

    private fun probeM3u(): Probe {
        val t0 = System.currentTimeMillis()
        return try {
            var entries = 0L; var n = 0L; var first = ""
            val code = exec(syncNR, m3uRequest().also { lastRedirect = null }).use { r ->
                r.body?.byteStream()?.bufferedReader()?.let { rd ->
                    while (true) {
                        val line = rd.readLine() ?: break
                        if (n == 0L) first = line.trim()
                        n += line.length + 1
                        if (line.startsWith("#EXTINF")) entries++
                    }
                }
                r.code
            }
            val ok = code in 200..299 && first.startsWith("#EXTM3U") && entries > 0
            Probe("get.php", ok, "HTTP $code · ${size(n)} · ${secs(t0)} · " +
                (if (ok) "$entries entrées" else "« ${mask(first.take(70))} »") +
                (lastRedirect?.let { " · redirigé (${if (it == serverHost) "même serveur" else it})" } ?: ""))
        } catch (e: IOException) {
            Probe("get.php", false, "échec après ${secs(t0)} : ${explain(e)}")
        }.also { Log.i(TAG, "probe m3u -> ${it.text}") }
    }

    private fun redirectNote(pk: Peek) = pk.redirected?.let { " · redirigé ($it)" } ?: ""
    private fun secs(t0: Long) = String.format(java.util.Locale.FRANCE, "%.1f s", (System.currentTimeMillis() - t0) / 1000.0)
    private fun size(b: Long) = if (b >= 1 shl 20) String.format(java.util.Locale.FRANCE, "%.1f Mo", b / 1048576.0) else "${(b + 1023) / 1024} Ko"

    /** Ne jamais afficher l'identifiant ni le mot de passe IPTV à l'écran. */
    private fun mask(t: String): String {
        var r = t
        if (userRaw.length >= 3) r = r.replace(userRaw, "•••")
        if (passRaw.length >= 3) r = r.replace(passRaw, "•••")
        return r
    }

    fun explain(e: IOException): String = when (e) {
        is SocketTimeoutException -> "le serveur met trop de temps à répondre (délai dépassé)"
        is UnknownHostException -> "serveur introuvable (${e.message})"
        is ConnectException -> "connexion refusée ou bloquée"
        is javax.net.ssl.SSLException -> "erreur de sécurité TLS (${e.message})"
        else -> e.message ?: e.javaClass.simpleName
    }

    companion object {
        private const val TAG = "FluxAPI"
        private val MODE_NAMES = arrayOf("GET", "POST", "GET inversé")
        private fun modeName(combo: Int) = MODE_NAMES[combo % 3] + if (combo >= 3) " via l'adresse annoncée par le serveur" else ""

        fun isAccountEcho(head: String) = head.trimStart().startsWith("{") && head.contains("\"user_info\"")

        /** Les 6 listes du catalogue, dans l'ordre de téléchargement (les plus légères d'abord). */
        val ACTIONS = listOf(
            "get_live_categories", "get_vod_categories", "get_series_categories",
            "get_live_streams", "get_vod_streams", "get_series",
        )

        fun label(action: String) = when (action) {
            "get_live_categories" -> "Catégories des chaînes"
            "get_live_streams" -> "Liste des chaînes"
            "get_vod_categories" -> "Catégories des films"
            "get_vod_streams" -> "Liste des films"
            "get_series_categories" -> "Catégories des séries"
            "get_series" -> "Liste des séries"
            "get.php" -> "Liste complète (get.php)"
            else -> action
        }

        fun catalogDir() = File(App.instance.filesDir, "catalog").apply { mkdirs() }

        /** Âge de la plus ancienne liste du catalogue (ms), ou null s'il en manque une. */
        fun catalogAge(): Long? {
            val files = ACTIONS.map { File(catalogDir(), "$it.json") }
            if (files.any { !it.exists() }) return null
            return System.currentTimeMillis() - files.minOf { it.lastModified() }
        }
    }
}
