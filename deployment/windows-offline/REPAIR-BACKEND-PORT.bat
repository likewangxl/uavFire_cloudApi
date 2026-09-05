@echo off
chcp 65001 >nul
cd /d "%~dp0"
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\repair-backend-port.ps1"
if errorlevel 1 (
  echo.
  echo [ERROR] Backend port repair failed. Read the message above and send it to support.
) else (
  echo.
  echo [OK] Backend port repair completed.
)
pause
