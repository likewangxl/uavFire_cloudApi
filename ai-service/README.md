# ai-service

`ai-service/` 是面向 M4T 直播流火情识别 PoC 的独立 Python 服务。它已经具备 FastAPI task lifecycle、连续视频消费、可见光启发式/YOLO 检测、热成像热点分析、融合事件、截图和 backend fire-event 回传能力。

## 当前定位

- 从 ZLMediaKit RTSP 或本地视频/图片读取 visible/thermal 输入。
- 输出 dual-stream detection event 到 backend `DualStreamServiceImpl`。
- 对达到阈值的事件生成 `FireEvent`，触发消防任务流。
- 在无 thermal 独立流时支持单可见光降级运行。

## 目录结构

```text
ai-service/
├── .env.example
├── README.md
├── app/
│   ├── api/
│   ├── config/
│   ├── fusion/
│   ├── main.py
│   ├── models/
│   ├── services/
│   └── video/
└── pyproject.toml
```

## 本地启动与检查

使用项目虚拟环境直接启动开发服务：

```bash
cd ai-service
./scripts/run-dev.sh
```

等价命令：

```bash
cd ai-service
./.venv/bin/python -m uvicorn app.main:app --host 0.0.0.0 --port 9000 --reload
```

服务启动后可用以下方式验证健康检查：

```bash
cd ai-service
curl http://127.0.0.1:9000/healthz
```

预期返回：

```json
{"status":"ok"}
```

## 无真机本地识别闭环

没有 RC Plus 真机时，可以用本地图片或视频文件验证识别与 backend 回传：

```bash
cd ai-service
AI_SERVICE_USE_CONTINUOUS_RUNNER=true \
AI_SERVICE_BACKEND_BASE_URL=http://127.0.0.1:6789 \
AI_SERVICE_BACKEND_USERNAME=adminPC \
AI_SERVICE_BACKEND_PASSWORD=adminPC \
AI_SERVICE_BACKEND_LOGIN_FLAG=1 \
./.venv/bin/python -m uvicorn app.main:app --host 0.0.0.0 --port 9000
```

然后创建并启动任务：

```bash
curl -X POST http://127.0.0.1:9000/api/v1/dual-stream/tasks \
  -H 'Content-Type: application/json' \
  -d '{"task_id":"ai-local-001","drone_sn":"AI_LOCAL","visible_stream_url":"/tmp/uavfire-ai-visible.png","thermal_stream_url":""}'

curl -X POST http://127.0.0.1:9000/api/v1/dual-stream/tasks/ai-local-001/start
curl http://127.0.0.1:9000/api/v1/dual-stream/tasks/ai-local-001/events
```

无模型默认使用可见光颜色启发式检测器；配置 `AI_SERVICE_VISIBLE_YOLO_MODEL_PATH` 后切换为 YOLO 检测器。
颜色启发式的默认满分阈值为 `AI_SERVICE_VISIBLE_FIRE_SATURATION_RATIO=0.01`，用于让火焰占比较小的视频片段也能触发中风险冒烟。

## YOLO 权重接入

当前默认下载目标是 Hugging Face 上的 `SalahALHaismawi/yolov26-fire-detection` 权重。模型卡标注 MIT 许可，类别包含 `fire`、`smoke` 和 `other`。

```bash
cd ai-service
MODEL_PATH="$(./scripts/download-yolo-fire-model.sh)"

AI_SERVICE_USE_CONTINUOUS_RUNNER=true \
AI_SERVICE_BACKEND_BASE_URL=http://127.0.0.1:6789 \
AI_SERVICE_BACKEND_USERNAME=adminPC \
AI_SERVICE_BACKEND_PASSWORD=adminPC \
AI_SERVICE_BACKEND_LOGIN_FLAG=1 \
AI_SERVICE_VISIBLE_YOLO_MODEL_PATH="$MODEL_PATH" \
./.venv/bin/python -m uvicorn app.main:app --host 0.0.0.0 --port 9000
```

权重默认放在 `/tmp/uavfire-models/yolov26-fire-detection-best.pt`，不提交到仓库。

也可以用在线公开素材跑一组正负样本冒烟：

```bash
cd ai-service
./scripts/run-online-media-smoke.sh
```

脚本会下载 Wikimedia Commons 的 `Fire fire flames.jpg` 火焰图、`Amazon green forest.jpg` 森林图和 `Fire flames 9652 Nevit.ogv` 火焰视频到 `/tmp/uavfire-online-media`。如果本机有 `ffmpeg`，脚本会把 OGV 转成 5 秒 MP4 后作为 visible stream 创建任务，并打印 AI 服务事件与后端回传事件。

