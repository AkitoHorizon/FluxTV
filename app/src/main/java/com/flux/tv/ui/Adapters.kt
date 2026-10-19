package com.flux.tv.ui

import android.os.Parcelable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.flux.tv.R
import com.flux.tv.data.CardStyle
import com.flux.tv.data.Item
import com.flux.tv.data.Kind
import com.flux.tv.data.Prefs
import com.flux.tv.data.Repo
import com.flux.tv.data.Row

interface CardListener {
    fun onCardFocused(item: Item, row: Row?) {}
    fun onCardClicked(item: Item, row: Row?)
    fun onCardLongPressed(item: Item, row: Row?) {}
}

object Cards {
    fun width(style: Int) = when (style) {
        CardStyle.LOGO -> 168.dp
        CardStyle.WIDE -> 236.dp
        CardStyle.TOP10 -> 196.dp
        else -> 116.dp
    }
    fun height(style: Int) = when (style) {
        CardStyle.LOGO -> 95.dp
        CardStyle.WIDE -> 133.dp
        else -> 174.dp
    }
    const val POSTER_W = 116
}

/** Une carte : affiche, logo de chaîne, vignette "Reprendre" ou Top 10. */
class CardAdapter(private val listener: CardListener) : RecyclerView.Adapter<CardAdapter.VH>() {
    private var items: List<Item> = emptyList()
    private var style = CardStyle.POSTER
    private var row: Row? = null

    fun submit(list: List<Item>, style: Int, row: Row? = null) {
        items = list; this.style = style; this.row = row
        notifyDataSetChanged()
    }

    class VH(v: View, val style: Int) : RecyclerView.ViewHolder(v) {
        val frame: FrameLayout = v.findViewById(R.id.frame)
        val img: ImageView = v.findViewById(R.id.cardImage)
        val shade: View = v.findViewById(R.id.cardShade)
        val textBox: View = v.findViewById(R.id.cardText)
        val title: TextView = v.findViewById(R.id.cardTitle)
        val sub: TextView = v.findViewById(R.id.cardSub)
        val progress: ProgressBar = v.findViewById(R.id.cardProgress)
        val badgeNew: TextView = v.findViewById(R.id.badgeNew)
        val badgeTop: TextView = v.findViewById(R.id.badgeTop)
        var number: StrokeNumberView? = null
        var item: Item? = null
    }

