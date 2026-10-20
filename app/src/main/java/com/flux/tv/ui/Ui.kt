package com.flux.tv.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.palette.graphics.Palette
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.flux.tv.R
import com.flux.tv.data.Details
import com.flux.tv.data.Item
import com.flux.tv.data.Kind
import com.flux.tv.data.Prefs
import java.util.concurrent.TimeUnit

// ====================================================================== base

val Int.dp: Int get() = (this * Resources.getSystem().displayMetrics.density + 0.5f).toInt()
val Float.dpf: Float get() = this * Resources.getSystem().displayMetrics.density

fun View.show(visible: Boolean) {
    visibility = if (visible) View.VISIBLE else View.GONE
}

/** Fondu d'apparition/disparition. */
fun View.fade(visible: Boolean, duration: Long = 220, gone: Boolean = true) {
    animate().cancel()
    if (visible) {
        if (visibility != View.VISIBLE) { alpha = 0f; visibility = View.VISIBLE }
        animate().alpha(1f).setDuration(duration).start()
    } else if (visibility == View.VISIBLE) {
        animate().alpha(0f).setDuration(duration).withEndAction { visibility = if (gone) View.GONE else View.INVISIBLE }.start()
    }
}

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

fun Context.toggleFavorite(item: Item): Boolean {
    val added = Prefs.toggleFav(item.key)
    toast(if (added) "Ajouté à Ma liste" else "Retiré de Ma liste")
    return added
}

/** Session en cours (profil choisi depuis le démarrage du boîtier). */
object Session {
    var profileChosen = false
}

/** Plein écran immersif sur tous les écrans. */
open class BaseActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        immersive()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    @Suppress("DEPRECATION")
    protected fun immersive() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }
}

// ====================================================================== polices

object Fonts {
    lateinit var regular: Typeface
    lateinit var medium: Typeface
    lateinit var semibold: Typeface
    lateinit var bold: Typeface
    lateinit var black: Typeface
    lateinit var display: Typeface

    fun init(c: Context) {
        regular = ResourcesCompat.getFont(c, R.font.inter_regular) ?: Typeface.DEFAULT
        medium = ResourcesCompat.getFont(c, R.font.inter_medium) ?: Typeface.DEFAULT
        semibold = ResourcesCompat.getFont(c, R.font.inter_semibold) ?: Typeface.DEFAULT_BOLD
        bold = ResourcesCompat.getFont(c, R.font.inter_bold) ?: Typeface.DEFAULT_BOLD
        black = ResourcesCompat.getFont(c, R.font.inter_display_black) ?: Typeface.DEFAULT_BOLD
        display = ResourcesCompat.getFont(c, R.font.display_bold) ?: Typeface.DEFAULT_BOLD
    }
}

// ====================================================================== images

object Img {
    private val opts = RequestOptions()
        .format(DecodeFormat.PREFER_RGB_565)          // moitié moins de RAM
        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)

    fun card(iv: ImageView, url: String?, w: Int, h: Int) {
        val g = Glide.with(iv)
        if (url == null) { g.clear(iv); iv.setImageDrawable(null); return }
        g.load(url).apply(opts).override(w, h)
            .transition(DrawableTransitionOptions.withCrossFade(180))
            .into(iv)
    }

    /** Grand visuel : fondu enchaîné + lent zoom "Ken Burns". */
    fun hero(iv: ImageView, url: String?, isPoster: Boolean) {
        val g = Glide.with(iv)
        if (url == null) { g.clear(iv); iv.setImageDrawable(null); return }
        iv.scaleType = if (isPoster) ImageView.ScaleType.FIT_END else ImageView.ScaleType.CENTER_CROP
        g.load(url).apply(opts).override(1280, 720)
            .transition(DrawableTransitionOptions.withCrossFade(450))
            .into(iv)
        KenBurns.start(iv)
    }

    fun clear(iv: ImageView) = Glide.with(iv).clear(iv)
}

