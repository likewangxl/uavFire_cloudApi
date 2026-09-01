# M300 Agent 火情识别 P0 验收

## 权威事件链

1. Agent 对连续帧确认后，将事件元数据、原始 JPEG、JPEG SHA-256、模型 SHA-256 和采集时间原子写入本地 SQLite Outbox。
2. Media 阶段先用 Agent JWT 上传 JPEG。每次请求携带当前毫秒时间戳和一次性 nonce；服务端默认要求 HTTPS、校验 JWT 绑定的 `droneSn` 并拒绝超时或重放请求。
3. 上传返回的 URL、哈希与采集时间写回同一 Outbox；随后 Event 阶段提交稳定 `event_id`。只有后端重新核验本地对象、URL、哈希和采集时间后，事件才标记 `VERIFIED` 并进入 `fire_event`。
4. 未认证的旧 `/tasks/{task}/events` 入口不能提交 `agent-visible-onnx`；缺图、哈希不匹配、模型哈希缺失、设备或任务不匹配均不得创建火情/灭火任务。
5. Agent 仅在收到 `accepted` 或 `duplicate` 业务回执后完成 Outbox；401/403 会清 JWT，网络和 5xx 继续按既有退避策略重试。

生产部署必须提供 HTTPS 反向代理或服务端 TLS，并设置 `WAYLINE_AGENT_FIRE_EVIDENCE_PUBLIC_BASE_URL` 为遥控器和驾驶舱均可访问的 HTTPS 地址。使用反向代理时，仅在代理会覆盖转发头且应用端口不能被外部直连的前提下设置 `SERVER_FORWARD_HEADERS_STRATEGY=framework`。仅本机开发可显式设置 `WAYLINE_AGENT_FIRE_EVENT_REQUIRE_HTTPS=false`。

## 真机标定数据

Agent 会在应用外部文件目录的 `fire-calibration/m300-inference.jsonl` 记录每次推理的时延、最高类别/分数、连续帧确认结果和模型哈希，文件达到 20 MiB 时保留一个轮转文件。

现场至少覆盖：明火、烟雾、阳光/反光、云雾、热源非火、不同高度/焦距、逆光、抖动和弱网。导出 JSONL 后建立 CSV 标签文件：

```csv
source_ts,ground_truth
1788141600000,fire
1788141600400,none
```

执行：

```powershell
python rcplus-msdk-agent/tools/m300_fire_calibration_report.py m300-inference.jsonl --labels labels.csv --flight-hours 2
```

默认门槛为 precision ≥ 0.90、recall ≥ 0.90、误报 ≤ 1 次/小时、P95 推理时延 ≤ 400 ms；项目现场可用命令行参数调整，但调整必须留存原因和报告。没有真机采集及人工标签报告时，不得宣称 M300 现场验收通过。
