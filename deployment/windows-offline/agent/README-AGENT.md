# MSDK Agent 安装说明

本目录包含第一代 DJI RC Plus 使用的 MSDK Agent：

- APK：`UAVFire-Agent-v0.1.24-trial.apk`
- APK 内置了与本 Windows 安装包成对的 MQTT/航线鉴权参数，不要与其他批次安装包混用
- 包名：`com.yinxin.uavfir`
- versionCode：`25`
- 试用截止：`2026-10-01 00:00:00 Asia/Shanghai`

先完成 Windows 主程序安装并确认 `STATUS.bat` 全部显示 `[OK]`，再回到安装包根目录运行
`INSTALL-AGENT.bat`。包内已经带有 Windows 版 ADB，不需要在新电脑上安装 Android SDK、JDK 或
其他开发环境。

当前 APK 已按 Windows 服务器局域网配置编译：

```text
HTTP/WebSocket/MQTT: 192.168.1.2:81
RTMP: 192.168.1.2:8089
模型: visible1088
```

RC Plus 必须能在局域网访问 Windows 服务器 `192.168.1.2`。如果服务器 IP 或端口发生变化，
这个 APK 需要重新构建，不能只改 Windows 的 `settings.json`。

无线安装前需要先通过 USB 在 RC Plus 上执行一次 `adb tcpip 5555`，之后才能输入
`RC Plus IP:5555` 安装。覆盖安装使用 `adb install -r`，会保留 Agent 的本地数据。

安装完成后仍需真机确认：应用版本、飞机 SN、心跳、OSD、命令轮询、RTMP 推流、1088 模型加载以及
航线执行状态。打包和安装成功不等同于真机飞行验收。
