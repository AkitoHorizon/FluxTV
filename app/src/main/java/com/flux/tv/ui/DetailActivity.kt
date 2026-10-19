package com.flux.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.flux.tv.R
import com.flux.tv.data.CardStyle
import com.flux.tv.data.Details
import com.flux.tv.data.Episode
import com.flux.tv.data.Item
import com.flux.tv.data.Kind
import com.flux.tv.data.Prefs
import com.flux.tv.data.Repo
import com.flux.tv.data.Row
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Fiche d'un film ou d'une série : reprise, saisons, épisodes vus, titres similaires. */
class DetailActivity : BaseActivity(), CardListener {

    private lateinit var item: Item
    private lateinit var backdrop: ImageView
    private lateinit var kicker: TextView
    private lateinit var title: TextView
    private lateinit var meta: TextView
    private lateinit var plot: TextView
    private lateinit var cast: TextView
    private lateinit var btnPlay: View
    private lateinit var btnPlayText: TextView
    private lateinit var playProgress: ProgressBar
    private lateinit var btnRestart: View
    private lateinit var btnList: ImageView
    private lateinit var btnLike: ImageView
    private lateinit var hint: TextView
    private lateinit var seriesBox: View
    private lateinit var seasonsRv: RecyclerView
    private lateinit var episodesRv: RecyclerView
    private lateinit var epInfo: TextView
    private lateinit var busy: View
    private lateinit var ambient: Ambient

