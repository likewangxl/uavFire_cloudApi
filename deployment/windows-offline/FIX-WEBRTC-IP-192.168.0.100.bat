@echo off
chcp 65001 >nul
cd /d "%~dp0"

net session >nul 2>&1
if not "%errorlevel%"=="0" (
  echo Requesting administrator privileges...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -Verb RunAs -FilePath '%~f0'"
  exit /b
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\fix-webrtc-ip.ps1" -PublicHost "192.168.0.100" -InstallRoot "C:\UAVFire"
if errorlevel 1 (
  echo.
  echo [ERROR] WebRTC IP repair failed. Send C:\UAVFire\data\logs\zlmediakit-stderr.log to support.
) else (
  echo.
  echo [OK] ZLMediaKit WebRTC address is now 192.168.0.100:19586.
  echo Open http://192.168.0.100:81/leadership-cockpit and press Ctrl+F5.
)
pause