    override fun getItemCount() = items.size
    override fun getItemViewType(position: Int) = style

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_card, parent, false)
        v.layoutParams = RecyclerView.LayoutParams(Cards.width(viewType), Cards.height(viewType)).apply {
            marginEnd = if (viewType == CardStyle.TOP10) 6.dp else 12.dp
        }
        val vh = VH(v, viewType)
        vh.frame.clipToOutline = true
        when (viewType) {
            CardStyle.TOP10 -> {
                vh.frame.layoutParams = FrameLayout.LayoutParams(Cards.POSTER_W.dp, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END)
                val n = StrokeNumberView(parent.context)
                (v as FrameLayout).addView(n, 0, FrameLayout.LayoutParams(96.dp, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START))
                vh.number = n
            }
            CardStyle.LOGO -> {
                vh.frame.setBackgroundResource(R.drawable.bg_logo_card)
                vh.img.scaleType = ImageView.ScaleType.FIT_CENTER
                vh.img.setPadding(22.dp, 12.dp, 22.dp, 28.dp)
            }
        }
        val scale = when (viewType) { CardStyle.WIDE -> 1.07f; CardStyle.TOP10 -> 1.08f; else -> 1.11f }
        v.setOnFocusChangeListener { view, has ->
            view.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f)
                .translationZ(if (has) 10f else 0f).setDuration(140).start()
            if (has) vh.item?.let { listener.onCardFocused(it, row) }
        }
        v.setOnClickListener { vh.item?.let { listener.onCardClicked(it, row) } }
        v.setOnLongClickListener { vh.item?.let { listener.onCardLongPressed(it, row) }; true }
        return vh
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val it = items[position]
        h.item = it
        h.badgeNew.show(false); h.badgeTop.show(false); h.progress.show(false)
        h.shade.show(false); h.textBox.show(false); h.sub.show(false)
        h.title.text = it.name

        when (h.style) {
            CardStyle.WIDE -> bindWide(h, it)
            CardStyle.LOGO -> {
                Img.card(h.img, it.icon, Cards.width(h.style), Cards.height(h.style))
                h.textBox.show(true)
                h.title.textSize = 11f
                h.title.maxLines = 1
            }
            else -> {
                h.number?.number = (position + 1).toString()
                val live = it.kind == Kind.LIVE
                h.img.scaleType = if (live) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
                val pad = if (live) 16.dp else 0
                h.img.setPadding(pad, pad, pad, pad)
                Img.card(h.img, it.icon, Cards.POSTER_W.dp, Cards.height(CardStyle.POSTER))
                if (it.icon == null || live) { h.shade.show(true); h.textBox.show(true) }
                if (h.style == CardStyle.POSTER && it.isNew && !live) h.badgeNew.show(true)
                else if (h.style == CardStyle.POSTER && Repo.isTop10(it)) h.badgeTop.show(true)
                if (it.kind == Kind.MOVIE) bindProgress(h, it.key)
            }
        }
    }

    private fun bindWide(h: VH, it: Item) {
        h.shade.show(true); h.textBox.show(true); h.sub.show(true)
        if (it.kind == Kind.SERIES) {
            val meta = Prefs.episodeMeta(it.id)
            Img.card(h.img, meta?.image ?: it.backdrop ?: it.icon, Cards.width(h.style), Cards.height(h.style))
            if (meta != null) {
                val key = "e:${meta.episodeId}"
                val rest = Prefs.remainingMs(key)
                h.sub.text = if (Prefs.isWatched(key)) "S${meta.season}:É${meta.num} terminé · épisode suivant"
                else "S${meta.season}:É${meta.num}" + if (rest > 0) " · ${Fmt.remaining(rest)}" else ""
                bindProgress(h, key)
            } else h.sub.text = "Commencer la série"
        } else {
            Img.card(h.img, it.backdrop ?: it.icon, Cards.width(h.style), Cards.height(h.style))
            val rest = Prefs.remainingMs(it.key)
            h.sub.text = if (rest > 0) Fmt.remaining(rest) else "Film"
            bindProgress(h, it.key)
        }
    }

    private fun bindProgress(h: VH, key: String) {
        val p = Prefs.progress(key)
        if (p in 0.01f..0.99f) { h.progress.progress = (p * 1000).toInt(); h.progress.show(true) }
    }

    override fun onViewRecycled(h: VH) {
        Img.clear(h.img)
        h.itemView.animate().cancel()
        h.itemView.scaleX = 1f; h.itemView.scaleY = 1f; h.itemView.translationZ = 0f
    }
}

/** Rangées horizontales empilées (accueil, films, séries…). */
class RowsAdapter(private val listener: CardListener) : RecyclerView.Adapter<RowsAdapter.VH>() {
    var rows: List<Row> = emptyList()
    private val pool = RecyclerView.RecycledViewPool().apply {
        setMaxRecycledViews(CardStyle.POSTER, 40)
        setMaxRecycledViews(CardStyle.LOGO, 24)
        setMaxRecycledViews(CardStyle.WIDE, 16)
        setMaxRecycledViews(CardStyle.TOP10, 16)
    }
    private val scrollStates = HashMap<String, Parcelable?>()

    fun submit(r: List<Row>, recycler: RecyclerView? = null) {
        rows = r; scrollStates.clear()
        notifyDataSetChanged()
        recycler?.let {
            it.layoutAnimation = AnimationUtils.loadLayoutAnimation(it.context, R.anim.rows_layout)
            it.scheduleLayoutAnimation()
        }
    }

    inner class VH(v: View, style: Int) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.rowTitle)
        val list: RecyclerView = v.findViewById(R.id.rowList)
        val adapter = CardAdapter(listener)
        val lm = FocusAlignLayoutManager(v.context, RecyclerView.HORIZONTAL).apply { initialPrefetchItemCount = 8 }
        var rowId: String? = null

        init {
            list.layoutManager = lm
            list.adapter = adapter
            list.setRecycledViewPool(pool)
            list.setHasFixedSize(true)
            list.setItemViewCacheSize(6)
            list.itemAnimator = null
            list.layoutParams.height = Cards.height(style) + 30.dp
        }
    }

    override fun getItemCount() = rows.size
    override fun getItemViewType(position: Int) = rows[position].style

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false), viewType)

    override fun onBindViewHolder(h: VH, position: Int) {
        val row = rows[position]
        h.rowId = row.id
        h.title.text = row.title
        h.adapter.submit(row.items, row.style, row)
        val state = scrollStates[row.id]
        if (state != null) h.lm.onRestoreInstanceState(state) else h.lm.scrollToPosition(0)
    }

    override fun onViewRecycled(h: VH) {
        h.rowId?.let { scrollStates[it] = h.lm.onSaveInstanceState() }
    }
}
