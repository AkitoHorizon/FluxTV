package com.flux.tv.data

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.File
import java.io.IOException

/**
 * Secours pour les serveurs Xtream Codes dont l'API ne renvoie pas les listes :
 * la liste complète (get.php, même compte) est convertie en fichiers au format de l'API.
 * Chaînes, films et séries sont reconnus à leur adresse (/live/, /movie/, /series/),
 * les catégories viennent de « group-title », les séries sont regroupées par nom (S01 E02…).
 */
object M3uImport {
    const val SERIES_PREFIX = "m3u"
    private const val EPISODES = "m3u_episodes.json"

    class Counts(val live: Int, val movies: Int, val series: Int, val episodes: Int)

    private val attrRx = Regex("""([\w-]+)="([^"]*)"""")
    private val epRx = Regex("""^(.*?)[\s._\-|:]*\b[Ss](\d{1,2})[\s._-]*[Ee](\d{1,4})\b(.*)$""")

    private class Entry(val attrs: Map<String, String>, val title: String)
    private class SeriesAcc(val id: String, val name: String, val cover: String?, val cat: String, var count: Int = 0)

    fun convert(playlist: File, dir: File): Counts {
        val first = playlist.bufferedReader().use { r -> generateSequence { r.readLine() }.firstOrNull { it.isNotBlank() } }?.trim().orEmpty()
        if (!first.startsWith("#EXTM3U")) {
            throw IOException("la liste complète du serveur est illisible (début : « ${first.take(60)} »)")
        }

        val cats = mapOf(Kind.LIVE to LinkedHashMap<String, String>(), Kind.MOVIE to LinkedHashMap(), Kind.SERIES to LinkedHashMap())
        val series = LinkedHashMap<String, SeriesAcc>()
        var nLive = 0; var nMovie = 0; var nEp = 0

        fun tmp(name: String) = File(dir, "$name.m3u.tmp")
        val liveW = writer(tmp("get_live_streams"))
        val movieW = writer(tmp("get_vod_streams"))
        val epW = writer(tmp(EPISODES))
        try {
            playlist.bufferedReader().use { rd ->
                var pending: Entry? = null
                while (true) {
                    val line = rd.readLine()?.trim() ?: break
                    when {
                        line.startsWith("#EXTINF") -> pending = parseInf(line)
                        line.isEmpty() || line.startsWith("#") -> {}
                        pending != null -> {
                            val e = pending; pending = null
                            val url = line
                            val path = url.substringBefore('?')
                            val last = path.substringAfterLast('/')
                            val id = last.substringBeforeLast('.').ifEmpty { last }
                            val ext = if (last.contains('.')) last.substringAfterLast('.') else ""
                            val group = e.attrs["group-title"]?.trim().orEmpty().ifEmpty { "Sans catégorie" }
                            val logo = e.attrs["tvg-logo"]?.takeIf { it.startsWith("http") }
                            when {
                                path.contains("/movie/") -> {
                                    nMovie++
                                    val cat = catId(cats.getValue(Kind.MOVIE), group, "m")
                                    movieW.obj {
                                        name("num").value(nMovie.toLong()); name("name").value(e.title)
                                        name("stream_id").value(id); name("category_id").value(cat)
                                        name("container_extension").value(ext.ifEmpty { "mp4" })
                                        logo?.let { name("stream_icon").value(it) }
                                        name("direct_source").value(url)
                                    }
                                }
                                path.contains("/series/") -> {
                                    nEp++
                                    val cat = catId(cats.getValue(Kind.SERIES), group, "s")
                                    val m = epRx.find(e.title)
                                    val sName = (m?.groupValues?.get(1)?.trim()?.trimEnd('-', '|', ':', '.')?.trim()).orEmpty().ifEmpty { e.title }
                                    val acc = series.getOrPut(sName.lowercase()) {
                                        SeriesAcc(SERIES_PREFIX + (sName.lowercase().hashCode().toLong() and 0xffffffffL), sName, logo, cat)
                                    }
                                    acc.count++
                                    val season = m?.groupValues?.get(2)?.toIntOrNull() ?: 1
                                    val num = m?.groupValues?.get(3)?.toIntOrNull() ?: acc.count
                                    epW.obj {
                                        name("series_id").value(acc.id); name("id").value(id)
                                        name("season").value(season.toLong()); name("episode_num").value(num.toLong())
                                        name("title").value(e.title); name("container_extension").value(ext.ifEmpty { "mp4" })
                                        logo?.let { name("image").value(it) }
                                        name("direct_source").value(url)
                                    }
                                }
                                else -> {
                                    nLive++
                                    val cat = catId(cats.getValue(Kind.LIVE), group, "l")
                                    liveW.obj {
                                        name("num").value(nLive.toLong()); name("name").value(e.title)
                                        name("stream_id").value(id); name("category_id").value(cat)
                                        logo?.let { name("stream_icon").value(it) }
                                        e.attrs["tvg-id"]?.takeIf { it.isNotBlank() }?.let { name("epg_channel_id").value(it) }
                                        name("direct_source").value(url)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            for (w in listOf(liveW, movieW, epW)) runCatching { w.endArray(); w.close() }
        }

        writer(tmp("get_series")).use { w ->
            var n = 0
            for (s in series.values) w.obj {
                name("num").value((++n).toLong()); name("name").value(s.name); name("series_id").value(s.id)
                name("category_id").value(s.cat); s.cover?.let { name("cover").value(it) }
            }
            w.endArray()
        }
        for ((kind, action) in listOf(Kind.LIVE to "get_live_categories", Kind.MOVIE to "get_vod_categories", Kind.SERIES to "get_series_categories")) {
            writer(tmp(action)).use { w ->
                for ((group, id) in cats.getValue(kind)) w.obj { name("category_id").value(id); name("category_name").value(group) }
                w.endArray()
            }
        }
        // Tout est prêt : on remplace les fichiers d'un coup
        for (action in XtreamApi.ACTIONS + EPISODES.removeSuffix(".json")) {
            val t = tmp(action); val f = File(dir, "$action.json")
            if (!t.renameTo(f)) throw IOException("écriture du catalogue impossible")
        }
        return Counts(nLive, nMovie, series.size, nEp)
    }

    /** Épisodes d'une série reconstituée, lus dans le fichier local. */
    fun seriesDetails(dir: File, seriesId: String): Details {
        val seasons = sortedMapOf<Int, MutableList<Episode>>()
        val f = File(dir, EPISODES)
        if (f.exists()) JsonReader(f.bufferedReader()).use { jr ->
            jr.beginArray()
            while (jr.hasNext()) {
                val m = HashMap<String, String>()
                jr.beginObject()
                while (jr.hasNext()) {
                    val k = jr.nextName()
                    if (jr.peek() == JsonToken.NULL) jr.nextNull() else m[k] = jr.nextString()
                }
                jr.endObject()
                if (m["series_id"] != seriesId) continue
                val ep = Episode(
                    id = m["id"] ?: continue,
                    season = m["season"]?.toIntOrNull() ?: 1,
                    num = m["episode_num"]?.toIntOrNull() ?: 1,
                    title = m["title"] ?: "",
                    ext = m["container_extension"] ?: "mp4",
                    image = m["image"],
                    plot = null,
                    duration = null,
                    direct = m["direct_source"],
                )
                seasons.getOrPut(ep.season) { ArrayList() }.add(ep)
            }
            jr.endArray()
        }
        return Details(
            plot = null, genre = null, duration = null, director = null, cast = null, rating = null, year = null,
            backdrop = null, seasons = seasons.mapValues { (_, l) -> l.sortedBy { it.num } }.toSortedMap(),
        )
    }

    private fun parseInf(line: String): Entry {
        val attrs = HashMap<String, String>()
        attrRx.findAll(line).forEach { attrs[it.groupValues[1].lowercase()] = it.groupValues[2] }
        // Titre affiché : après la dernière virgule qui suit les attributs
        val lastQuote = line.lastIndexOf('"')
        val comma = line.indexOf(',', if (lastQuote >= 0) lastQuote else 0)
        val title = (if (comma >= 0) line.substring(comma + 1) else "").trim()
            .ifEmpty { attrs["tvg-name"].orEmpty() }.ifEmpty { "Sans titre" }
        return Entry(attrs, title)
    }

    private fun catId(map: LinkedHashMap<String, String>, group: String, prefix: String) =
        map.getOrPut(group) { "$SERIES_PREFIX-$prefix${map.size + 1}" }

    private fun writer(f: File) = JsonWriter(f.bufferedWriter()).also { it.beginArray() }

    private inline fun JsonWriter.obj(block: JsonWriter.() -> Unit) { beginObject(); block(); endObject() }
}
