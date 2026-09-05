@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo Edit config\settings.json first if the detected IP or ports need to change.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\configure.ps1"
pause
