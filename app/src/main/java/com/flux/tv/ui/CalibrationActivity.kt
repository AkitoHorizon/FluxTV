package com.flux.tv.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import com.flux.tv.data.Prefs

/**
 * Calibrage de l'écran (overscan) : beaucoup de téléviseurs rognent les bords de l'image.
 * L'utilisateur règle un cadre rouge à la télécommande jusqu'à voir ses 4 bords en entier ;
 * les marges choisies sont ensuite appliquées à tous les écrans de l'appli.
 * Proposé une fois au premier lancement, puis disponible dans Paramètres.
 */
class CalibrationActivity : BaseActivity() {

    override val overscan = false   // cet écran dessine lui-même les marges

    private var mx = Prefs.overscanX   // pour mille de la largeur
    private var my = Prefs.overscanY   // pour mille de la hauteur
    private lateinit var frame: View
    private val corners = ArrayList<View>()
    private lateinit var values: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF080808.toInt()) }

        frame = View(this).apply {
            background = GradientDrawable().apply {
                setColor(0xFF141414.toInt())
                setStroke(4.dp, 0xFFE50914.toInt())
            }
        }
        root.addView(frame)

        // Repères dans les quatre coins
        for (g in intArrayOf(Gravity.TOP or Gravity.START, Gravity.TOP or Gravity.END, Gravity.BOTTOM or Gravity.START, Gravity.BOTTOM or Gravity.END)) {
            val c = View(this).apply { setBackgroundColor(0xFFE50914.toInt()) }
            corners += c
            root.addView(c, FrameLayout.LayoutParams(28.dp, 28.dp, g))
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        box.addView(TextView(this).apply {
            text = "Calibrage de l'écran"; typeface = Fonts.bold; textSize = 30f; setTextColor(Color.WHITE)
        })
        box.addView(TextView(this).apply {
            text = "Réglez le cadre pour voir ses 4 bords rouges en entier,\nau plus près des bords de votre téléviseur."
            typeface = Fonts.regular; textSize = 16f; setTextColor(0xFFB3B3B3.toInt()); gravity = Gravity.CENTER
            setPadding(0, 12.dp, 0, 20.dp)
        })
        box.addView(TextView(this).apply {
            text = "◀  rétrécir la largeur      ▶  l'agrandir\n▼  rétrécir la hauteur      ▲  l'agrandir"
            typeface = Fonts.semibold; textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setLineSpacing(8f, 1f)
        })
        values = TextView(this).apply {
            typeface = Fonts.medium; textSize = 14f; setTextColor(0xFF7A7A7A.toInt()); gravity = Gravity.CENTER
            setPadding(0, 16.dp, 0, 20.dp)
        }
        box.addView(values)
        box.addView(TextView(this).apply {
            text = "OK : valider      Retour : garder le réglage actuel"
            typeface = Fonts.bold; textSize = 15f; setTextColor(0xFFE50914.toInt()); gravity = Gravity.CENTER
        })
        root.addView(box, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { Prefs.calibrated = true; finish() }
        })
        root.post { layoutFrame() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> mx += STEP
            KeyEvent.KEYCODE_DPAD_RIGHT -> mx -= STEP
            KeyEvent.KEYCODE_DPAD_DOWN -> my += STEP
            KeyEvent.KEYCODE_DPAD_UP -> my -= STEP
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                Prefs.overscanX = mx; Prefs.overscanY = my; Prefs.calibrated = true
                toast("Calibrage enregistré")
                finish(); return true
            }
            else -> return super.dispatchKeyEvent(event)
        }
        mx = mx.coerceIn(0, MAX); my = my.coerceIn(0, MAX)
        layoutFrame()
        return true
    }

    private fun layoutFrame() {
        val root = frame.parent as FrameLayout
        val x = root.width * mx / 1000
        val y = root.height * my / 1000
        frame.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            .apply { setMargins(x, y, x, y) }
        for (c in corners) {
            val lp = c.layoutParams as FrameLayout.LayoutParams
            lp.setMargins(x, y, x, y); c.layoutParams = lp
        }
        values.text = "Marges : ${mx / 10.0} % sur les côtés · ${my / 10.0} % en haut et en bas"
    }

    companion object {
        private const val STEP = 5      // 0,5 % par appui
        private const val MAX = 100     // 10 % au maximum
    }
}
