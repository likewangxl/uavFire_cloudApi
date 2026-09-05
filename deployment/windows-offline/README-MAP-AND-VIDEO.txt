TSA / 航线规划地图与 WebRTC 直播合并修复包

使用：
1. 将压缩包完整解压到 Windows 服务器，保留 scripts 和 frontend 目录。
2. 双击 FIX-MAP-AND-VIDEO.bat，允许管理员权限。
3. 首次设置地图位置时，根据提示依次输入：
   Longitude：服务器实际部署点的经度。
   Latitude：服务器实际部署点的纬度。
   Coordinate system：GPS 经纬度填 WGS84；高德地图经纬度填 GCJ02。
   不要输入 IP 地址，也不要直接使用百度坐标。
4. 看到 Hotfix completed 后，浏览器 Ctrl+F5 刷新。

地图：
- TSA 和航线规划共用服务器部署点，优先级高于浏览器历史中心。
- 已选择航线的预览和飞机定位继续正常工作，可点击“回到服务器部署点”。
- 数据存放在 C:\UAVFire\config\settings.json 的 mapLocation。
- 未设置部署点时，页面会明确提示，不会假称已经定位到服务器。
- 以后搬动服务器，只需运行 C:\UAVFire\SET-MAP-LOCATION.bat 重新设置后刷新网页。
- 修改 IP 与修改地理位置是两件事；不能从 192.168.0.100 推算经纬度。

直播：
- 把 ZLM 返回的旧 WebRTC 地址 192.168.1.2 改为 192.168.0.100。
- 自动重启 UAVFire MediaServer，直播会短暂中断，等待遥控器恢复推流。
- 该项需要在 192.168.0.100 这台 Windows 机器运行。

安装目录固定为 C:\UAVFire。更新前备份前端到 data\backups\map-video-时间。
保留运行时 API 地址及后端数据。已有 V4 直播修复仍可使用此合并包。
如果只安装地图更新，在 PowerShell 中运行：
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\install-map-video-hotfix.ps1 -MapOnly

本机已通过地图规则测试、配置脚本测试和 PowerShell 语法检查。
实际 Windows 服务重启及现场定位结果需在目标机器执行后确认。
