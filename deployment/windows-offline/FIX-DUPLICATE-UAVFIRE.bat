@echo off
chcp 65001 >nul
cd /d "%~dp0"
net session >nul 2>&1
if %errorlevel% neq 0 (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\fix-duplicate-uavfire.ps1"
if errorlevel 1 (
  echo.
  echo [ERROR] Duplicate UAVFire cleanup or service repair failed.
  echo Leave the window open and send the complete error to support.
) else (
  echo.
  echo [OK] Only C:\UAVFire is active. Redis, backend, Nginx and auto-start are healthy.
)
pause
