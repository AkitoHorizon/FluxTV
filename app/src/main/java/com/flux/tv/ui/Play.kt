package com.flux.tv.ui

import android.content.Context
import android.content.Intent
import com.flux.tv.data.Details
import com.flux.tv.data.Episode
import com.flux.tv.data.Item
import com.flux.tv.data.PlayItem
import com.flux.tv.data.Prefs
import com.flux.tv.data.Repo

/** Prépare la file de lecture et ouvre le lecteur. */
object Play {

    fun live(ctx: Context, channels: List<Item>, index: Int) {
        if (channels.isEmpty()) return
        val api = Repo.api()
        Repo.queue = channels.map { PlayItem(it.key, it.name, null, api.liveUrl(it.id), true, it.icon, it.num, it.id) }
        Repo.queueIndex = index.coerceIn(0, channels.size - 1)
        Repo.resume = false
        Repo.seriesId = null
        open(ctx)
    }

    fun movie(ctx: Context, item: Item, resume: Boolean) {
        Repo.queue = listOf(PlayItem(item.key, item.name, item.year, Repo.api().movieUrl(item.id, item.ext), false, item.backdrop ?: item.icon, 0, item.id))
        Repo.queueIndex = 0
        Repo.resume = resume
        Repo.seriesId = null
        Prefs.pushRecent(item.key)
        open(ctx)
    }

    fun episodes(ctx: Context, series: Item, eps: List<Episode>, index: Int, resume: Boolean) {
        if (eps.isEmpty()) return
        val api = Repo.api()
        Repo.queue = eps.map {
            PlayItem(it.key, series.name, "${it.label}  « ${it.title} »", api.episodeUrl(it.id, it.ext),
                false, it.image ?: series.backdrop ?: series.icon, it.num, it.id, it)
        }
        Repo.queueIndex = index.coerceIn(0, eps.size - 1)
        Repo.resume = resume
        Repo.seriesId = series.id
        Prefs.pushRecent(series.key)
        Prefs.setLastEpisode(series.id, eps[Repo.queueIndex])
        open(ctx)
    }

    /**
     * Logique "Reprendre" d'une série : dernier épisode commencé,
     * ou le suivant s'il est terminé, sinon le tout premier.
     */
    fun resumeTarget(series: Item, d: Details): Pair<Int, Boolean> {
        val all = d.allEpisodes
        if (all.isEmpty()) return 0 to false
        val idx = all.indexOfFirst { it.id == Prefs.lastEpisode(series.id) }
        if (idx < 0) return 0 to false
        return if (Prefs.isWatched(all[idx].key) && idx + 1 < all.size) (idx + 1) to false else idx to true
    }

    fun series(ctx: Context, series: Item, d: Details) {
        val (idx, resume) = resumeTarget(series, d)
        episodes(ctx, series, d.allEpisodes, idx, resume)
    }

    private fun open(ctx: Context) = ctx.startActivity(Intent(ctx, PlayerActivity::class.java))
}