    private var details: Details? = null
    private var seasonKeys: List<Int> = emptyList()
    private var seasonIndex = 0
    private var episodes: List<Episode> = emptyList()
    private var nextUpId: String? = null
    private val seasonAdapter = SeasonAdapter()
    private val episodeAdapter = EpisodeAdapter()
    private val similarAdapter = CardAdapter(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        item = Repo.find(intent.getStringExtra(EXTRA_KEY) ?: "") ?: run { finish(); return }
        setContentView(R.layout.activity_detail)
        backdrop = findViewById(R.id.backdrop)
        kicker = findViewById(R.id.kicker)
        title = findViewById(R.id.title)
        meta = findViewById(R.id.meta)
        plot = findViewById(R.id.plot)
        cast = findViewById(R.id.cast)
        btnPlay = findViewById(R.id.btnPlay)
        btnPlayText = findViewById(R.id.btnPlayText)
        playProgress = findViewById(R.id.playProgress)
        btnRestart = findViewById(R.id.btnRestart)
        btnList = findViewById(R.id.btnList)
        btnLike = findViewById(R.id.btnLike)
        hint = findViewById(R.id.hint)
        seriesBox = findViewById(R.id.seriesBox)
        seasonsRv = findViewById(R.id.seasons)
        episodesRv = findViewById(R.id.episodes)
        epInfo = findViewById(R.id.epInfo)
        busy = findViewById(R.id.busy)
        ambient = Ambient(findViewById(R.id.root), findViewById(R.id.fadeLeft), findViewById(R.id.fadeBottom))

        kicker.text = Fmt.kicker(item.kind)
        title.textSize = Fmt.titleSize(item.name, 60f)
        title.text = item.name

        btnPlay.setOnClickListener { play(resume = true) }
        btnRestart.setOnClickListener { play(resume = false) }
        btnList.setOnClickListener { toggleFavorite(item); updateButtons(); updateHint(btnList) }
        btnLike.setOnClickListener {
            val liked = Prefs.toggleLike(item.key)
            toast(if (liked) "Vous aimez ce titre" else "Évaluation retirée")
            updateButtons(); updateHint(btnLike)
        }
        for (v in listOf(btnList, btnLike)) v.setOnFocusChangeListener { view, has -> if (has) updateHint(view) else hint.animate().alpha(0f).setDuration(150).start() }

        seasonsRv.layoutManager = FocusAlignLayoutManager(this, RecyclerView.HORIZONTAL)
        seasonsRv.adapter = seasonAdapter
        seasonsRv.itemAnimator = null
        episodesRv.layoutManager = FocusAlignLayoutManager(this, RecyclerView.HORIZONTAL)
        episodesRv.adapter = episodeAdapter
        episodesRv.itemAnimator = null
        episodesRv.setHasFixedSize(true)

        val similar = findViewById<RecyclerView>(R.id.similar)
        similar.layoutManager = FocusAlignLayoutManager(this, RecyclerView.HORIZONTAL)
        similar.adapter = similarAdapter
        similar.itemAnimator = null
        val sim = Repo.similar(item, 30)
        if (sim.isNotEmpty()) {
            similarAdapter.submit(sim, CardStyle.POSTER, Row("Titres similaires", sim, id = "similar"))
            similar.show(true); findViewById<View>(R.id.similarTitle).show(true)
        }

        render(Repo.cachedDetails(item))
        updateButtons()
        btnPlay.requestFocus()

        if (details == null) lifecycleScope.launch {
            try {
                render(Repo.details(item))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (item.kind == Kind.SERIES) epInfo.text = "Impossible de charger les épisodes : ${e.message}"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::btnPlay.isInitialized) {
            updateButtons()
            episodeAdapter.notifyDataSetChanged()
        }
    }

    // ------------------------------------------------------------------ affichage

    private fun render(d: Details?) {
        details = d
        meta.text = Fmt.meta(item, d)
        plot.text = d?.plot ?: item.plot ?: ""
        cast.text = listOfNotNull(d?.cast?.let { "Avec : $it" }, d?.director?.let { "Réalisation : $it" }).joinToString("\n")
        val wide = d?.backdrop ?: item.backdrop
        val url = wide ?: item.icon
        if (backdrop.tag != url) {
            backdrop.tag = url
            Img.hero(backdrop, url, isPoster = wide == null)
            ambient.fromUrl(url)
        }
        if (item.kind == Kind.SERIES && d != null) {
            seasonKeys = d.seasons.keys.toList()
            if (seasonKeys.isEmpty()) { seriesBox.show(true); epInfo.text = "Aucun épisode disponible pour le moment."; return }
            val (target, _) = Play.resumeTarget(item, d)
            nextUpId = d.allEpisodes.getOrNull(target)?.id
            val seasonOfTarget = d.allEpisodes.getOrNull(target)?.season
            seasonIndex = seasonKeys.indexOf(seasonOfTarget).coerceAtLeast(0)
            episodes = d.seasons[seasonKeys[seasonIndex]].orEmpty()
            seriesBox.fade(true)
            seasonAdapter.notifyDataSetChanged()
            episodeAdapter.notifyDataSetChanged()
            val pos = episodes.indexOfFirst { it.id == nextUpId }
            if (pos > 0) episodesRv.scrollToPosition(pos)
            updateButtons()
        }
    }

    private fun selectSeason(i: Int) {
        val d = details ?: return
        if (i !in seasonKeys.indices || i == seasonIndex) return
        seasonIndex = i
        episodes = d.seasons[seasonKeys[i]].orEmpty()
        seasonAdapter.notifyDataSetChanged()
        episodeAdapter.notifyDataSetChanged()
        episodesRv.scrollToPosition(0)
    }

    private fun updateButtons() {
        val fav = Prefs.isFav(item.key)
        btnList.setImageResource(if (fav) R.drawable.ic_check else R.drawable.ic_add)
        val liked = Prefs.isLiked(item.key)
        btnLike.imageTintList = ContextCompat.getColorStateList(this, if (liked) R.color.like_fg else R.color.on_btn)

        if (item.kind == Kind.MOVIE) {
            val p = Prefs.progress(item.key)
            val resumable = p in 0.01f..0.95f
            btnPlayText.text = if (resumable) "Reprendre" else "Lecture"
            btnRestart.show(resumable)
            showProgress(if (resumable) p else -1f)
        } else {
            val d = details
            val metaEp = Prefs.episodeMeta(item.id)
            if (d != null && d.allEpisodes.isNotEmpty()) {
                val (idx, resume) = Play.resumeTarget(item, d)
                val ep = d.allEpisodes[idx]
                btnPlayText.text = when {
                    resume -> "Reprendre ${ep.label}"
                    metaEp != null -> "Lecture ${ep.label}"
                    else -> "Lecture"
                }
                showProgress(if (resume) Prefs.progress(ep.key) else -1f)
            } else {
                btnPlayText.text = metaEp?.let { "Reprendre S${it.season}:É${it.num}" } ?: "Lecture"
                showProgress(-1f)
            }
            btnRestart.show(false)
        }
    }

    private fun showProgress(p: Float) {
        if (p > 0f) { playProgress.progress = (p * 1000).toInt(); playProgress.visibility = View.VISIBLE }
        else playProgress.visibility = View.INVISIBLE
    }

    private fun updateHint(v: View) {
        hint.text = when (v) {
            btnList -> if (Prefs.isFav(item.key)) "Retirer de Ma liste" else "Ajouter à Ma liste"
            btnLike -> if (Prefs.isLiked(item.key)) "Vous aimez ce titre" else "J'aime"
            else -> ""
        }
        hint.animate().alpha(1f).setDuration(180).start()
    }

    // ------------------------------------------------------------------ lecture

    private fun play(resume: Boolean) {
        if (item.kind == Kind.MOVIE) { Play.movie(this, item, resume); return }
        val d = details
        if (d != null) { if (d.allEpisodes.isNotEmpty()) Play.series(this, item, d) else toast("Aucun épisode disponible"); return }
        busy.fade(true)
        lifecycleScope.launch {
            try {
                val loaded = Repo.details(item)
                render(loaded)
                if (loaded.allEpisodes.isNotEmpty()) Play.series(this@DetailActivity, item, loaded)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Impossible de charger les épisodes")
            } finally {
                busy.fade(false)
            }
        }
    }

    private fun playEpisode(ep: Episode) {
        val all = details?.allEpisodes ?: return
        Play.episodes(this, item, all, all.indexOf(ep), resume = !Prefs.isWatched(ep.key))
    }

    /** "Ma Série - S01E03 - Le titre" → "Le titre". */
    private fun cleanTitle(ep: Episode): String {
        val t = ep.title.replace(Regex("^.*?[Ss]\\d{1,2}\\s*[EeÉé]\\d{1,3}\\s*[-–:·]*\\s*"), "").trim()
        return t.ifEmpty { "Épisode ${ep.num}" }
    }

    // ------------------------------------------------------------------ titres similaires

    override fun onCardClicked(item: Item, row: Row?) {
        startActivity(Intent(this, DetailActivity::class.java).putExtra(EXTRA_KEY, item.key))
    }

    override fun onCardLongPressed(item: Item, row: Row?) { toggleFavorite(item) }

    // ------------------------------------------------------------------ adapters

    private inner class SeasonAdapter : RecyclerView.Adapter<SeasonAdapter.VH>() {
        inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

        override fun getItemCount() = seasonKeys.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = LayoutInflater.from(parent.context).inflate(R.layout.item_chip, parent, false) as TextView
            val vh = VH(tv)
            tv.setOnFocusChangeListener { _, has ->
                val pos = vh.bindingAdapterPosition
                if (has && pos != RecyclerView.NO_POSITION) selectSeason(pos)
            }
            tv.setOnClickListener { episodesRv.getChildAt(0)?.requestFocus() }
            return vh
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val s = seasonKeys[position]
            val count = details?.seasons?.get(s)?.size ?: 0
            h.tv.text = (if (s == 0) "Bonus" else "Saison $s") + if (count > 0) "  ·  $count" else ""
            h.tv.isSelected = position == seasonIndex
        }
    }

