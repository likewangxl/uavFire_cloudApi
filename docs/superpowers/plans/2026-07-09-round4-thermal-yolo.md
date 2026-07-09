# Round 4：红外 YOLO 接入（用户模型 v2 已训成）

**执行方式**：同前——Claude 出任务书/评审，Codex 编码，进度标记 + 周期播报 + 任务看板。

## 模型事实（已核实）

- 训练产物：`E:\uavfire-training\thermal\runs\thermal-fire-yolov8n-640-v2-20260708\weights\`（`best.pt` 6.2MB / `best.onnx` 12.3MB，2026-07-09 上午导出）
- 架构 YOLOv8n@640，单类别 `names: {0: fire}`，FireMan 伪标签数据集 v2，80 epochs
- 用户后续会继续训练细化版——**接入必须做成"模型路径可配置热替换"**，换模型只改配置

## ai-service 现状锚点（已核实）

- 依赖已含 `ultralytics>=8.3 + torch 2.2`（pyproject.toml），可见光 YOLO 基建现成（`app/inference/visible/detector.py`，接入模式 `visible_detector_mode: "color"|...` + `visible_yolo_model_path`）
- 热分析协议：`app/inference/thermal/analyzer.py` —— `ThermalAnalyzer.analyze(frame: FramePacket) -> float`（置信度 0~1）；现有实现 `HotSpotThermalAnalyzer`（亮度阈值 200 + 饱和占比 0.05）
- 装配点：`app/services/continuous_runner.py`、`task_registry.py`、`task_runner.py`
- 配置：`app/config/settings.py`（pydantic-settings，环境变量可覆盖）
- 测试：`tests/`（pytest），本机无现成 venv

## R4-A（本轮）：ThermalYoloAnalyzer + 模式切换

- 新增 `YoloThermalAnalyzer` 实现 `ThermalAnalyzer` 协议：ultralytics 推理（懒加载，构造可注入 fake predictor），置信度 = fire 类检测框最高 conf；无检出 = 0.0；推理异常 → 记日志返回 0.0（fail-safe，不炸巡逻链路）
- 同时暴露 `last_detections`（框列表,归一化 xywh+conf）供 R4-B 的 ROI 映射与多角度投票使用（本轮只存不发）
- settings 新增：`thermal_detector_mode: "brightness"(默认)|"yolo"|"max"`（max = 两者取大,过渡期推荐）、`thermal_yolo_model_path`、`thermal_yolo_imgsz=640`、`thermal_yolo_conf_threshold=0.25`
- 装配点按 mode 构造对应 analyzer；`yolo`/`max` 模式但模型路径为空或加载失败 → 降级 brightness 并 warn（服务必须能起）
- 测试：fake predictor 单测全覆盖 + `@pytest.mark.skipif`（模型文件存在才跑的真模型冒烟集成测试,路径从环境变量取）
- **部署说明**（写入 README 或 plan）：设 `THERMAL_DETECTOR_MODE=max`、`THERMAL_YOLO_MODEL_PATH=E:\uavfire-training\thermal\runs\thermal-fire-yolov8n-640-v2-20260708\weights\best.pt`;权重不入 git

## R4-B（下一轮,待 R4-A 验收）：多角度投票 + ROI 映射

- agent 环绕各方位点加拍热快照上传 → ai-service 逐张 YOLO → 后端按"K/N 角度检出"投票升级置信度
- YOLO bbox → `ThermalMeasureRegion` 映射,替代/补充帧亮度检测的候选框（测温 seed 更准）
- 细化模型（用户在训）到位后直接换路径,无代码改动

## 验收门

- ai-service pytest 全绿（新增 + 存量）;`thermal_detector_mode=brightness` 时行为与基线零差异（专项测试）
- 真模型冒烟：用数据集 test split 若干正/负样图跑一遍,正样置信度 > 阈值、负样低于（记录数值,供用户判断 v2 模型质量）
