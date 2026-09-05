@echo off
cd /d "%~dp0"
net session >nul 2>&1
if not "%errorlevel%"=="0" (
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -Verb RunAs -FilePath '%~f0'"
  exit /b
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\install-map-video-hotfix.ps1" -InstallRoot "C:\UAVFire"
if errorlevel 1 (
  echo [ERROR] Hotfix did not complete. See the message above.
) else (
  echo [OK] Refresh the browser with Ctrl+F5.
)
pause
