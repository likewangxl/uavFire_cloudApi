# M4T 双流 MSDK 执行层与 AI 服务设计

## 目标

在当前仓库内新增两个独立工程：

- `rcplus-msdk-agent/`：运行在 RC Plus 2 上的 Android + DJI MSDK v5 执行层工程
- `ai-service/`：运行在后端环境中的双流火情识别服务工程

本次设计目标不是直接宣称“双流正式版已完成”，而是为方案文档中的 PoC 路径建立可运行、可扩展、可接入现有前后端的工程基础。

## 背景与边界

依据 [m_4_t双流直播与火情识别专项详细设计方案（评审修订版）](../../../m_4_t双流直播与火情识别专项详细设计方案（评审修订版）.md)：

- 本方案不是 Cloud API 原生双流方案
- 双流前提是 RC Plus 2 上允许部署第三方轻型 App
- AI 服务第一阶段允许采用“可见光检测 + 红外伪彩图复核 + 融合打分”
- 若真机 PoC 不通过，必须可退化为“单流 + 镜头切换”

因此本次实现边界如下：

### 本次实现包含

- 新建 Android / MSDK 执行层工程骨架
- 新建 AI 服务工程骨架
- 定义双流运行态、健康状态、时间锚点与推流/识别接口
- 让现有仓库具备继续推进 PoC 的工程承载能力

### 本次实现不承诺

- 在无 RC Plus 2 真机和无设备授权条件下完成真机联调
- 在当前阶段完成稳定的 M4T 双流正式商用能力
- 在当前阶段完成热成像原始温度矩阵分析
- 在当前阶段完成 FC100 联动闭环

## 工程拆分

## 1. RC Plus 2 MSDK 执行层工程

### 1.1 路径

新增目录：

- `rcplus-msdk-agent/`

### 1.2 技术栈

- Kotlin
- Android Gradle
- DJI MSDK v5
- 协程 + Flow
- OkHttp / Retrofit
- RTMP 推流预留适配层

### 1.3 责任

- 管理 DJI SDK 初始化与设备连接状态
- 探测 M4T 可见光 / 红外能力
- 建立双流会话运行态
- 为后续双路订阅、双路帧监听、双路推流提供统一抽象
- 回传健康状态、能力状态、时间锚点与流状态

### 1.4 架构

建议分为 5 层：

- `sdk/`
  负责 DJI MSDK 初始化、设备连接、相机能力查询
- `session/`
  负责双流会话生命周期与状态机
- `stream/`
  负责 visible / thermal 两路源的订阅抽象、推流抽象
- `api/`
  与现有后端交互，接收 start/stop/focus 指令，回报状态
- `app/`
  Android UI 与前台服务入口，PoC 阶段仅保留最小调试界面

### 1.5 核心状态模型

- `AgentConnectionState`
  - `IDLE`
  - `SDK_READY`
  - `AIRCRAFT_CONNECTED`
  - `CAPABILITY_READY`
  - `STREAMING`
  - `DEGRADED`
  - `ERROR`

- `DualStreamSessionState`
  - `INIT`
  - `STARTING`
  - `RUNNING`
  - `STOPPING`
  - `STOPPED`
  - `FAILED`

- `StreamChannelType`
  - `VISIBLE`
  - `THERMAL`

### 1.6 对外接口

执行层对现有后端暴露 HTTP 回调或轮询式上报接口：

- `POST /internal/dual-stream/agents/{droneSn}/heartbeat`
- `POST /internal/dual-stream/agents/{droneSn}/status`
- `POST /internal/dual-stream/agents/{droneSn}/capability`

执行层从后端接收：

- `POST /internal/dual-stream/agents/{droneSn}/start`
- `POST /internal/dual-stream/agents/{droneSn}/stop`
- `POST /internal/dual-stream/agents/{droneSn}/focus`

说明：
- 真机推流细节先以接口抽象固化
- 没有真机时可以先用 mock provider 验证状态机和链路编排

### 1.7 关键设计原则

- 必须存在 `MockStreamProvider`，允许无真机时先跑通状态机
- 真机流能力实现与 mock 实现通过接口解耦
- 所有时间同步统一打近端采集时间戳
- 推流层只定义抽象与接入点，不在第一版写死某个推流库实现

## 2. AI 服务工程

### 2.1 路径

新增目录：

- `ai-service/`

### 2.2 技术栈

- Python 3.11
- FastAPI
- PyTorch
- Ultralytics YOLO
- OpenCV
- FFmpeg
- Pydantic

技术依据：

- FastAPI 官方文档：https://fastapi.tiangolo.com/
- PyTorch 官方文档：https://pytorch.org/
- Ultralytics YOLO 官方文档：https://docs.ultralytics.com/
- FFmpeg 官方文档：https://www.ffmpeg.org/documentation.html

### 2.3 为什么不用“单一现成开源项目”

当前没有一个现成开源仓库能直接满足你的目标：

- visible + thermal 双流并行消费
- 时间锚点对齐
- 红外伪彩图热点复核
- 融合评分
- 标准化服务接口

因此 AI 服务采用“基础设施 + 算法模块”组合方式：

