@echo off
setlocal
cd /d "%~dp0"
set PKG=com.flux.tv
set APK=FluxTV.apk
set ADB=adb
if exist "platform-tools\adb.exe" set ADB=platform-tools\adb.exe
%ADB% version >nul 2>nul || (echo [ERREUR] adb introuvable : copiez le dossier platform-tools a cote de ce script. & pause & exit /b 1)
if not exist "%APK%" (echo [ERREUR] %APK% introuvable a cote de ce script. & pause & exit /b 1)

set IP=%1
if "%IP%"=="" set /p IP=Adresse IP du boitier (ex. 192.168.1.50) : 
%ADB% connect %IP%:5555
set DEV=-s %IP%:5555
%ADB% %DEV% get-state >nul 2>nul || (echo [ERREUR] Boitier injoignable. Debogage ADB active ? Meme reseau ? & pause & exit /b 1)

echo [1/5] Installation de Flux TV...
%ADB% %DEV% install -r "%APK%" || (echo [ERREUR] Echec de l'installation & pause & exit /b 1)

echo [2/5] Flux TV devient l'ecran d'accueil...
%ADB% %DEV% shell cmd package set-home-activity %PKG%/.ui.MainActivity

echo [3/5] Desactivation des applis inutiles...
for /f "usebackq eol=# tokens=1" %%p in ("bloat.txt") do (
  %ADB% %DEV% shell pm disable-user --user 0 %%p >nul 2>nul && echo    - %%p
)

echo [4/5] Optimisations...
%ADB% %DEV% shell settings put global window_animation_scale 0.5
%ADB% %DEV% shell settings put global transition_animation_scale 0.5
%ADB% %DEV% shell settings put global animator_duration_scale 0.5
%ADB% %DEV% shell settings put global wifi_sleep_policy 2
%ADB% %DEV% shell settings put global wifi_scan_always_enabled 0
%ADB% %DEV% shell settings put global ble_scan_always_enabled 0
%ADB% %DEV% shell settings put secure screensaver_enabled 0
%ADB% %DEV% shell settings put secure screensaver_activate_on_sleep 0
%ADB% %DEV% shell settings put system screen_off_timeout 2147483647
%ADB% %DEV% shell pm trim-caches 999G >nul 2>nul
%ADB% %DEV% shell cmd package compile -m speed -f %PKG%

echo [5/5] Redemarrage...
%ADB% %DEV% reboot
echo.
echo Termine ! Le boitier redemarre directement sur Flux TV.
pause