    private inner class EpisodeAdapter : RecyclerView.Adapter<EpisodeAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: View = v.findViewById(R.id.thumb)
            val img: ImageView = v.findViewById(R.id.epImage)
            val play: View = v.findViewById(R.id.epPlay)
            val watched: View = v.findViewById(R.id.epWatched)
            val next: View = v.findViewById(R.id.epNext)
            val progress: ProgressBar = v.findViewById(R.id.epProgress)
            val title: TextView = v.findViewById(R.id.epTitle)
            val meta: TextView = v.findViewById(R.id.epMeta)
        }

        override fun getItemCount() = episodes.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false)
            val vh = VH(v)
            vh.thumb.clipToOutline = true
            v.setOnFocusChangeListener { view, has ->
                view.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).translationZ(if (has) 8f else 0f).setDuration(140).start()
                vh.play.animate().alpha(if (has) 1f else 0f).setDuration(160).start()
                val ep = episodes.getOrNull(vh.bindingAdapterPosition) ?: return@setOnFocusChangeListener
                if (has) epInfo.text = listOfNotNull("${ep.label}  ·  ${cleanTitle(ep)}", ep.plot).joinToString("\n")
            }
            v.setOnClickListener { episodes.getOrNull(vh.bindingAdapterPosition)?.let { playEpisode(it) } }
            return vh
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val ep = episodes[position]
            h.title.text = "${ep.num}.  ${cleanTitle(ep)}"
            Img.card(h.img, ep.image ?: details?.backdrop ?: item.backdrop ?: item.icon, 256.dp, 144.dp)
            val p = Prefs.progress(ep.key)
            val watched = Prefs.isWatched(ep.key)
            h.watched.show(watched)
            h.next.show(ep.id == nextUpId && !watched)
            if (p in 0.01f..1f) { h.progress.progress = (p * 1000).toInt(); h.progress.show(true) } else h.progress.show(false)
            h.meta.text = listOfNotNull(
                Fmt.duration(ep.duration),
                when {
                    watched -> "Vu"
                    p > 0.01f -> Fmt.remaining(Prefs.remainingMs(ep.key))
                    else -> null
                },
            ).joinToString("  •  ")
        }

        override fun onViewRecycled(holder: VH) = Img.clear(holder.img)
    }

    companion object {
        const val EXTRA_KEY = "key"
    }
}
