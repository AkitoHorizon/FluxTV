package com.flux.tv.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.flux.tv.R
import com.flux.tv.data.Item
import com.flux.tv.data.Kind
import com.flux.tv.data.PlayItem
import com.flux.tv.data.Prefs
import com.flux.tv.data.Repo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Guide TV : catégories | chaînes | aperçu vidéo en direct + programme en cours. */
@OptIn(UnstableApi::class)
class LiveActivity : BaseActivity() {

    private class Cat(val id: String, val name: String)

    private lateinit var catList: RecyclerView
    private lateinit var langFilter: TextView
    private lateinit var chList: RecyclerView
    private lateinit var previewView: PlayerView
    private lateinit var previewLogo: ImageView
    private lateinit var previewSpinner: View
    private lateinit var liveBadge: View
    private lateinit var chName: TextView
    private lateinit var epgNow: TextView
    private lateinit var epgDesc: TextView
    private lateinit var epgNext: TextView
    private lateinit var epgProgress: ProgressBar
    private lateinit var loading: View

    private var cats: List<Cat> = emptyList()
    private var all: List<Item> = emptyList()
    private var current: List<Item> = emptyList()
    private var selectedCat = -1
    private var focused: Item? = null
    private var preview: ExoPlayer? = null
    private var catJob: Job? = null
    private var chJob: Job? = null
    private var openedPlayer = false
    private var ready = false          // ignore les focus automatiques pendant le chargement
    private lateinit var chEmpty: TextView
    private val catAdapter = CatAdapter()
    private val chAdapter = ChAdapter()
    private val hhmm = SimpleDateFormat("HH:mm", Locale.FRANCE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live)
        catList = findViewById(R.id.categories)
        langFilter = findViewById(R.id.langFilter)
        updateLangLabel()
        langFilter.setOnClickListener {
            showMenu("Langue des chaînes", "La recherche trouve toujours toutes les chaînes.", listOf(
                "Français uniquement" to { setLang(true) },
                "Toutes les langues" to { setLang(false) },
            ))
        }
        chList = findViewById(R.id.channels)
        previewView = findViewById(R.id.preview)
        previewLogo = findViewById(R.id.previewLogo)
        previewSpinner = findViewById(R.id.previewSpinner)
        liveBadge = findViewById(R.id.liveBadge)
        chName = findViewById(R.id.chName)
        epgNow = findViewById(R.id.epgNow)
        epgDesc = findViewById(R.id.epgDesc)
        epgNext = findViewById(R.id.epgNext)
        epgProgress = findViewById(R.id.epgProgress)
        loading = findViewById(R.id.loading)
        chEmpty = findViewById(R.id.chEmpty)

        catList.layoutManager = LinearLayoutManager(this)
        catList.adapter = catAdapter
        catList.itemAnimator = null
        chList.layoutManager = LinearLayoutManager(this).apply { initialPrefetchItemCount = 12 }
        chList.adapter = chAdapter
        chList.itemAnimator = null
        chList.setHasFixedSize(true)
        chList.setItemViewCacheSize(10)

        val box = findViewById<View>(R.id.previewBox)
        box.clipToOutline = true
        box.post { box.layoutParams.height = box.width * 9 / 16; box.requestLayout() }

