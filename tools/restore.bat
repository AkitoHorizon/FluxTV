@echo off
setlocal
cd /d "%~dp0"
set ADB=adb
if exist "platform-tools\adb.exe" set ADB=platform-tools\adb.exe
set IP=%1
if "%IP%"=="" set /p IP=Adresse IP du boitier : 
%ADB% connect %IP%:5555
set DEV=-s %IP%:5555
for /f "usebackq eol=# tokens=1" %%p in ("bloat.txt") do (
  %ADB% %DEV% shell pm enable %%p >nul 2>nul && echo    + %%p
)
%ADB% %DEV% shell settings put global window_animation_scale 1.0
%ADB% %DEV% shell settings put global transition_animation_scale 1.0
%ADB% %DEV% shell settings put global animator_duration_scale 1.0
set /p R=Desinstaller aussi Flux TV ? (o/N) : 
if /i "%R%"=="o" %ADB% %DEV% uninstall com.flux.tv
%ADB% %DEV% reboot
pause
