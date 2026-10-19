package com.flux.tv.ui

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatDialog
import androidx.core.content.ContextCompat
import com.flux.tv.R
import com.flux.tv.data.Prefs
import com.flux.tv.data.Profile

/**
 * "Qui regarde ?" : intro animée du logo puis choix du profil.
 * Chaque profil a son propre historique, sa liste et ses préférences de langue.
 */
class ProfilesActivity : BaseActivity() {

    private lateinit var row: LinearLayout
    private lateinit var content: View
    private lateinit var manageBtn: TextView
    private var manage = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profiles)
        row = findViewById(R.id.profileRow)
        content = findViewById(R.id.content)
        manageBtn = findViewById(R.id.manage)
        manageBtn.setOnClickListener { setManage(!manage) }

        Prefs.ensureProfile()
        build()

        val intro = !intent.getBooleanExtra(EXTRA_SWITCH, false) && savedInstanceState == null
        if (intro) playIntro() else {
            findViewById<View>(R.id.introLogo).show(false)
            findViewById<View>(R.id.smallLogo).alpha = 1f
            findViewById<View>(R.id.glow).alpha = 1f
            content.alpha = 1f
            focusCurrent()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    manage -> setManage(false)
                    Session.profileChosen -> finish()
                }
            }
        })
    }

    // ------------------------------------------------------------------ intro

    private fun playIntro() {
        val logo = findViewById<View>(R.id.introLogo)
        val small = findViewById<View>(R.id.smallLogo)
        val glow = findViewById<View>(R.id.glow)
        logo.alpha = 0f; logo.scaleX = 1.7f; logo.scaleY = 1.7f
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(900)
            .setInterpolator(DecelerateInterpolator(2.2f)).start()
        glow.animate().alpha(1f).setStartDelay(300).setDuration(900).start()
        logo.postDelayed({
            logo.animate().alpha(0f).scaleX(0.82f).scaleY(0.82f).setDuration(380)
                .withEndAction { logo.visibility = View.GONE }.start()
            small.animate().alpha(1f).setStartDelay(200).setDuration(400).start()
            content.translationY = 30f.dpf
            content.animate().alpha(1f).translationY(0f).setStartDelay(220).setDuration(520)
                .setInterpolator(DecelerateInterpolator()).start()
            for (i in 0 until row.childCount) {
                val c = row.getChildAt(i)
                c.alpha = 0f; c.translationY = 24f.dpf
                c.animate().alpha(1f).translationY(0f).setStartDelay(320L + i * 70L).setDuration(420)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
            content.postDelayed({ focusCurrent() }, 300)
        }, 1500)
    }

    // ------------------------------------------------------------------ tuiles

    private fun build() {
        row.removeAllViews()
        val profiles = Prefs.profiles()
        profiles.forEach { p -> row.addView(tile(p)) }
        if (profiles.size < 5) row.addView(addTile())
    }

    private fun tile(p: Profile): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_profile, row, false)
        v.findViewById<ImageView>(R.id.avatar).setImageDrawable(avatarDrawable(this, p.color))
        v.findViewById<TextView>(R.id.name).text = p.name
        v.findViewById<View>(R.id.editOverlay).show(manage)
        v.tag = p.id
        v.setOnFocusChangeListener { view, has ->
            view.animate().scaleX(if (has) 1.1f else 1f).scaleY(if (has) 1.1f else 1f).setDuration(160).start()
        }
        v.setOnClickListener { if (manage) editProfile(p) else choose(v, p) }
        return v
    }

    private fun addTile(): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_profile, row, false)
        val iv = v.findViewById<ImageView>(R.id.avatar)
        iv.setBackgroundResource(R.drawable.bg_add_profile)
        iv.setImageResource(R.drawable.ic_add)
        iv.scaleType = ImageView.ScaleType.CENTER
        iv.setColorFilter(0xFF8C8C8C.toInt())
        v.findViewById<TextView>(R.id.name).text = "Ajouter"
        v.setOnFocusChangeListener { view, has ->
            view.animate().scaleX(if (has) 1.1f else 1f).scaleY(if (has) 1.1f else 1f).setDuration(160).start()
        }
        v.setOnClickListener { editProfile(null) }
        return v
    }

    private fun focusCurrent() {
        val target = (0 until row.childCount).map { row.getChildAt(it) }.firstOrNull { it.tag == Prefs.profileId }
        (target ?: row.getChildAt(0))?.requestFocus()
    }

    private fun setManage(on: Boolean) {
        manage = on
        manageBtn.text = if (on) "TERMINÉ" else "GÉRER LES PROFILS"
        findViewById<TextView>(R.id.title).text = if (on) "Gérer les profils" else "Qui regarde ?"
        for (i in 0 until row.childCount) row.getChildAt(i).findViewById<View>(R.id.editOverlay)?.let {
            if (row.getChildAt(i).tag != null) it.fade(on)
        }
    }

    private fun choose(v: View, p: Profile) {
        Prefs.profileId = p.id
        Session.profileChosen = true
        v.animate().scaleX(1.25f).scaleY(1.25f).setDuration(260).setInterpolator(OvershootInterpolator()).start()
        content.animate().alpha(0f).setStartDelay(120).setDuration(260).withEndAction { finish() }.start()
    }

    // ------------------------------------------------------------------ édition

    private fun editProfile(p: Profile?) {
        val d = AppCompatDialog(this, R.style.Dialog)
        var color = p?.color ?: (Prefs.profiles().size % Prefs.PROFILE_COLORS.size)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32.dp, 28.dp, 32.dp, 26.dp)
            minimumWidth = 480.dp
        }
        box.addView(TextView(this).apply {
            text = if (p == null) "Ajouter un profil" else "Modifier le profil"
            typeface = Fonts.bold; textSize = 22f; setTextColor(0xFFF5F5F1.toInt())
        })
        val preview = ImageView(this).apply { setImageDrawable(avatarDrawable(context, color)) }
        val name = EditText(this).apply {
            setText(p?.name ?: "")
            hint = "Nom"
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 20.dp, 0, 0)
            addView(preview, LinearLayout.LayoutParams(72.dp, 72.dp))
            addView(name, LinearLayout.LayoutParams(0, 52.dp, 1f).apply { marginStart = 18.dp })
        }
        box.addView(top)
        box.addView(TextView(this).apply {
            text = "Couleur"; typeface = Fonts.medium; textSize = 13f; setTextColor(0xFFB3B3B3.toInt())
            setPadding(0, 20.dp, 0, 10.dp)
        })
        val swatches = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Prefs.PROFILE_COLORS.forEachIndexed { i, c ->
            val s = FrameLayout(this).apply {
                foreground = ContextCompat.getDrawable(context, R.drawable.swatch_ring)
                isFocusable = true; isClickable = true
                isSelected = i == color
                setPadding(5.dp, 5.dp, 5.dp, 5.dp)
                addView(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) } },
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                setOnClickListener {
                    color = i
                    for (k in 0 until swatches.childCount) swatches.getChildAt(k).isSelected = k == i
                    preview.setImageDrawable(avatarDrawable(context, i))
                }
            }
            swatches.addView(s, LinearLayout.LayoutParams(44.dp, 44.dp).apply { marginEnd = 10.dp })
        }
        box.addView(swatches)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 26.dp, 0, 0) }
        fun button(label: String, action: () -> Unit) = TextView(this).apply {
            text = label; typeface = Fonts.bold; textSize = 15f
            setTextColor(ContextCompat.getColorStateList(context, R.color.on_btn))
            background = ContextCompat.getDrawable(context, R.drawable.btn_pill)
            gravity = Gravity.CENTER; setPadding(22.dp, 0, 22.dp, 0)
            isFocusable = true; isClickable = true
            setOnClickListener { action() }
            buttons.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 44.dp).apply { marginEnd = 12.dp })
        }
        button("Enregistrer") {
            val n = name.text.toString().trim().ifEmpty { "Profil" }.take(16)
            if (p == null) Prefs.addProfile(n, color) else Prefs.updateProfile(Profile(p.id, n, color))
            d.dismiss(); build(); setManage(manage); focusCurrent()
        }
        button("Annuler") { d.dismiss() }
        if (p != null && Prefs.profiles().size > 1) button("Supprimer") {
            showMenu("Supprimer « ${p.name} » ?", "Son historique, sa liste et ses préférences seront effacés.", listOf(
                "Supprimer le profil" to {
                    Prefs.deleteProfile(p.id)
                    if (Prefs.profileId == p.id) Prefs.profileId = Prefs.profiles().first().id
                    d.dismiss(); build(); setManage(manage); focusCurrent()
                },
                "Annuler" to {},
            ))
        }
        box.addView(buttons)
        d.setContentView(box)
        d.window?.setDimAmount(0.8f)
        d.show()
        name.requestFocus()
    }

    companion object {
        const val EXTRA_SWITCH = "switch"
    }
}
