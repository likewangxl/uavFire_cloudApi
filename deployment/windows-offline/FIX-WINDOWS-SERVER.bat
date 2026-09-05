@echo off
chcp 65001 >nul
cd /d "%~dp0"
net session >nul 2>&1
if %errorlevel% neq 0 (
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\fix-windows-server.ps1"
if errorlevel 1 (
  echo.
  echo [ERROR] Windows server repair failed. Send data\logs to support.
  pause
  exit /b 1
)

echo.
echo [OK] Windows server address and AI service were repaired.
pause
