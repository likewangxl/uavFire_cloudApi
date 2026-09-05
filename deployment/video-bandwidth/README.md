# 4 高清＋16 低清部署与验收

本期交付一个最多 20 架的带宽域：LOW 为 500,000 bit/s、SD 输出，HIGH 为 4,000,000 bit/s、FULL_HD 输出；最多 4 个高清授权。镜头及 ONNX 帧输入不因档位策略主动修改。

## 部署步骤

1. 记录后端/前端提交、APK SHA-256、Agent 版本 `0.1.28-video-policy-trial` / versionCode 29、ZLM 二进制或容器摘要。确认目标环境只有一个后台调度实例；滚动更新必须先停旧调度实例，不能多活。当前已有试用期限保持不变。
2. 保持 `video-bandwidth.enabled=false`，先在地面升级单机 Agent。新版无有效策略时低清启动；正常视频取流、相机镜头和本地推理须同时验证。
3. 将 [配置示例](application-video-bandwidth.yml.example) 中的内容合并进实际加载的外部后端配置，填入真实机群 SN，最多 20 个。先用测试机名单启用，再扩大到全部已升级机群；禁止在通用 APK 中写同一固定 SN。
4. 配套部署新前端。驾驶舱查看的飞机产生 20 秒观看需求，5 秒续租；页面后台/退出释放自己的需求。发生经后端验证的新火情后，相关飞机获得 60 秒优先权，仍不能突破四个名额。
5. 开启 `enabled=true` 并启动唯一后端实例。启动前 25 秒不发高清，用于等待旧租约和设备降档保护期。每台 Agent 2 秒轮询，高清租约最长 15 秒；旧机停续租后保留名额至到期再加 10 秒保护期，交接期间可以少于四路高清。
6. 策略使用既有 Agent token 和 HTTPS/防重放校验。配置实际可达且证书受信任的 HTTPS 入口和既有共享认证；不要把 401/403/426 当成码率功能故障，也不要为上线随意关闭验证。局域网例外按已有部署政策单独配置。

策略只调整已按既有业务流程启动的源流，不负责批量启动 20 个飞行/识别任务；20 路持续上传验收前，先确认这 20 路均已起流。

## 核验路径

- 身份绑定策略：`POST /manage/api/v1/dual-stream/agents/{sn}/video-policy`。请求头 `x-agent-token`、`x-agent-timestamp`、`x-agent-nonce`。正文 snake_case，`protocol_version=1`，包含随机进程 `instance_id`、`streaming`、`applied_profile`、`configured_bitrate_bps`、SDK 原始观测和 `error`。
- 响应：`protocol_version`、`drone_sn`、`instance_id`、`profile`、`bitrate_bps`、`valid_for_ms`、`lease_id`、`reason`。只有身份匹配、有效期不超过 15 秒且码率匹配的 HIGH 才能生效。Agent 从请求发起时计算有效期，网络延迟不能延长它。
- 工作区状态：登录后请求 `GET /manage/api/v1/video-bandwidth/status`。核对 `aircraft[].target_profile` 与 `agent.applied_profile`，保留 `report_age_ms`、`lease_remaining_ms`、`reserved`、`outstanding_high`、`agent.error` 等诊断字段；跨工作区不返回设备细节。
- `sdk_vbps` 保留 DJI 原始数值，未假定它等于 bit/s；使用 ZLM 入站字节差值和抓包校准。浏览器显示的 Mbps 来自接收字节差值，不能作为遥控器上行的唯一证据。

## RTC 地址与端口

仓库 Docker 的 `config/config.ini` 配置 `[rtc].port=8000` / `tcpPort=8000`；UDP 8000 承载 RTC，包括 STUN、DTLS 和媒体。Compose 的旧变量 `ZLM_STUN_UDP_PORT` 对应这一路 RTC UDP；`ZLM_WEBRTC_UDP_PORT` 名称有历史遗留，映射的是内部 10000，不能据名称认定 RTC 在 10000。

Windows 由 `configure.ps1` 根据 `settings.zlmRtcPort` 同步生成 RTC UDP/TCP 端口；应检查 `config/zlmediakit.ini` 及 MediaServer 的 `-c` 路径。`rtc.externIP` 必须是观看端可达地址。Docker/NAT/防火墙与实际监听保持一致。正式交付要指定并记录已验证 ZLM 版本/摘要，不能把仓库示例的浮动 master 当作版本锁定。

## 现场验收表

| 项目 | 通过条件 | 本轮结果 |
| --- | --- | --- |
| 单机 LOW/HIGH | 真正输出约 540p/0.5 Mbps 和 1080p/4 Mbps；检查峰值与长时均值 | 待真机 |
| 20 机稳态 | 4 HIGH＋16 LOW、原始约 24 Mbps；实际开销和重传另测 | 待真机 |
| 同时争用高清 | 20 机请求时最多 4 个保留名额；旧机降档后再升新机 | 自动测试＋待真机 |
| 网络断开/服务器重启 | Agent 本地过期降 LOW；恢复不瞬时全机高清 | 自动测试＋待真机 |
| 多人观看 | 同一飞机只发布一条源流；用户关窗不影响其他订阅 | 自动测试＋待现场 |
| 本地 AI | 两档切换时输入帧尺寸/帧龄、ONNX 与证据链正常 | 待真机 |
| 延迟 | 计时器法测玻璃到玻璃，报告 P50/P95/最大值及冻结时长，连续 30 分钟不积压 | 待现场 |

共享上行初算不少于约 43 Mbps 可用容量，另为遥测/证据和背景业务留预算。按相同余量估算，单台高清所在上行需约 7.2 Mbps 可用容量，低清约 0.9 Mbps；20 台走独立蜂窝链路时，不能用中心总带宽代替逐台链路检查。一个观众看全 20 路约 30 Mbps 下载，5 个观众全看约 150 Mbps 媒体出口（计入 25% 开销）；按 70% 持续利用率需约 215 Mbps 可用出口。当前界面仍为选机主画面及预览，本轮没有新增 20 宫格。

调度控制的是参与的新版 Agent 及其目标编码；CPU 完全挂起、SDK 不响应、旧 APK 或未登记的额外推流均不属于已保证的流量。服务器入站必须复核这些情况。软件测试通过不替代真实无线环境测试。

## 回退

关闭 `video-bandwidth.enabled`，重启唯一后端，等待最长租约和设备保护期，确认全部回到 LOW。视频源继续存在；不为回退自动安装旧版全高清 Agent。保留错误/码率记录后再定位，避免用增大缓存掩盖持续带宽不足。