object KenBurns {
    fun start(v: View) {
        v.animate().cancel()
        v.scaleX = 1f; v.scaleY = 1f; v.translationX = 0f
        v.pivotX = v.width * 0.7f; v.pivotY = v.height * 0.4f
        v.animate().scaleX(1.08f).scaleY(1.08f).translationX(-10f.dpf)
            .setDuration(18_000).setInterpolator(LinearInterpolator()).start()
    }
}

/**
 * Couleur d'ambiance : comme sur Netflix, le fond se teinte doucement
 * avec la couleur dominante de l'affiche sélectionnée.
 */
class Ambient(private val root: View, left: View?, bottom: View?) {
    private var current = BASE
    private var animator: ValueAnimator? = null
    private var lastUrl: String? = null
    private val leftG = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(BASE, BASE))
    private val bottomG = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(BASE, BASE))

    init {
        left?.background = leftG
        bottom?.background = bottomG
        apply(BASE)
    }

    fun fromUrl(url: String?) {
        if (url == lastUrl) return
        lastUrl = url
        if (url == null) { animateTo(BASE); return }
        Glide.with(root).asBitmap().load(url).override(48, 27)
            .into(object : CustomTarget<Bitmap>(48, 27) {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    if (url != lastUrl) return
                    Palette.from(resource).maximumColorCount(12).generate { p ->
                        val sw = p?.darkMutedSwatch ?: p?.mutedSwatch ?: p?.darkVibrantSwatch
                        animateTo(if (sw != null) blend(BASE, sw.rgb, 0.32f) else BASE)
                    }
                }
                override fun onLoadCleared(placeholder: Drawable?) {}
            })
    }

    private fun animateTo(target: Int) {
        animator?.cancel()
        animator = ValueAnimator.ofObject(ArgbEvaluator(), current, target).apply {
            duration = 700
            interpolator = DecelerateInterpolator()
            addUpdateListener { apply(it.animatedValue as Int) }
            start()
        }
    }

    private fun apply(c: Int) {
        current = c
        root.setBackgroundColor(c)
        leftG.colors = intArrayOf(c, alpha(c, 0xF0), alpha(c, 0x99), alpha(c, 0x00))
        bottomG.colors = intArrayOf(c, alpha(c, 0xCC), alpha(c, 0x00))
    }

    companion object {
        const val BASE = 0xFF0E0E0F.toInt()
        fun alpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)
        fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
            (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
            (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
            (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt(),
        )
    }
}

/** Avatar de profil : carré arrondi coloré + visage. */
fun avatarDrawable(c: Context, colorIndex: Int): Drawable {
    val color = Prefs.PROFILE_COLORS[colorIndex.coerceIn(0, Prefs.PROFILE_COLORS.size - 1)]
    val bg = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(color, Ambient.blend(color, Color.BLACK, 0.35f)))
    bg.cornerRadius = 8f.dpf
    val face = ContextCompat.getDrawable(c, R.drawable.avatar_face)!!
    return LayerDrawable(arrayOf(bg, face))
}

// ====================================================================== formats

object Fmt {
    private const val SEP = "   •   "

    fun duration(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val p = raw.trim().split(":")
        val minutes = when {
            p.size == 3 -> (p[0].toIntOrNull() ?: 0) * 60 + (p[1].toIntOrNull() ?: 0)
            raw.trim().endsWith("min") -> raw.filter { it.isDigit() }.toIntOrNull() ?: return raw
            else -> raw.trim().toIntOrNull()?.let { if (it > 600) it / 60 else it } ?: return raw
        }
        if (minutes <= 0) return null
        return if (minutes >= 60) "${minutes / 60} h ${"%02d".format(minutes % 60)}" else "$minutes min"
    }

