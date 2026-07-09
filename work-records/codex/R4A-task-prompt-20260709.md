# Codex 任务书 R4-A：ai-service 接入红外 YOLO（ThermalYoloAnalyzer）

## 仓库与范围

- 仓库：`D:\likewangx\项目材料\智能集群大载重无人机灭火系统\uavFire_cloudApi_publish`，分支 `feature/fire-precision-and-realtime-detection`（基线 `2da68ed`）
- **只改 `ai-service/`**。agent、backend 零改动。不执行 git commit。**权重文件不得复制进仓库。**
- 依据：`docs/superpowers/plans/2026-07-09-round4-thermal-yolo.md`（先通读）

## 模型事实

- 权重：`E:\uavfire-training\thermal\runs\thermal-fire-yolov8n-640-v2-20260708\weights\best.pt`（YOLOv8n@640，单类 `{0: fire}`）；同目录有 `best.onnx`
- 正/负样图可取自 `E:\uavfire-training\thermal\datasets\thermal-fireman-pseudo-v2-20260708\images\test\`（配对 labels 在同级 labels/test）

## 现状锚点

- `app/inference/thermal/analyzer.py`：`ThermalAnalyzer` 协议 `analyze(frame: FramePacket) -> float`；`HotSpotThermalAnalyzer`（亮度启发式）、`StubThermalAnalyzer`
- `app/inference/visible/detector.py`：可见光 YOLO 已有实现——**先通读并尽量镜像其加载/推理/懒导入风格**
- `app/config/settings.py`：pydantic-settings；:15-17 可见光模式配置是命名范本
- 装配点：`app/services/continuous_runner.py`、`task_registry.py`、`task_runner.py` 中构造 `HotSpotThermalAnalyzer` 处（先 grep 确认真正的构造工厂在哪，只改工厂一处最好）
- 测试：`tests/`（pytest）。本机 ai-service 无 venv。

## 实现要求

1. `app/inference/thermal/analyzer.py`（或同包新文件）新增：

```python
class YoloThermalAnalyzer:
    """ultralytics YOLO 热红外火焰检测,实现 ThermalAnalyzer 协议。"""
    def __init__(self, model_path: str, imgsz: int = 640, conf_threshold: float = 0.25,
                 predictor: Callable | None = None) -> None: ...
    def analyze(self, frame: FramePacket) -> float: ...
    @property
    def last_detections(self) -> list[ThermalDetection]: ...   # 归一化 cx,cy,w,h,conf
```

- ultralytics **懒导入**（构造时不 import；首次 analyze 或显式 load 时加载,`predictor` 注入后完全不触 ultralytics——单测不需要 torch）
- 置信度 = fire 类检出框最高 conf,无检出 0.0；推理/加载异常 → log warning 返回 0.0（fail-safe）,异常后标记 broken 不再重复尝试加载（避免每帧刷异常）
- `last_detections` 保存最近一次结果（R4-B 用,本轮只存）

2. settings 新增（默认值保证零配置时行为与基线完全一致）：`thermal_detector_mode: str = "brightness"`（`brightness|yolo|max`）、`thermal_yolo_model_path: str = ""`、`thermal_yolo_imgsz: int = 640`、`thermal_yolo_conf_threshold: float = 0.25`

3. 装配工厂：按 mode 构造——`brightness` 现状；`yolo` 仅 YOLO；`max` 组合分析器（两者 analyze 取 max,新增小类 `MaxThermalAnalyzer`）；`yolo|max` 但路径为空/文件不存在 → warn 降级 brightness（服务必须能启动）

4. 部署说明：在 `ai-service/README.md` 增补一节（环境变量示例、模式含义、权重路径指向 E: 训练目录、换新模型只改路径）

## 测试要求（pytest）

1. fake predictor 单测：检出取最高 conf / 无检出 0.0 / 异常 fail-safe 0.0 且 broken 后不重试加载 / last_detections 归一化正确
2. `MaxThermalAnalyzer` 取大逻辑
3. 工厂：三种 mode 构造正确;yolo 模式空路径降级 brightness（warn）
4. `thermal_detector_mode` 默认 brightness → 与基线行为零差异（专项断言构造类型）
5. **真模型冒烟**（`@pytest.mark.skipif` 环境变量 `THERMAL_YOLO_SMOKE_MODEL` 未设或文件不存在则跳过）：从数据集 test split 取 ≥3 张有标注（正样）与 ≥3 张无标注/背景图,断言正样最高置信 ≥ conf_threshold、并把每张的置信度数值 print 出来（评审要看 v2 模型的实际表现）
6. 存量 pytest 全部通过

## 验证方式（必须实际执行并粘贴数字）

ai-service 无 venv。优先：
```bash
cd "D:\likewangx\项目材料\智能集群大载重无人机灭火系统\uavFire_cloudApi_publish\ai-service"
uv sync --extra test   # 若 torch 下载过大/超时,降级方案见下
uv run pytest -q       # 存量+新增(不含冒烟)
THERMAL_YOLO_SMOKE_MODEL="E:\uavfire-training\thermal\runs\thermal-fire-yolov8n-640-v2-20260708\weights\best.pt" uv run pytest -q -k smoke -s
```
降级方案（uv sync 失败时）：`python -m venv .venv` + pip 装 fastapi/pydantic/pydantic-settings/numpy/opencv-python-headless/pytest/httpx（不装 torch）,跑除冒烟外全部测试;冒烟测试如无法安装 ultralytics 则报告说明,由评审侧补跑。venv 目录不得入 git（确认 .gitignore 覆盖）。

## 进度可见性要求

每完成一个里程碑向 stdout 打印：`[R4A-PROGRESS] <一句话状态>`（建议节点：分析器实现、settings+工厂、README、单测绿、冒烟结果含数值）。

## 验收标准

- [ ] 上述测试全绿;默认配置零行为差异有专项测试
- [ ] 冒烟测试数值已输出（或明确说明环境受限未跑及原因）
- [ ] 改动严格限于 ai-service/;权重未入库;不执行 git commit
- [ ] 完成报告：改动文件、测试数字、冒烟置信度列表、偏差说明
