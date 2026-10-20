@echo off
setlocal
cd /d "%~dp0"
set PKG=com.flux.tv
set APK=FluxTV.apk
set ADB=adb
set LOG=%TEMP%\fluxtv_install.txt
if exist "platform-tools\adb.exe" set ADB=platform-tools\adb.exe
%ADB% version >nul 2>nul || (echo [ERREUR] adb introuvable : copiez le dossier platform-tools a cote de ce script. & pause & exit /b 1)
if not exist "%APK%" (echo [ERREUR] %APK% introuvable a cote de ce script. & pause & exit /b 1)

set IP=%1
if "%IP%"=="" set /p IP=Adresse IP du boitier (ex. 192.168.1.50) : 
%ADB% connect %IP%:5555
set DEV=-s %IP%:5555
%ADB% %DEV% get-state >nul 2>nul || (echo [ERREUR] Boitier injoignable. Debogage ADB active ? Meme reseau ? & pause & exit /b 1)

rem ---- Premiere installation ou mise a jour ?
set MODE=install
%ADB% %DEV% shell pm path %PKG% 2>nul | findstr /c:"package:" >nul && set MODE=update
echo.
if "%MODE%"=="update" goto say_update
echo === Premiere installation de Flux TV ===
goto do_install
:say_update
echo === Flux TV est deja installe : MISE A JOUR ===
echo     Le compte IPTV, les profils et l'historique sont conserves.
for /f "tokens=2 delims==" %%v in ('%ADB% %DEV% shell dumpsys package %PKG% ^| findstr versionName') do echo     Version actuelle : %%v

:do_install
echo.
echo [1/5] Installation de Flux TV (1 a 3 minutes, sans barre de progression)...
%ADB% %DEV% install -r "%APK%" > "%LOG%" 2>&1
type "%LOG%"
findstr /c:"Success" "%LOG%" >nul && goto installed
findstr /c:"INSTALL_FAILED_UPDATE_INCOMPATIBLE" /c:"signatures do not match" /c:"INCONSISTENT_CERTIFICATES" /c:"VERSION_DOWNGRADE" "%LOG%" >nul || goto failed
echo.
echo L'ancienne version n'a pas la meme signature : elle va etre desinstallee puis remplacee.
echo Cette fois seulement, il faudra retaper le serveur et les identifiants dans l'appli.
%ADB% %DEV% uninstall %PKG%
%ADB% %DEV% install "%APK%" > "%LOG%" 2>&1
type "%LOG%"
findstr /c:"Success" "%LOG%" >nul && goto installed
:failed
echo [ERREUR] Echec de l'installation (le message ci-dessus indique la raison).
pause
exit /b 1

:installed
echo [2/5] Flux TV devient l'ecran d'accueil...
%ADB% %DEV% shell cmd package set-home-activity %PKG%/.ui.MainActivity
if "%MODE%"=="update" goto quick

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
exit /b 0

:quick
echo [3/5] Nettoyage du boitier deja fait : etape ignoree.
echo [4/5] Optimisation de l'appli pour le processeur...
%ADB% %DEV% shell cmd package compile -m speed -f %PKG%
echo [5/5] Relance de Flux TV...
%ADB% %DEV% shell am force-stop %PKG%
%ADB% %DEV% shell am start -n %PKG%/.ui.MainActivity >nul
echo.
echo Mise a jour terminee ! Flux TV s'est relance sur la TV.
for /f "tokens=2 delims==" %%v in ('%ADB% %DEV% shell dumpsys package %PKG% ^| findstr versionName') do echo Nouvelle version : %%v
pause