    fun time(ms: Long): String {
        val s = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0))
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    fun remaining(ms: Long): String {
        val min = TimeUnit.MILLISECONDS.toMinutes(ms).coerceAtLeast(1)
        return if (min >= 60) "${min / 60} h ${"%02d".format(min % 60)} restantes" else "$min min restantes"
    }

    fun rating(r: String?): String? = r?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 }?.let {
        val v = if (it > 10) it / 10 else it
        "★ " + "%.1f".format(v).replace('.', ',')
    }

    /** "★ 7,8   •   2023   •   2 h 05   •   Action" avec la note en vert. */
    fun meta(item: Item, d: Details? = null): CharSequence {
        val sb = SpannableStringBuilder()
        rating(item.rating ?: d?.rating)?.let {
            sb.append(it)
            sb.setSpan(ForegroundColorSpan(0xFF46D369.toInt()), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val parts = listOfNotNull(
            item.year ?: d?.year,
            if (item.kind == Kind.MOVIE) duration(d?.duration) else null,
            if (item.kind == Kind.SERIES) d?.seasons?.size?.takeIf { it > 0 }?.let { if (it > 1) "$it saisons" else "1 saison" } else null,
            d?.genre?.split(',', '/', '&')?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(2)?.joinToString(" · "),
            if (item.kind == Kind.LIVE) "En direct" else null,
        )
        parts.forEach { if (sb.isNotEmpty()) sb.append(SEP); sb.append(it) }
        return sb
    }

    /** "Ma Série - S01E03 - Le titre" → "Le titre". */
    fun episodeTitle(ep: com.flux.tv.data.Episode): String {
        val t = ep.title.replace(Regex("^.*?[Ss]\\d{1,2}\\s*[EeÉé]\\d{1,3}\\s*[-–:·]*\\s*"), "").trim()
        return t.ifEmpty { "Épisode ${ep.num}" }
    }

    fun kicker(kind: Kind) = when (kind) {
        Kind.MOVIE -> "F I L M"
        Kind.SERIES -> "S É R I E"
        Kind.LIVE -> "C H A Î N E"
    }

    /** Taille du titre selon sa longueur, pour qu'il reste spectaculaire sans déborder. */
    fun titleSize(name: String, base: Float = 54f) = when {
        name.length > 38 -> base * 0.5f
        name.length > 24 -> base * 0.64f
        name.length > 14 -> base * 0.8f
        else -> base
    }
}

// ====================================================================== menus

/** Menu contextuel sombre façon Netflix. */
fun Context.showMenu(title: String, subtitle: String? = null, options: List<Pair<String, () -> Unit>>, subtitleLines: Int = 4) {
    val d = AppCompatDialog(this, R.style.Dialog)
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(30.dp, 28.dp, 30.dp, 24.dp)
        minimumWidth = 420.dp
    }
    box.addView(TextView(this).apply {
        text = title; typeface = Fonts.bold; textSize = 21f; setTextColor(0xFFF5F5F1.toInt())
        maxLines = 2
    })
    if (subtitle != null) box.addView(TextView(this).apply {
        text = subtitle; typeface = Fonts.regular; textSize = 14f; setTextColor(0xFFB3B3B3.toInt())
        setPadding(0, 8.dp, 0, 0); maxLines = subtitleLines
    })
    val first = box.childCount
    options.forEachIndexed { i, (label, action) ->
        box.addView(TextView(this).apply {
            text = label
            typeface = Fonts.semibold; textSize = 16f
            setTextColor(ContextCompat.getColorStateList(context, R.color.list_fg))
            background = ContextCompat.getDrawable(context, R.drawable.list_item_bg)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18.dp, 0, 18.dp, 0)
            isFocusable = true; isClickable = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 50.dp).apply {
                topMargin = if (i == 0) 20.dp else 4.dp
            }
            setOnClickListener { d.dismiss(); action() }
        })
    }
    d.setContentView(box)
    d.window?.setDimAmount(0.78f)
    d.show()
    box.getChildAt(first)?.requestFocus()
}
