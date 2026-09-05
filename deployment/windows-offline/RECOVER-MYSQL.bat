@echo off
chcp 65001 >nul
cd /d "%~dp0"
net session >nul 2>&1
if %errorlevel% neq 0 (
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\recover-mysql-account.ps1"
if errorlevel 1 (
  echo.
  echo [ERROR] MySQL recovery failed. Send data\logs\mysql-error.log to support.
  pause
  exit /b 1
)

echo.
echo [OK] MySQL account recovery completed. Continuing the normal installation...
call "%~dp0INSTALL.bat"
