package com.flux.tv.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Grand chiffre détouré des rangées "Top 10", dessiné à la main (net à toutes les tailles). */
class StrokeNumberView(ctx: Context) : View(ctx) {
    var number: String = "1"
        set(v) { field = v; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFF0E0E0F.toInt(); typeface = Fonts.black; textAlign = Paint.Align.RIGHT
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f.dpf; color = 0xFF9A9A9A.toInt()
        typeface = Fonts.black; textAlign = Paint.Align.RIGHT; strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(c: Canvas) {
        val size = height * 1.02f
        fill.textSize = size; stroke.textSize = size
        val tight = if (number.length > 1) -0.08f else 0f
        fill.letterSpacing = tight; stroke.letterSpacing = tight
        val x = width + 22f.dpf          // la fin du chiffre passe sous l'affiche
        val y = height - 2f.dpf
        c.drawText(number, x, y, fill)
        c.drawText(number, x, y, stroke)
    }
}

/** Squelette animé pendant le chargement (au lieu d'un rond qui tourne). */
class SkeletonView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1C1C1C.toInt() }
    private val r = RectF()
    private var pulse: ValueAnimator? = null

    private fun startPulse() {
        if (pulse != null) return
        pulse = ValueAnimator.ofFloat(0.35f, 0.9f).apply {
            duration = 850
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isShown) startPulse() else stopPulse()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isShown) startPulse()
    }

    override fun onDetachedFromWindow() {
        stopPulse()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val left = 40f.dpf
        var y = 6f.dpf
        val rad = 6f.dpf
        repeat(3) {
            r.set(left, y, left + 150f.dpf, y + 14f.dpf); c.drawRoundRect(r, rad, rad, p)
            y += 30f.dpf
            var x = left
            while (x < width) {
                r.set(x, y, x + 116f.dpf, y + 174f.dpf); c.drawRoundRect(r, rad, rad, p)
                x += 128f.dpf
            }
            y += 174f.dpf + 34f.dpf
        }
    }
}

/**
 * Garde l'élément sélectionné au même endroit (bord gauche ou haut), comme Netflix :
 * c'est le contenu qui glisse, pas la sélection.
 */
class FocusAlignLayoutManager(ctx: Context, orientation: Int) :
    LinearLayoutManager(ctx, orientation, false) {

    override fun requestChildRectangleOnScreen(
        parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean,
    ): Boolean {
        val horizontal = orientation == HORIZONTAL
        val delta = if (horizontal) child.left - parent.paddingLeft else child.top - parent.paddingTop
        if (delta == 0) return false
        if (horizontal) {
            if (immediate) parent.scrollBy(delta, 0) else parent.smoothScrollBy(delta, 0)
        } else {
            if (immediate) parent.scrollBy(0, delta) else parent.smoothScrollBy(0, delta)
        }
        return true
    }
}
