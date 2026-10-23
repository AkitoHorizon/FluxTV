package com.flux.tv.data

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/**
 * Copie compacte du catalogue, prête à l'emploi.
 *
 * Les listes du serveur (JSON, souvent plusieurs dizaines de Mo) ne sont analysées qu'une fois,
 * juste après leur téléchargement ; les ouvertures suivantes relisent ce fichier binaire,
 * bien plus rapide à charger sur le petit processeur du boîtier. Si les listes JSON sont plus
 * récentes (mise à jour en arrière-plan), la copie est simplement refaite.
 *
 * Ne concerne que la liste des titres : la lecture vidéo n'est pas touchée.
 */
object CatalogStore {
    private const val TAG = "FluxStore"
    private const val VERSION = 1

    private fun sources(kind: Kind): List<String> = when (kind) {
        Kind.LIVE -> listOf("get_live_streams", "get_live_categories")
        Kind.MOVIE -> listOf("get_vod_streams", "get_vod_categories")
        Kind.SERIES -> listOf("get_series", "get_series_categories")
    }

    private fun file(kind: Kind) = File(XtreamApi.catalogDir(), "store_${kind.prefix}.bin")

    /** Lit la copie compacte si elle est à jour, sinon null. */
    fun read(kind: Kind): Pair<List<Item>, List<Category>>? {
        val f = file(kind)
        if (!f.exists()) return null
        val newestSource = sources(kind).map { File(XtreamApi.catalogDir(), "$it.json") }
            .filter { it.exists() }.maxOfOrNull { it.lastModified() } ?: return null
        if (f.lastModified() < newestSource) return null
        val t0 = System.currentTimeMillis()
        return try {
            DataInputStream(BufferedInputStream(f.inputStream(), 1 shl 16)).use { d ->
                if (d.readInt() != VERSION) return null
                val pool = HashMap<String, String>()
                fun str(): String { val s = d.readUTF(); return pool.getOrPut(s) { s } }
                fun opt(): String? = if (d.readBoolean()) d.readUTF() else null
                fun optPooled(): String? = if (d.readBoolean()) str() else null

                val cats = List(d.readInt()) { Category(str(), d.readUTF()) }
                val items = List(d.readInt()) {
                    Item(
                        kind = kind, id = d.readUTF(), name = d.readUTF(), icon = opt(),
                        categoryId = str(), ext = str(), num = d.readInt(), rating = optPooled(),
                        added = d.readLong(), plot = opt(), backdrop = opt(), year = optPooled(), direct = opt(),
                    )
                }
                Log.i(TAG, "$kind : ${items.size} titres relus en ${System.currentTimeMillis() - t0} ms")
                items to cats
            }
        } catch (e: IOException) {
            Log.w(TAG, "copie compacte $kind illisible, on repart du JSON", e)
            f.delete(); null
        }
    }

    /** Écrit la copie compacte (fichier temporaire puis remplacement d'un coup). */
    fun write(kind: Kind, items: List<Item>, cats: List<Category>) {
        val f = file(kind)
        val tmp = File(f.parentFile, f.name + ".tmp")
        try {
            DataOutputStream(BufferedOutputStream(tmp.outputStream(), 1 shl 16)).use { d ->
                fun opt(s: String?) { d.writeBoolean(s != null); if (s != null) d.writeUTF(s.take(MAX_TEXT)) }
                d.writeInt(VERSION)
                d.writeInt(cats.size)
                cats.forEach { d.writeUTF(it.id); d.writeUTF(it.name.take(MAX_TEXT)) }
                d.writeInt(items.size)
                for (it in items) {
                    d.writeUTF(it.id); d.writeUTF(it.name.take(MAX_TEXT)); opt(it.icon)
                    d.writeUTF(it.categoryId); d.writeUTF(it.ext); d.writeInt(it.num); opt(it.rating)
                    d.writeLong(it.added); opt(it.plot); opt(it.backdrop); opt(it.year); opt(it.direct)
                }
            }
            if (!tmp.renameTo(f)) tmp.delete()
        } catch (e: IOException) {
            Log.w(TAG, "écriture de la copie compacte $kind impossible", e)
            tmp.delete()
        }
    }

    /** writeUTF est limité à 64 Ko : les textes très longs (résumés) sont raccourcis. */
    private const val MAX_TEXT = 8000
}
