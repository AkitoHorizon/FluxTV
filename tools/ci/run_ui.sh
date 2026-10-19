#!/usr/bin/env bash
# Robot de test : pilote Flux TV à la "télécommande" (adb), capture chaque écran, relève plantages et lecture.
set -u
NAME="${1:-emu}"
OUT="report/$NAME"; mkdir -p "$OUT"
S="$OUT/summary.txt"; : > "$S"
APK=$(ls app/build/outputs/apk/release/*.apk | head -1)
PKG=com.flux.tv
n=0

k() { for c in "$@"; do adb shell input keyevent "$c"; sleep 0.7; done; }
shot() { sleep "${2:-1.5}"; n=$((n+1)); f=$(printf "%02d_%s" $n "$1"); adb exec-out screencap -p > "$OUT/$f.png"; echo "capture $f"; check "$1"; }
check() {
  if adb logcat -d | grep -qE "FATAL EXCEPTION|ANR in $PKG"; then
    echo "PLANTAGE apres: $1" | tee -a "$S"
    adb logcat -d | grep -A40 -E "FATAL EXCEPTION|ANR in" > "$OUT/crash_$1.txt"
    adb logcat -c
  fi
}
qa() { adb logcat -d -s FluxQA:* | tail -n "${1:-6}" | sed "s/^/  [$2] /" >> "$S"; }
restart() { adb shell am force-stop $PKG; sleep 1; adb shell am start -n $PKG/.ui.MainActivity >/dev/null; }

echo "== $NAME : $(adb shell getprop ro.build.version.release) / $(adb shell wm size | tail -1) / $(adb shell wm density | tail -1)" | tee -a "$S"
adb install -r "$APK" | tail -1 | tee -a "$S"
adb logcat -c

# ---- 1. Connexion
restart
shot login 4
adb shell input text "10.0.2.2:8000"; k 66
adb shell input text "demo"; k 66
adb shell input text "demo"; k 66
shot login_submitted 1

# ---- 2. Intro + profils
shot profils 5
k 23
shot accueil_billboard 7
qa 4 accueil

# ---- 3. Accueil : rangées, défilement, menu latéral
k 20; shot accueil_rangees 2
k 22 22 22; shot rangee_defilee 2
k 20; shot rangee_2 2
k 21 21 21 21 21 21; shot menu_ouvert 1.5
k 20 20; shot menu_series 1.5
k 23; shot section_series 5
k 21 21 21 21 21 21; k 20; k 23; shot section_films 5
k 21 21 21 21 21 21; k 19 19 19 19; k 23; shot section_accueil 5

# ---- 4. Fiche film + lecture
k 20; k 23; shot fiche_film 4
k 22; shot fiche_bouton_liste 1; k 21
k 23; shot lecteur_demarrage 3; shot lecteur_lecture 8
qa 6 film
k 23; shot lecteur_controles 1.5
k 22; k 22; k 22; shot lecteur_focus_pistes 1
k 23; shot lecteur_panneau_audio 2
k 4; k 4; shot lecteur_retour_avant 1
k 22; k 22; k 22; shot lecteur_scrub 1.5
k 4; k 4; shot fiche_apres_lecture 3
k 4; shot accueil_reprendre 4

# ---- 5. Recherche (clavier physique + clavier à l'écran)
k 21 21 21 21 21 21; k 19 19 19 19 19 19 19 19; k 20 20; k 23; shot recherche_vide 4
adb shell input text "serie"; shot recherche_resultats 3
k 22 22 22 22 22 22 22; shot recherche_focus_resultat 1.5
k 23; shot fiche_serie 5
k 20 20; shot fiche_serie_saisons 1.5
k 20; shot fiche_serie_episodes 1.5
k 23; shot serie_lecture 9
qa 6 serie
k 23; k 22 22 22; k 23; shot serie_panneau_pistes 2
k 20; k 20; shot serie_panneau_navigation 1
k 4; k 4; k 4; shot fiche_serie_retour 3
k 4; k 4; shot retour_accueil 3

# ---- 6. TV en direct
k 21 21 21 21 21 21; k 20 20 20 20; k 23; shot guide_tv 4
k 22; shot guide_chaines 2; shot guide_apercu 7
qa 4 apercu
k 23; shot direct_plein_ecran 7
qa 4 direct
k 19; shot direct_zapping 5
k 21; shot direct_liste_chaines 2
k 4; k 4; k 4; shot retour_depuis_direct 3

# ---- 7. Profils et réglages
k 21 21 21 21 21 21; k 19 19 19 19 19 19 19 19 19 19; k 20 20 20 20 20 20 20 20 20 20 20; k 23; shot parametres 3
k 4

# ---- 8. Résolutions (toutes les TV / densités courantes des boîtiers Android)
for res in "1920x1080 320" "1920x1080 240" "1280x720 160" "1280x720 213"; do
  set -- $res
  adb shell wm size "$1"; adb shell wm density "$2"
  tag="${1}_${2}dpi"
  restart; sleep 6; k 23; shot "res_${tag}_accueil" 6
  k 20; shot "res_${tag}_rangees" 2
  k 23; shot "res_${tag}_fiche" 4
  k 4
done
adb shell wm size reset; adb shell wm density reset

# ---- Bilan
echo "--- Traces FluxQA ---" >> "$S"; adb logcat -d -s FluxQA:* | tail -40 >> "$S"
echo "--- Fluidité (gfxinfo) ---" >> "$S"; adb shell dumpsys gfxinfo $PKG | grep -E "Total frames|Janky|percentile" >> "$S"
adb logcat -d > "$OUT/logcat.txt"
grep -c "PLANTAGE" "$S" > /dev/null && echo "Plantages: $(grep -c PLANTAGE "$S")" || echo "Aucun plantage détecté" | tee -a "$S"
exit 0
