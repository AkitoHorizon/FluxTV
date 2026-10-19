#!/usr/bin/env bash
# Annule install.sh : réactive les applis d'origine et retire Flux TV.
set -u
cd "$(dirname "$0")"
IP="${1:-}"
[ -z "$IP" ] && read -rp "Adresse IP du boîtier : " IP
adb connect "$IP:5555"; ADB="adb -s $IP:5555"
grep -v '^\s*#' bloat.txt | sed 's/\s//g' | grep -v '^$' | while read -r p; do
  $ADB shell pm enable "$p" >/dev/null 2>&1 && echo "   + $p"
done
for k in window_animation_scale transition_animation_scale animator_duration_scale; do
  $ADB shell settings put global $k 1.0
done
read -rp "Désinstaller aussi Flux TV ? (o/N) " r
[ "$r" = "o" ] && $ADB uninstall com.flux.tv
$ADB reboot
