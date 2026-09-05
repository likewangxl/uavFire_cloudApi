@echo off
chcp 65001 >nul
cd /d "%~dp0"
net session >nul 2>&1
if %errorlevel% neq 0 (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\set-backend-port.ps1" -BackendPort 6789
if errorlevel 1 (
  echo.
  echo [ERROR] Backend port configuration failed. Send the message above and data\logs to support.
) else (
  echo.
  echo [OK] Backend is running on TCP 6789 and Nginx proxy verification passed.
)
pause
