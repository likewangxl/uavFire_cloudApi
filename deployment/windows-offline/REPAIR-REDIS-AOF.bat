@echo off
chcp 65001 >nul
cd /d "%~dp0"
net session >nul 2>&1
if %errorlevel% neq 0 (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\repair-redis-aof.ps1"
if errorlevel 1 (
  echo.
  echo [ERROR] Redis AOF repair failed. Send the message above and data\logs\redis.log to support.
) else (
  echo.
  echo [OK] Redis, backend, and Nginx proxy verification passed.
)
pause
