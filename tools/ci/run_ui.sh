#!/usr/bin/env bash
# Robot de test : pilote Flux TV à la télécommande (adb), capture chaque écran, relève plantages et lecture.
set -u
NAME="${1:-emu}"
OUT="report/$NAME"; mkdir -p "$OUT"
S="$OUT/summary.txt"; : > "$S"
APK=$(ls app/build/outputs/apk/release/*.apk | head -1)
PKG=com.flux.tv
n=0

k() { for c in "$@"; do adb shell input keyevent "$c"; sleep 0.6; done; }
rep() { local c=$1 t=$2; for _ in $(seq 1 "$t"); do adb shell input keyevent "$c"; sleep 0.25; done; sleep 0.6; }
shot() { sleep "${2:-1.5}"; n=$((n+1)); f=$(printf "%02d_%s" $n "$1"); adb exec-out screencap -p > "$OUT/$f.png"; echo "capture $f"; check "$1"; }
check() {
  if adb logcat -d | grep -qE "Process: $PKG|ANR in $PKG"; then
    echo "PLANTAGE apres: $1" | tee -a "$S"
    adb logcat -d | grep -B2 -A40 -E "Process: $PKG|ANR in $PKG" > "$OUT/crash_$1.txt"
    adb logcat -c
  fi
}
resumed() { adb shell dumpsys activity activities | grep -m1 -E "mResumedActivity|ResumedActivity:" ; }
wait_act() {  # attend qu'un écran donné soit au premier plan
  for _ in $(seq 1 40); do resumed | grep -q "$1" && { sleep 1; return 0; }; sleep 0.5; done
  echo "ATTENTE ECHOUEE: $1 (actuel: $(resumed))" | tee -a "$S"; return 1
}
qa() { echo "  [$1] $(adb logcat -d -s FluxQA:* | grep -c 'firstFrame') premieres images, $(adb logcat -d -s FluxQA:* | grep -c 'player error') erreurs lecteur" >> "$S"; }
restart() { adb shell am force-stop $PKG; sleep 1; adb shell am start -n $PKG/.ui.MainActivity >/dev/null; }
# Retour à l'accueil puis entrée n°$1 du menu : 0 profil, 1 recherche, 2 accueil, 3 séries, 4 films, 5 nouveautés, 6 direct, 7 ma liste, 8 paramètres
menu() {
  adb shell am start -n $PKG/.ui.MainActivity >/dev/null; wait_act MainActivity
  k 4; sleep 0.8; k 21; rep 19 10; [ "$1" -gt 0 ] && rep 20 "$1"; k 23
}

echo "== $NAME : Android $(adb shell getprop ro.build.version.release) / $(adb shell wm size | tail -1) / $(adb shell wm density | tail -1)" | tee -a "$S"
adb install -r "$APK" | tail -1 | tee -a "$S"
adb logcat -c

# ---- 1. Connexion
restart; wait_act LoginActivity
shot login 1
adb shell input text "10.0.2.2:8000"; k 66
adb shell input text "demo"; k 66
adb shell input text "demo"; k 66
wait_act ProfilesActivity && shot profils 4

# ---- 2. Accueil
k 23; wait_act MainActivity; shot accueil_billboard 6
k 20; shot accueil_rangees 2
k 22 22 22; shot rangee_defilee 2
k 20; shot rangee_top10 2
k 21 21 21 21 21 21 21; shot menu_ouvert 1.5

# ---- 3. Sections
menu 3; shot section_series 5
menu 4; shot section_films 5
menu 5; shot section_nouveautes 5

# ---- 4. Film : fiche, lecture, contrôles, pistes, reprise
menu 4; sleep 4; k 20; k 23; wait_act DetailActivity; shot fiche_film 3
k 22; shot fiche_bouton_liste 1; k 21
k 23; wait_act PlayerActivity; shot lecteur_demarrage 2; shot lecteur_lecture 8
k 23; shot lecteur_pause_controles 1.5
k 22 22 22; shot lecteur_focus_pistes 1
k 23; shot lecteur_panneau_pistes 2
k 4; k 4; k 22 22 22; shot lecteur_scrub 1.2
sleep 2; k 4; k 4; wait_act DetailActivity; shot fiche_bouton_reprendre 2
k 4; wait_act MainActivity; shot accueil_reprendre 4

# ---- 5. Recherche + série (2 pistes audio, sous-titres, épisode suivant)
menu 1; wait_act SearchActivity; shot recherche_vide 3
adb shell input text "serie"; shot recherche_resultats 3
rep 22 7; shot recherche_focus 1.5
k 23; wait_act DetailActivity; shot fiche_serie 5
k 20; shot fiche_serie_saisons 1.5
k 22; shot fiche_serie_saison2 2
k 20; shot fiche_serie_episodes 1.5
k 23; wait_act PlayerActivity; shot serie_lecture 8
qa serie
k 23; k 22 22 22; k 23; shot serie_panneau_pistes 2
k 22; k 20; k 23; shot serie_soustitres_actives 4
k 23; rep 22 30; shot serie_saut_fin 3
shot serie_episode_suivant 12
shot serie_enchainement 14
k 4; k 4; k 4; wait_act DetailActivity; shot fiche_serie_progression 3

# ---- 6. TV en direct
menu 6; wait_act LiveActivity; shot guide_tv 4
k 22; shot guide_chaines 2; shot guide_apercu 8
k 23; wait_act PlayerActivity; shot direct_plein_ecran 8
qa direct
k 19; shot direct_zapping 6
k 21; shot direct_liste_chaines 2
k 20 20; k 23; shot direct_changement_chaine 6
k 4; k 4; wait_act LiveActivity; shot guide_retour 2

# ---- 7. Ma liste, paramètres, profils
menu 7; shot ma_liste 4
menu 8; wait_act SettingsActivity; shot parametres 3
rep 20 12; shot parametres_bas 1.5
menu 0; wait_act ProfilesActivity; shot profils_changer 2
k 22; k 23; shot profil_ajout 2
adb shell input text "Invite"; k 66; k 20; k 20; k 23; shot profil_ajoute 2
k 4

# ---- 8. Résolutions (TV / densités courantes des boîtiers Android)
for res in "1920x1080 320" "1920x1080 240" "1280x720 160" "1280x720 213" "3840x2160 640"; do
  set -- $res
  adb shell wm size "$1"; adb shell wm density "$2"
  tag="${1}_${2}dpi"
  restart; wait_act ProfilesActivity; sleep 2; k 23; wait_act MainActivity; shot "res_${tag}_accueil" 6
  k 20; shot "res_${tag}_rangees" 2
  menu 4; sleep 4; k 20; k 23; wait_act DetailActivity; shot "res_${tag}_fiche" 3
  menu 6; wait_act LiveActivity; shot "res_${tag}_guide" 4
done
adb shell wm size reset; adb shell wm density reset

# ---- Bilan
echo "--- Traces FluxQA ---" >> "$S"; adb logcat -d -s FluxQA:* | grep -v beginning | tail -30 >> "$S"
adb logcat -d > "$OUT/logcat.txt"
c=$(grep -c PLANTAGE "$S"); echo "Plantages Flux TV: $c" | tee -a "$S"
exit 0
