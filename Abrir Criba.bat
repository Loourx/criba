@echo off
chcp 65001 >nul
title Criba
cd /d "%~dp0"

where python >nul 2>nul
if errorlevel 1 (
  echo No encuentro Python. Instalalo desde https://www.python.org/downloads/ marcando "Add python.exe to PATH".
  pause
  exit /b 1
)

for /f "delims=" %%a in ('python -I -c "import sys; sys.path.insert(0, r'criba'); import inventario; print(inventario.ADB)" 2^>nul') do set "ADB=%%a"
if not defined ADB (
  echo No encuentro adb ^(Android Platform Tools^).
  choice /m "Instalarlo ahora con winget"
  if errorlevel 2 exit /b 1
  winget install --id Google.PlatformTools -e --accept-source-agreements --accept-package-agreements
  for /f "delims=" %%a in ('python -I -c "import sys; sys.path.insert(0, r'criba'); import inventario; print(inventario.ADB)" 2^>nul') do set "ADB=%%a"
)
if not defined ADB (
  echo No se pudo instalar adb. Mira el README.
  pause
  exit /b 1
)

echo Conecta el movil por USB con la depuracion USB activada y acepta el aviso en el movil.
"%ADB%" wait-for-device
echo Movil detectado. Leyendo fotos y videos...
start "" cmd /c "timeout /t 8 >nul & start http://127.0.0.1:8765"
python -I criba\servidor.py --refresh
echo.
echo El servidor se ha cerrado. Pulsa una tecla para salir.
pause >nul