        load()
    }

    private fun load() {
        lifecycleScope.launch {
            loading.show(true)
            try {
                all = Repo.load(Kind.LIVE)
                val list = ArrayList<Cat>()
                if (Prefs.recentChannels().isNotEmpty()) list += Cat(RECENT, "Récemment regardées")
                list += Cat(FAV, "★  Favoris")
                list += Cat(ALL, "Toutes les chaînes")
                Repo.categories(Kind.LIVE).forEach { list += Cat(it.id, it.name) }
                cats = list
                catAdapter.notifyDataSetChanged()
                val start = when {
                    Prefs.favorites().any { it.startsWith("l:") } -> cats.indexOfFirst { it.id == FAV }
                    Prefs.recentChannels().isNotEmpty() -> 0
                    else -> cats.indexOfFirst { it.id == ALL }
                }
                selectCategory(start)
                val last = current.indexOfFirst { it.key == Prefs.lastChannel }.coerceAtLeast(0)
                chList.scrollToPosition(last)
                focusChannel(last) { ready = true }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                chName.text = "Impossible de charger les chaînes"
                epgDesc.text = e.message ?: ""
            } finally {
                loading.show(false)
            }
        }
    }

    private fun setLang(on: Boolean) {
        lifecycleScope.launch { Repo.setFrenchOnly(Kind.LIVE, on); updateLangLabel(); load() }
    }

    private fun updateLangLabel() {
        langFilter.text = if (Prefs.frenchOnly(Kind.LIVE)) "Langue : Français ▾" else "Langue : toutes ▾"
    }

    private fun selectCategory(i: Int) {
        if (i !in cats.indices) return
        val previous = selectedCat
        selectedCat = i
        // Seules les deux lignes concernées sont redessinées : redessiner toute la liste
        // faisait perdre le focus, qui retombait sur « Favoris »
        if (previous in cats.indices) catAdapter.notifyItemChanged(previous)
        catAdapter.notifyItemChanged(i)
        current = when (val id = cats[i].id) {
            RECENT -> Prefs.recentChannels().mapNotNull { Repo.find(it) }
            FAV -> Prefs.favorites().let { f -> all.filter { it.key in f } }
            ALL -> all
            else -> all.filter { it.categoryId == id }
        }
        chAdapter.notifyDataSetChanged()
        chList.scrollToPosition(0)
        chEmpty.text = when (cats[i].id) {
            FAV -> "Aucune chaîne favorite pour l'instant.\nAppui long sur OK sur une chaîne pour l'ajouter."
            RECENT -> "Aucune chaîne regardée récemment."
            else -> "Aucune chaîne dans cette catégorie."
        }
        chEmpty.show(current.isEmpty())
    }

    private fun focusChannel(pos: Int, attempt: Int = 0, done: (() -> Unit)? = null) {
        if (isFinishing || isDestroyed) return   // écran déjà fermé (retour rapide, rotation…)
        val v = chList.findViewHolderForAdapterPosition(pos)?.itemView
        if (v != null) { v.requestFocus(); done?.invoke() }
        else if (attempt < 30) chList.postDelayed({ focusChannel(pos, attempt + 1, done) }, 50)
        else { catList.getChildAt(0)?.requestFocus(); done?.invoke() }
    }

    override fun onStart() {
        super.onStart()
        focused?.let { f -> chJob?.cancel(); chJob = lifecycleScope.launch { delay(500); startPreview(f) } }
    }

    override fun onResume() {
        super.onResume()
        if (openedPlayer) {
            openedPlayer = false
            val idx = Repo.queueIndex
            if (idx in current.indices) { chList.scrollToPosition(idx); focusChannel(idx) }
        }
    }

    override fun onStop() {
        releasePreview()
        super.onStop()
    }

    private fun onChannelFocused(item: Item) {
        focused = item
        chName.text = if (item.num > 0) "${item.num}   ${item.name}" else item.name
        epgNow.text = ""; epgDesc.text = ""; epgNext.text = ""
        epgProgress.visibility = View.INVISIBLE
        Img.card(previewLogo, item.icon, 320, 180)
        previewLogo.show(true)
        chJob?.cancel()
        chJob = lifecycleScope.launch {
            delay(600)                      // on attend que le défilement s'arrête
            startPreview(item)
            loadEpg(item)
        }
    }

    private fun startPreview(item: Item) {
        if (!Prefs.livePreview) return
        val p = preview ?: PlayerFactory.create(this, preview = true).also {
            preview = it
            previewView.player = it
            it.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    previewSpinner.show(state == Player.STATE_BUFFERING)
                }
                override fun onRenderedFirstFrame() {
                    Log.i("FluxQA", "preview firstFrame")
                    previewLogo.fade(false); liveBadge.fade(true)
                }
                override fun onPlayerError(error: PlaybackException) {
                    previewSpinner.show(false); previewLogo.show(true); liveBadge.show(false)
                }
            })
        }
        liveBadge.show(false)
        p.setMediaItem(PlayerFactory.mediaItem(PlayItem(item.key, item.name, null, Repo.api().liveUrl(item.id, item.direct), true, item.icon, item.num, item.id)))
        p.prepare()
        p.play()
    }

    private fun releasePreview() {
        chJob?.cancel()
        preview?.release()
        preview = null
        previewView.player = null
        liveBadge.show(false)
        previewSpinner.show(false)
        previewLogo.show(true)
    }

    private suspend fun loadEpg(item: Item) {
        val now = System.currentTimeMillis() / 1000
        val list = runCatching { Repo.api().shortEpg(item.id) }.getOrNull().orEmpty().filter { it.end > now }
        if (focused !== item) return
        val e = list.getOrNull(0)
        if (e == null) { epgNow.text = "Programme non communiqué"; return }
        epgNow.text = "${hhmm.format(Date(e.start * 1000))} – ${hhmm.format(Date(e.end * 1000))}    ${e.title}"
        epgDesc.text = e.desc ?: ""
        if (e.end > e.start) {
            epgProgress.progress = (((now - e.start).toFloat() / (e.end - e.start)) * 1000).toInt().coerceIn(0, 1000)
            epgProgress.visibility = View.VISIBLE
        }
        list.getOrNull(1)?.let { n -> epgNext.text = "À suivre  ${hhmm.format(Date(n.start * 1000))}   ${n.title}" }
    }

    private fun openFullscreen(pos: Int) {
        releasePreview()
        openedPlayer = true
        Play.live(this, current, pos)
    }

    // ------------------------------------------------------------------ adapters

    private inner class CatAdapter : RecyclerView.Adapter<CatAdapter.VH>() {
        inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

        override fun getItemCount() = cats.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false) as TextView
            val vh = VH(tv)
            tv.setOnFocusChangeListener { _, has ->
                if (!has || !ready) return@setOnFocusChangeListener
                val pos = vh.bindingAdapterPosition
                catJob?.cancel()
                catJob = lifecycleScope.launch { delay(220); if (pos != selectedCat) selectCategory(pos) }
            }
            tv.setOnClickListener {
                val pos = vh.bindingAdapterPosition
                catJob?.cancel()
                if (pos != selectedCat) selectCategory(pos)
                if (current.isNotEmpty()) focusChannel(0)
            }
            return vh
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            h.tv.text = cats[position].name
            h.tv.isSelected = position == selectedCat
        }
    }

    private inner class ChAdapter : RecyclerView.Adapter<ChAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val num: TextView = v.findViewById(R.id.chNum)
            val logo: ImageView = v.findViewById(R.id.chLogo)
            val title: TextView = v.findViewById(R.id.chTitle)
            val fav: View = v.findViewById(R.id.chFav)
        }

        override fun getItemCount() = current.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
            val vh = VH(v)
            v.setOnFocusChangeListener { _, has ->
                if (has) current.getOrNull(vh.bindingAdapterPosition)?.let { onChannelFocused(it) }
            }
            v.setOnClickListener { val p = vh.bindingAdapterPosition; if (p in current.indices) openFullscreen(p) }
            v.setOnLongClickListener {
                val p = vh.bindingAdapterPosition
                current.getOrNull(p)?.let { item -> toggleFavorite(item); notifyItemChanged(p) }
                true
            }
            return vh
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val it = current[position]
            h.num.text = if (it.num > 0) it.num.toString() else ""
            h.title.text = it.name
            h.fav.show(Prefs.isFav(it.key))
            Img.card(h.logo, it.icon, 136, 80)
        }

        override fun onViewRecycled(holder: VH) = Img.clear(holder.logo)
    }

    companion object {
        private const val RECENT = "__recent"
        private const val FAV = "__fav"
        private const val ALL = "__all"
    }
}