## 热红外 YOLO 权重接入

热红外检测默认仍使用原亮度启发式，零配置部署与基线行为一致：

```dotenv
AI_SERVICE_THERMAL_DETECTOR_MODE=brightness
```

可选模式：

- `brightness`：只使用现有热像热点亮度分析器。
- `yolo`：只使用热红外 YOLO 模型。
- `max`：同时运行亮度分析器和 YOLO，取两者较大置信度。

接入本轮 v2 热红外模型时，环境变量示例：

```dotenv
AI_SERVICE_USE_CONTINUOUS_RUNNER=true
AI_SERVICE_THERMAL_DETECTOR_MODE=max
AI_SERVICE_THERMAL_YOLO_MODEL_PATH=E:\uavfire-training\thermal\runs\thermal-fire-yolov8n-640-v2-20260708\weights\best.pt
AI_SERVICE_THERMAL_YOLO_IMGSZ=640
AI_SERVICE_THERMAL_YOLO_CONF_THRESHOLD=0.25
```

权重文件保留在训练输出目录，不复制进仓库。后续换新模型时保持模式不变，只需要把
`AI_SERVICE_THERMAL_YOLO_MODEL_PATH` 改成新的 `best.pt` 路径。若 `yolo` 或 `max`
模式下路径为空或文件不存在，服务会记录 warning 并降级到 `brightness`，保证进程可启动。

## ZLM RTSP 直播流跑火情识别

`scripts/run-dev.sh` 现在会自动 `source .env`，所以可以把所有配置写在 `ai-service/.env` 里：

```dotenv
AI_SERVICE_USE_CONTINUOUS_RUNNER=true
AI_SERVICE_BACKEND_BASE_URL=
AI_SERVICE_VISIBLE_YOLO_MODEL_PATH=/tmp/uavfire-models/yolov26-fire-detection-best.pt
OPENCV_FFMPEG_CAPTURE_OPTIONS="rtsp_transport;tcp"
```

`OPENCV_FFMPEG_CAPTURE_OPTIONS=rtsp_transport;tcp` 强制 cv2 走 TCP 拉 RTSP，避免内网 UDP 丢包触发连续 read-fail 看门狗。

启动并把单路可见光指向 ZLM：

```bash
./scripts/run-dev.sh

curl -X POST http://localhost:9000/api/v1/dual-stream/tasks \
  -H 'Content-Type: application/json' \
  -d '{"task_id":"zlm-demo","drone_sn":"<DRONE_SN>","visible_stream_url":"rtsp://<ZLM_HOST>:8554/live/<STREAM_KEY>","thermal_stream_url":""}'
curl -X POST http://localhost:9000/api/v1/dual-stream/tasks/zlm-demo/start
curl http://localhost:9000/api/v1/dual-stream/tasks/zlm-demo/events
```

每帧的 `visible_score / thermal_score / fusion_score / risk_level` 同步打印到 ai-service 日志。若拉流失败或连续读帧失败，worker 会在日志里输出 `ERROR app.services.continuous_runner | task=... failed to open ...` 或 `WARNING ... exiting after N consecutive read failures`，不会再静默死掉。

当前默认运行配置：

- `host=0.0.0.0`
- `port=9000`
- `log_level=INFO`
- `max_concurrent_tasks=2`

## 当前 MSDK 迁移注意事项

- 当前 backend / frontend 仍会显式传入 stream URL；`ai-service` 尚未按 `droneSn` 主动查询 backend DualStream group。
- MSDK agent 当前默认流名是 `{droneSn}-0` 或 `{agentAircraftSn}-0`，不是旧设计中的 `{droneSn}_visible` / `{droneSn}_thermal`。
- 如果要监测 agent 推到 ZLM 的单路可见光，visible URL 应为：

```text
rtsp://172.20.10.7:8554/live/{streamId}
```

其中 `streamId` 当前通常是 `{aircraftSn}-0` 或 `RC_PLUS_LOCAL-0`，取决于 agent build config。
- 还没有 `CompositeSliceVideoSource`。如果后续采用 MSDK side-by-side/PIP 复合流，需要新增一层视频源装饰器，把单帧按布局切成 visible/thermal 两路 `FramePacket`。

## 当前不承诺项

- 不承诺模型效果达到生产级；YOLO 权重和阈值仍需现场数据标定。
- 不承诺 GPU 优化或生产级并发控制。
- 不承诺提供生产级部署、监控、鉴权、任务队列和性能指标。
