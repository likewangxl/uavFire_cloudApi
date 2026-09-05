# UAVFire Windows x64 离线部署包

本包面向没有 JDK、Node.js、Maven、Python、MySQL、Redis 或视频服务环境的 Windows 10/11 x64 电脑。运行时均放在包内，不需要安装开发工具。

## 包含内容

- 已编译前端和 Spring Boot 后端
- Eclipse Temurin JRE 17
- MySQL 8.4 LTS、Redis、Mosquitto MQTT
- MinIO 对象存储
- ZLMediaKit：RTMP 推流、RTSP 拉流、WebRTC 播放
- Python 3.11、AI 服务源码、模型和 Windows 离线依赖轮子
- Nginx 统一入口
- 初始化、启动、停止、状态检查和开机自启动脚本
- MSDK Agent `0.1.24-trial` APK、Windows ADB 工具和 `INSTALL-AGENT.bat`

本包为试用版本，前端、Java 后端和 MSDK Agent 将在 **2026 年 10 月 1 日 00:00（北京时间）**
停止使用。

AI 服务用于兼容历史双流识别和快照接口。当前正式火情识别主链在 RC Plus Agent，本机 ZLMediaKit 主要负责直播展示。

## 机器要求

- Windows 10/11 64 位
- 至少 8 GB 内存，建议 16 GB
- 至少 8 GB 可用磁盘；导入历史数据时需要更多空间
- 第一次安装需要管理员权限
- 建议解压到 `C:\UAVFire`，不要放在网络盘

## 安装

1. 解压完整目录到 `C:\UAVFire`。
2. 建议先双击 `VERIFY.bat`，确认传输和解压后的全部文件校验通过。
3. 双击 `INSTALL.bat`，同意管理员权限。
4. 脚本自动安装 VC++ 运行库、便携 Python 依赖，初始化 MySQL/MinIO，生成配置并启动全部组件。
5. 执行完成后打开脚本显示的地址，默认是 `http://本机局域网IP:81`。
6. 双击 `STATUS.bat`，所有检查应显示 `[OK]`。
7. Windows 服务正常后，连接第一代 RC Plus 并运行 `INSTALL-AGENT.bat`；详细说明见
   `agent\README-AGENT.md`。

首次安装可能需要 5～15 分钟，主要时间用于离线安装 PyTorch/OpenCV。

## 地址或端口修改

默认自动选择局域网 IPv4。若识别错误，编辑 `config\settings.json` 中的 `publicHost`，然后依次运行：

1. `STOP.bat`
2. `RECONFIGURE.bat`
3. `START.bat`
4. `STATUS.bat`

配置文件和数据库密码位于 `config` 目录。安装包内的
`agent-bootstrap-secrets.json` 与 APK 是成对生成的，首次安装会用它生成 `secrets.json`，
使 Java 后端、MQTT 和 Agent 能直接完成鉴权。整个 ZIP 应按部署凭据管理，请勿外发或上传公开网盘。

## 默认端口

| 用途 | 端口 | 对外开放 |
|---|---:|---|
| Web/后端/WebSocket/MQTT WebSocket 统一入口 | 81/TCP | 是 |
| RTMP 推流 | 8089/TCP | 是 |
| WebRTC 媒体 | 19586/TCP+UDP | 是 |
| ZLM HTTP 信令 | 8099/TCP | 不创建防火墙入站放行，Nginx 转发 |
| ZLM RTSP | 8554/TCP | 不创建防火墙入站放行，供本机 AI 使用 |
| Java 后端 | 6790/TCP | 仅本机 |
| MySQL/Redis/MQTT | 3306/6379/1883 | 仅本机 |
| AI/MinIO | 9000/9100/9101 | 仅本机 |

## RC Plus Agent 安装

本包已经包含 `agent\UAVFire-Agent-v0.1.24-trial.apk`，并按 Windows 局域网地址编译：

```properties
agentBackendBaseUrl=http://192.168.1.2:81/
agentAiServiceBaseUrl=http://192.168.1.2:81/
agentMediaHost=192.168.1.2
agentMediaRtmpPort=8089
agentMediaStreamApp=live
agentMqttBrokerUrl=ws://192.168.1.2:81/mqtt
```

运行根目录的 `INSTALL-AGENT.bat` 即可覆盖安装并保留本地数据。Agent 和 Windows 服务器
必须能在局域网直接访问 `192.168.1.2`。Agent 地址是 APK 编译参数；如果服务器 IP 改变，
必须重新构建 APK。

## 日常操作

- `START.bat`：启动全部组件
- `STOP.bat`：停止全部组件
- `STATUS.bat`：检查端口和 HTTP 健康状态
- `VERIFY.bat`：校验包内全部文件是否完整
- `RECONFIGURE.bat`：根据 `settings.json` 重新生成配置
- `INSTALL-AGENT.bat`：使用包内 ADB 安装 RC Plus Agent

日志在 `data\logs`，进程号在 `data\pids`。数据目录在 `data`，升级或迁移前应整体备份。

## 当前数据迁移

本包只包含干净数据库结构和基础管理员数据，不包含现有服务器约 2.4 GB 的业务数据库与对象文件。待新机器服务验证通过后，再停止写入并执行一次单独的数据迁移，避免打包期间产生数据差异。
