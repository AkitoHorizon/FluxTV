#!/usr/bin/env bash
# Installe Flux TV sur le boîtier et le transforme en boîtier IPTV dédié.
# Usage : ./install.sh 192.168.1.50   (FluxTV.apk doit être dans ce dossier)
set -u
cd "$(dirname "$0")"
PKG=com.flux.tv
APK="${2:-FluxTV.apk}"
IP="${1:-}"
[ -z "$IP" ] && read -rp "Adresse IP du boîtier (ex. 192.168.1.50) : " IP
command -v adb >/dev/null || { echo "adb introuvable : installez Android platform-tools"; exit 1; }
[ -f "$APK" ] || { echo "$APK introuvable dans ce dossier"; exit 1; }

adb connect "$IP:5555"
ADB="adb -s $IP:5555"
timeout 30 $ADB wait-for-device || { echo "Boîtier injoignable. Débogage ADB activé ?"; exit 1; }

MODE=install
$ADB shell pm path "$PKG" 2>/dev/null | grep -q "package:" && MODE=update
if [ "$MODE" = update ]; then
  echo "=== Flux TV est déjà installé : MISE À JOUR (compte, profils et historique conservés) ==="
  echo "    Version actuelle : $($ADB shell dumpsys package "$PKG" | tr -d '\r' | grep -m1 versionName | cut -d= -f2)"
else
  echo "=== Première installation de Flux TV ==="
fi

echo "[1/5] Installation de Flux TV (1 à 3 minutes)…"
OUT=$($ADB install -r "$APK" 2>&1); echo "$OUT"
if ! echo "$OUT" | grep -q Success; then
  if echo "$OUT" | grep -qE "UPDATE_INCOMPATIBLE|signatures do not match|INCONSISTENT_CERTIFICATES|VERSION_DOWNGRADE"; then
    echo "L'ancienne version n'a pas la même signature : désinstallation puis réinstallation."
    echo "Cette fois seulement, il faudra retaper le serveur et les identifiants."
    $ADB uninstall "$PKG"
    OUT=$($ADB install "$APK" 2>&1); echo "$OUT"
    echo "$OUT" | grep -q Success || { echo "Échec de l'installation"; exit 1; }
    MODE=install
  else
    echo "Échec de l'installation"; exit 1
  fi
fi

echo "[2/5] Flux TV devient l'écran d'accueil…"
$ADB shell cmd package set-home-activity "$PKG/.ui.MainActivity" || true

if [ "$MODE" = update ]; then
  echo "[3/5] Nettoyage du boîtier déjà fait : étape ignorée."
  echo "[4/5] Optimisation de l'appli…"
  $ADB shell cmd package compile -m speed -f "$PKG" || true
  echo "[5/5] Relance de Flux TV…"
  $ADB shell am force-stop "$PKG"
  $ADB shell am start -n "$PKG/.ui.MainActivity" >/dev/null
  echo "Mise à jour terminée : $($ADB shell dumpsys package "$PKG" | tr -d '\r' | grep -m1 versionName | cut -d= -f2)"
  exit 0
fi

echo "[3/5] Désactivation des applis inutiles…"
INSTALLED=$($ADB shell pm list packages | tr -d '\r' | sed 's/^package://')
grep -v '^\s*#' bloat.txt | sed 's/\s//g' | grep -v '^$' | while read -r p; do
  if echo "$INSTALLED" | grep -qx "$p"; then
    $ADB shell pm disable-user --user 0 "$p" >/dev/null 2>&1 && echo "   - $p"
  fi
done

echo "[4/5] Optimisations (animations, Wi-Fi, mise en veille, compilation)…"
$ADB shell settings put global window_animation_scale 0.5
$ADB shell settings put global transition_animation_scale 0.5
$ADB shell settings put global animator_duration_scale 0.5
$ADB shell settings put global wifi_sleep_policy 2
$ADB shell settings put global wifi_scan_always_enabled 0
$ADB shell settings put global ble_scan_always_enabled 0
$ADB shell settings put secure screensaver_enabled 0
$ADB shell settings put secure screensaver_activate_on_sleep 0
$ADB shell settings put system screen_off_timeout 2147483647
$ADB shell pm trim-caches 999G >/dev/null 2>&1
$ADB shell cmd package compile -m speed -f "$PKG" || true

echo "[5/5] Redémarrage…"
$ADB reboot
echo "Terminé. Le boîtier redémarre directement sur Flux TV."
