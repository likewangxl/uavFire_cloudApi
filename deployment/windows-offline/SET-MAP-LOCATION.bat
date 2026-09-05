@echo off
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\set-map-location.ps1" -InstallRoot "C:\UAVFire"
if errorlevel 1 echo [ERROR] Location was not configured. See the message above.
pause
