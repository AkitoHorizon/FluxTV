package com.flux.tv.ui

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.flux.tv.R
import com.flux.tv.data.CardStyle
import com.flux.tv.data.Item
import com.flux.tv.data.Kind
import com.flux.tv.data.Repo
import com.flux.tv.data.Row
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer

/** Recherche Netflix : clavier à l'écran (pas besoin du clavier Android) + grille de résultats. */
class SearchActivity : BaseActivity(), CardListener {

    private lateinit var queryView: TextView
    private lateinit var status: TextView
    private lateinit var results: RecyclerView
    private val resultAdapter = CardAdapter(this)
    private var query = ""
    private var index: List<Triple<Item, String, Kind>> = emptyList()
    private var job: Job? = null
    private val accents = Regex("\\p{Mn}+")

    private val keys: List<String> = ('a'..'z').map { it.toString() } + ('1'..'9').map { it.toString() } + "0" + listOf(SPACE, BACK, CLEAR)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        queryView = findViewById(R.id.query)
        status = findViewById(R.id.status)
        results = findViewById(R.id.results)

        val kb = findViewById<RecyclerView>(R.id.keyboard)
        kb.layoutManager = GridLayoutManager(this, 6).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = when (keys[position]) { SPACE -> 3; BACK -> 2; else -> 1 }
            }
        }
        kb.adapter = KeyAdapter()
        kb.itemAnimator = null

        results.post {
            val span = ((results.width - results.paddingLeft - results.paddingRight) / (Cards.POSTER_W + 12).dp).coerceAtLeast(3)
            results.layoutManager = GridLayoutManager(this, span)
            results.adapter = resultAdapter
        }
        results.itemAnimator = null
        results.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                outRect.bottom = 18.dp
            }
        })

        status.text = "Préparation…"
        lifecycleScope.launch {
            val loaded = listOf(Kind.MOVIE, Kind.SERIES, Kind.LIVE)
                .map { k -> async { runCatching { Repo.load(k) }.getOrDefault(emptyList()) } }.awaitAll()
            index = withContext(Dispatchers.Default) { loaded.flatten().map { Triple(it, norm(it.name), it.kind) } }
            search()
        }
        kb.post { kb.getChildAt(0)?.requestFocus() }
    }

    private fun norm(s: String) = accents.replace(Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD), "")

    private fun type(k: String) {
        query = when (k) {
            SPACE -> if (query.isEmpty() || query.endsWith(" ")) query else "$query "
            BACK -> query.dropLast(1)
            CLEAR -> ""
            else -> query + k
        }
        queryView.text = query
        search()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Les claviers physiques / télécommandes à touches fonctionnent aussi
        if (event.action == KeyEvent.ACTION_DOWN) {
            val c = event.unicodeChar
            if (c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code) { type(c.toChar().lowercase()); return true }
            if (event.keyCode == KeyEvent.KEYCODE_DEL) { type(BACK); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun search() {
        job?.cancel()
        job = lifecycleScope.launch {
            delay(250)
            val q = norm(query.trim())
            val list = withContext(Dispatchers.Default) {
                if (q.length < 2) {
                    (Repo.top10(Kind.MOVIE) + Repo.top10(Kind.SERIES) + Repo.latest(Kind.MOVIE, 20) + Repo.latest(Kind.SERIES, 20)).distinctBy { it.key }
                } else {
                    val starts = ArrayList<Item>(); val words = ArrayList<Item>(); val inside = ArrayList<Item>()
                    for ((item, name, _) in index) {
                        when {
                            name.startsWith(q) -> starts += item
                            name.contains(" $q") -> words += item
                            name.contains(q) -> inside += item
                        }
                        if (starts.size + words.size + inside.size >= 300) break
                    }
                    (starts + words + inside).take(150)
                }
            }
            resultAdapter.submit(list, CardStyle.POSTER, Row("search", list, id = "search"))
            status.text = when {
                q.length < 2 -> "Suggestions pour vous"
                list.isEmpty() -> "Aucun résultat pour « ${query.trim()} »"
                else -> "Résultats pour « ${query.trim()} »"
            }
        }
    }

    override fun onCardClicked(item: Item, row: Row?) {
        if (item.kind == Kind.LIVE) {
            val list = Repo.list(Kind.LIVE).filter { it.categoryId == item.categoryId }.ifEmpty { listOf(item) }
            Play.live(this, list, list.indexOfFirst { it.key == item.key }.coerceAtLeast(0))
        } else startActivity(Intent(this, DetailActivity::class.java).putExtra(DetailActivity.EXTRA_KEY, item.key))
    }

    override fun onCardLongPressed(item: Item, row: Row?) { toggleFavorite(item) }

    private inner class KeyAdapter : RecyclerView.Adapter<KeyAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val text: TextView = v.findViewById(R.id.keyText)
            val icon: ImageView = v.findViewById(R.id.keyIcon)
        }

        override fun getItemCount() = keys.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_key, parent, false)
            val vh = VH(v)
            v.setOnClickListener { keys.getOrNull(vh.bindingAdapterPosition)?.let { type(it) } }
            return vh
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val k = keys[position]
            val icon = when (k) { SPACE -> R.drawable.ic_space; BACK -> R.drawable.ic_backspace; CLEAR -> R.drawable.ic_close; else -> 0 }
            h.icon.show(icon != 0); h.text.show(icon == 0)
            if (icon != 0) h.icon.setImageResource(icon) else h.text.text = k.uppercase()
        }
    }

    companion object {
        private const val SPACE = "␣"
        private const val BACK = "⌫"
        private const val CLEAR = "✕"
    }
}
