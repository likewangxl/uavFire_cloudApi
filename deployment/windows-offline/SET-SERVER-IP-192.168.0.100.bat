@echo off
chcp 65001 >nul
cd /d "%~dp0"

net session >nul 2>&1
if not "%errorlevel%"=="0" (
  echo Requesting administrator privileges...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -Verb RunAs -FilePath '%~f0'"
  exit /b
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\set-server-ip.ps1" -PublicHost "192.168.0.100" -InstallRoot "C:\UAVFire"
if errorlevel 1 (
  echo.
  echo [ERROR] Server IP repair failed. Send C:\UAVFire\data\logs to support.
) else (
  echo.
  echo [OK] Server IP and WebRTC configuration updated to 192.168.0.100.
  echo Open http://192.168.0.100:81 and press Ctrl+F5 in the browser.
)
pause