- 服务层：FastAPI
- 推理层：PyTorch / Ultralytics
- 视频层：FFmpeg / OpenCV
- 融合层：自研时间对齐与评分逻辑

### 2.4 算法能力分级

#### 第一阶段：可落地 PoC 能力

- visible 路：
  - 火焰检测
  - 烟雾检测
  - 连续帧稳定性判断

- thermal 路：
  - 红外伪彩图热点区域检测
  - 热异常持续性分析
  - 热区面积 / 强度近似特征

- 融合层：
  - 依据 source timestamp 做时间窗口对齐
  - 依据 bbox 中心点 / 近邻区域做弱空间匹配
  - 输出 `LOW / MEDIUM / HIGH`

#### 第二阶段：增强能力

- 引入双模态 RGBT 检测网络
- 引入跟踪与稳定告警
- 若设备能力允许，再接原始热数据矩阵

### 2.5 必须明确的技术限制

- 若当前只有红外伪彩图视频，AI 只能做近似热异常分析，不能宣称真实温度分析
- 若没有可用数据集，本阶段只能提供服务骨架与默认基线模型接入能力，不能保证最终识别准确率
- 双流“工程可做”不等于“双流效果已达标”，仍需真机 PoC 验证

### 2.6 AI 服务模块拆分

- `app/main.py`
  FastAPI 入口
- `app/api/`
  提供健康检查、任务接口、结果查询
- `app/config/`
  环境配置
- `app/video/`
  拉流、探测、抽帧、时间戳
- `app/inference/visible/`
  visible 模型推理
- `app/inference/thermal/`
  thermal 伪彩图热点分析
- `app/fusion/`
  双流时间对齐与融合评分
- `app/models/`
  DTO / schema
- `app/storage/`
  识别结果、证据预留写入适配

### 2.7 服务接口

PoC 阶段建议至少包含：

- `POST /api/v1/dual-stream/tasks`
  创建一个双流识别任务
- `POST /api/v1/dual-stream/tasks/{taskId}/start`
  启动识别
- `POST /api/v1/dual-stream/tasks/{taskId}/stop`
  停止识别
- `GET /api/v1/dual-stream/tasks/{taskId}`
  查询任务状态
- `GET /api/v1/dual-stream/tasks/{taskId}/events`
  查询识别事件
- `GET /healthz`
  健康检查

### 2.8 输出事件模型

第一版统一输出：

- `event_id`
- `drone_sn`
- `source_ts`
- `visible_bbox`
- `thermal_bbox`
- `visible_score`
- `thermal_score`
- `fusion_score`
- `risk_level`
- `sync_status`

后续再扩展到你文档中的 `fire_dual_stream_record`

## 3. 与现有后端 / 前端的衔接

### 3.1 后端

现有后端负责：

- 保存双流组运行态到 Redis
- 暴露 `GET/POST /api/v1/live/groups/{drone_sn}` 系列接口
- 协调 `rcplus-msdk-agent` 与 `ai-service`

### 3.2 前端

现有前端负责：

- 驾驶舱双画面播放
- 双路状态与焦点切换
- 双流健康状态展示
- AI 告警面板展示

## 4. 分阶段实施建议

### 第一阶段：新工程骨架

- 建立 Android / MSDK 工程
- 建立 AI 服务工程
- 跑通最小启动与健康检查

### 第二阶段：后端接入骨架

- 定义双流组运行态
- 打通 agent / ai 的后端接口

### 第三阶段：前端双画面

- 驾驶舱接双流组接口
- visible / thermal 双窗口展示

### 第四阶段：识别 PoC

- visible 模型推理
- thermal 热点复核
- 融合评分

## 5. 风险控制

### 风险 1：真机能力未证实

应对：
- 执行层必须提供 mock 能力
- 所有真机依赖点都做接口抽象

### 风险 2：AI 被误解为“已经满足正式生产要求”

应对：
- 文档与代码均明确第一版是 PoC 能力
- 不承诺绝对温度分析

### 风险 3：工程一次铺太大

应对：
- 先建骨架，再逐层接入
- 不在第一版直接做端到端灭火联动

## 6. 当前已验证范围

截至 2026-04-21，当前仓库内已完成并验证的范围如下：

- Android 侧：
  - `rcplus-msdk-agent` 最小 app 壳可本机构建
  - `session` / `sdk` / `api` 三层骨架已落地
  - mock 状态机、能力抽象、后端请求组装的单元测试已通过
- AI 侧：
  - `ai-service` 的 FastAPI 入口、任务创建 / start / stop / query / events 已本地通过
  - 视频源抽象、可见光/红外占位分析、融合评分逻辑已本地通过
  - 配置模型、运行脚本和 `/healthz` 健康检查已本地通过

本轮明确未验证的范围如下：

- 真机双流采集
- DJI MSDK 真连与真机能力读取
- 真实视频流消费
- 真实模型效果、准确率和稳定性
- 生产级并发、持久化、监控与容错

因此，本设计当前应被理解为：

- Android 侧当前为“骨架 + mock 测试通过”
- AI 侧当前为“API / 生命周期 / 融合逻辑本地通过”
- 真机双流、MSDK 真连、真实模型效果不在本轮验证范围
