# ai-service

`ai-service/` 是面向双流火情识别 PoC 的独立 Python 服务工程骨架，当前提供了最小 FastAPI 服务入口、运行配置默认值和本地开发启动脚本。

## 当前定位

- 承载 FastAPI + OpenCV + YOLO 的 AI 服务工程级配置和本地运行入口。
- 为双流视频源抽象、推理任务管理、火情事件输出与本地验证提供最小骨架。
- 当前推理逻辑仍以占位实现和接口契约为主，不代表真实模型能力已接入。

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

## 当前不承诺项

- 不承诺本阶段已提供真实模型推理、GPU 优化或生产级并发控制。
- 不承诺已集成模型权重、视频流接入、GPU 加速或多路融合逻辑。
- 不承诺提供生产级部署、监控、鉴权、任务队列和性能指标。
