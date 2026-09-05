@echo off
chcp 65001 >nul
cd /d "%~dp0"

set "ADB=%~dp0runtime\android-platform-tools\adb.exe"
set "APK=%~dp0agent\UAVFire-Agent-v0.1.24-trial.apk"

if not exist "%ADB%" (
  echo [ERROR] 缺少 adb.exe，请重新解压完整安装包。
  pause
  exit /b 1
)
if not exist "%APK%" (
  echo [ERROR] 缺少 MSDK Agent APK，请重新解压完整安装包。
  pause
  exit /b 1
)

"%ADB%" start-server >nul
echo 当前可见的 Android 设备：
"%ADB%" devices
echo.
set /p "TARGET=无线 ADB 请输入 RC Plus 地址（如 192.168.1.50:5555），USB 连接直接回车："

if not "%TARGET%"=="" (
  "%ADB%" connect "%TARGET%"
  if errorlevel 1 goto :failed
  "%ADB%" -s "%TARGET%" install -r "%APK%"
) else (
  "%ADB%" install -r "%APK%"
)

if errorlevel 1 goto :failed
echo.
echo [OK] MSDK Agent 0.1.24-trial 安装完成，原应用数据已保留。
pause
exit /b 0

:failed
echo.
echo [ERROR] Agent 安装失败，请确认 USB 调试或无线 ADB 已开启，且设备状态为 device。
pause
exit /b 1
