# ZLMediaKit：UAVFire 显示链路

## 职责

ZLMediaKit 只连接操作员画面：

```text
RC Plus Agent → RTMP publish → ZLMediaKit → WebRTC playback → cockpit
```

它不向 detector 提供帧，不参与火情确认、悬停、ROI、激光、Outbox 或任务恢复。
ZLMediaKit 故障应表现为“直播不可用”，不能触发检测服务切换或后端定位编排。

## 文件

- `docker-compose.yml`：本地或专网部署入口；
- `.env.example`：主机、端口和流名称模板；
- `config/config.ini`：ZLMediaKit 配置。

## 端口

| 用途 | 端口 |
| --- | --- |
| RTMP publish | `1935/tcp` |
| HTTP API / WebRTC signaling | `58925/tcp` |
| RTSP（媒体调试） | `8554/tcp` |
| WebRTC media | `10000/tcp+udp` |
| STUN | `8000/udp` |
| RTP proxy | `9000/udp` |

`9000/udp` 是媒体 RTP proxy，不能与其他系统使用的同号端口混为一谈。

## 流命名

每架飞机当前只有一个操作员可见主流：

- publish：`rtmp://{ZLM_PUBLIC_HOST}:1935/live/{effectiveSn}-0`
- playback：`webrtc://{ZLM_PUBLIC_HOST}:58925/live/{effectiveSn}-0`

`effectiveSn` 必须与 Agent 当前 aircraft identity 一致。历史双流命名不是当前生产契约。

## 启动与检查

```bash
cd deployment/zlmediakit
cp -n .env.example .env
docker compose up -d
docker compose ps
docker logs uavfire-zlmediakit --tail 100
curl -s 'http://localhost:58925/index/api/getMediaList?secret=psvKeKowZ3tp0Z43oC9O4gWHKFYZAkMy'
```

确认媒体列表包含 `live/{effectiveSn}-0`，backend 给驾驶舱的 `visiblePlayUrl` 指向同一流，
浏览器使用 `ZLMRTCClient.Endpoint` 播放。

## 故障边界

- 无流：检查 Agent publish、网络、防火墙和动态 aircraft SN；
- 有流无画面：检查 WebRTC signaling、UDP/TCP 10000 和浏览器日志；
- ZLM 宕机：记录显示故障，Agent 本地检测与飞行闭环继续按安全门运行；
- 禁止通过增加另一 detector、后端拉流或自动定位命令来“修复”显示问题。

生产验收与证据路径见
[`docs/runbooks/agent-visible-fire-closed-loop-acceptance.md`](../../docs/runbooks/agent-visible-fire-closed-loop-acceptance.md)。
