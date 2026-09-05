# 消防领导推广 PPT 更新 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 启动当前系统并重新采集真实运行界面，结合大疆官方 FlyCart 100 素材，制作并验证一份面向消防部门领导的 15 页推广 PPT。

**Architecture:** 现有 PPT 只作为视觉模板；当前仓库服务是系统截图的唯一来源。前端、后端和本地 AI 服务分别启动并验活，浏览器在固定 1920×1080 视口下重新采集页面，所有截图记录 URL、时间和功能。PPT 使用 `@oai/artifact-tool` 按模板复制/编辑模式生成，最后逐页渲染并进行模板忠实度、溢出和空占位符检查。

**Tech Stack:** PowerShell、Java 11、Maven、Vue 3/Vite、Spring Boot、Python/FastAPI/YOLO、Codex in-app Browser、`@oai/artifact-tool`、PowerPoint `.pptx`。

## Global Constraints

- 原始 PPT `C:\Users\51799\xwechat_files\wxid_4gzexwkmkw0w21_d38a\temp\RWTemp\2026-07\9e20f478899dc29eb19741386f9343c8\智能集群大载重无人机灭火系统-推广.pptx` 不得覆盖。
- 最终文件固定为 `D:\likewangx\项目材料\智能集群大载重无人机灭火系统\uavFire_cloudApi_publish\outputs\智能集群大载重无人机灭火系统-消防领导推广版-20260710.pptx`。
- 驾驶舱、工作台、航线规划、火情识别和识别结果必须在本轮启动当前系统后重新截图。
- 禁止复用旧 PPT 截图和 `frontend/codex-*-preview*.png` 等仓库历史预览图。
- 如果使用 mock 或演示数据，PPT 图片说明标注“系统演示环境”。
- FlyCart 100 主图只使用 DJI 大疆官方网页或官方资料素材，并记录来源链接。
- 灭火释放统一表述为“到点待释放、人工确认、全过程留痕”，不得写成无人值守自动脱钩。
- 不声称 UOM 自动完成正式空域审批，不声称新闭环全部完成真机实飞验证。

---

## File Map

- Modify: `docs/superpowers/specs/2026-07-10-uav-fire-promotion-deck-refresh-design.md` — 锁定本轮新截图要求。
- Create: `docs/superpowers/plans/2026-07-10-uav-fire-promotion-deck-refresh.md` — 本实施计划。
- Create: `outputs/智能集群大载重无人机灭火系统-消防领导推广版-20260710.pptx` — 最终交付文件。
- Create in scratch: `%TEMP%\codex-presentations\manual-20260710\uav-fire-promo-refresh\tmp\capture-ledger.txt` — 截图 URL、时间、页面和演示数据说明。
- Create in scratch: `%TEMP%\codex-presentations\manual-20260710\uav-fire-promo-refresh\tmp\source-notes.txt` — 系统证据与外部素材来源。
- Create in scratch: `%TEMP%\codex-presentations\manual-20260710\uav-fire-promo-refresh\tmp\template-audit.txt` — 模板视觉审计。
- Create in scratch: `%TEMP%\codex-presentations\manual-20260710\uav-fire-promo-refresh\tmp\template-frame-map.json` — 15 页到源页的复制映射和编辑对象。
- Create in scratch: `%TEMP%\codex-presentations\manual-20260710\uav-fire-promo-refresh\tmp\deviation-log.txt` — 有意偏离旧稿的记录。
- Create in scratch: `%TEMP%\codex-presentations\manual-20260710\uav-fire-promo-refresh\tmp\build-deck.mjs` — 使用 artifact-tool 编辑模板的构建脚本。
- Create in scratch: `%TEMP%\codex-presentations\manual-20260710\uav-fire-promo-refresh\tmp\qa-ledger.txt` — 逐页视觉检查结果。

---

### Task 1: 启动并验证当前服务

**Interfaces:**
- Consumes: 当前仓库配置、Java 11、Maven、现有 `frontend/node_modules`、`ai-service/.venv` 和仓库内两套 YOLO 权重。
- Produces: `6789` 后端、`8080` 前端、`9000` AI 服务的健康状态及日志；不修改前端或后端配置文件。

- [ ] **Step 1: 检查基础端口和依赖可达性**

Run:

```powershell
Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object LocalPort -In 3306,6379,6789,8080,9000
Test-NetConnection 192.168.50.254 -Port 3306
Test-NetConnection 192.168.50.200 -Port 1883
```

Expected: 记录 MySQL、Redis、MQTT 的实际可达性；已有同端口服务时先识别进程，不重复启动。

- [ ] **Step 2: 使用 Java 11 启动后端**

Run from `backend`:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-11'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
mvn.cmd -pl uavfire spring-boot:run
```

Expected: 日志出现 Spring Boot 启动成功，`Get-NetTCPConnection -LocalPort 6789 -State Listen` 返回监听进程；若依赖不可达，保留完整首个根因日志并按系统化调试流程处理。

- [ ] **Step 3: 以运行时环境覆盖启动前端，不编辑 `.env`**

Run from `frontend`:

```powershell
$env:VITE_APP_APIGATEWAY_BACKEND_HOST='http://127.0.0.1:6789'
$env:VITE_APP_APIGATEWAY_WEBSOCKET_HOST='ws://127.0.0.1:6789'
$env:VITE_OPERATION_MOCK='false'
npm.cmd run serve -- --host 127.0.0.1 --port 8080
```

Expected: Vite 输出 `http://127.0.0.1:8080/`，浏览器请求返回系统登录页。

- [ ] **Step 4: 启动本地 AI 服务并接入当前双模型**

Run from `ai-service`:

```powershell
$env:AI_SERVICE_USE_CONTINUOUS_RUNNER='true'
$env:AI_SERVICE_BACKEND_BASE_URL='http://127.0.0.1:6789'
$env:AI_SERVICE_THERMAL_DETECTOR_MODE='max'
$env:AI_SERVICE_THERMAL_YOLO_MODEL_PATH=(Resolve-Path '.\weights\thermal-fire-yolov8n-640-gt-20260709.pt').Path
$env:AI_SERVICE_VISIBLE_YOLO_MODEL_PATH=(Resolve-Path '.\weights\visible-fire-smoke-yolov8s-960-hardneg-20260702.pt').Path
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 9000
```

Expected: `Invoke-RestMethod http://127.0.0.1:9000/healthz` 返回 `status=ok`，日志确认可见光和红外模型路径已读取。

- [ ] **Step 5: 写入运行证据**

将进程 PID、监听端口、健康响应和启动时间写入 scratch 的 `capture-ledger.txt`；不得把密钥、数据库密码、令牌或完整环境变量写入记录。

---

### Task 2: 用当前系统重新采集五类页面

**Interfaces:**
- Consumes: Task 1 的前后端服务和系统账户。
- Produces: `current-cockpit.png`、`current-operation-workbench.png`、`current-wayline-planner.png`、`current-fire-recognition.png`、`current-fire-result.png`，以及截图台账。

- [ ] **Step 1: 登录并固定采集环境**

通过 in-app Browser 打开 `http://127.0.0.1:8080/`，使用系统现有演示账户登录，固定 1920×1080 视口；隐藏调试浮层和无关浏览器 UI。

Expected: 登录后进入 `/leadership-cockpit`，页面无登录遮罩和明显控制台浮层。

- [ ] **Step 2: 采集领导驾驶舱**

打开 `http://127.0.0.1:8080/leadership-cockpit`，等待数据和地图稳定，截取完整首屏到 scratch `assets/current-cockpit.png`。

Expected: 新截图包含当前火情概览、态势图、设备/任务状态，文件修改时间为本轮执行时间。

- [ ] **Step 3: 采集作战工作台**

打开 `http://127.0.0.1:8080/operation-incidents`，选择一条可展示详情的事件，确保设备分配、操作按钮、合规或时间线区域可见，保存为 `assets/current-operation-workbench.png`。

Expected: 截图来自当前页面；若真实接口无数据，可在浏览器 localStorage 开启系统内置 `uavfire_operation_mock` 并重新加载，同时在台账和 PPT 图片说明标注“系统演示环境”。

- [ ] **Step 4: 采集航线规划**

打开 `http://127.0.0.1:8080/wayline`，进入当前规划模式，展示地图、至少 3 个航点、连线、任务参数和保存/下发入口，保存为 `assets/current-wayline-planner.png`。

Expected: 新截图能一眼看出“地图规划 + 航点 + 任务配置”，不得使用旧稿中的航线截图。

- [ ] **Step 5: 采集火情识别界面**

回到 `/leadership-cockpit` 的“火情监测画面”标签，显示识别控制、当前任务/通道状态和视频或图像区域，保存为 `assets/current-fire-recognition.png`。

Expected: 截图能证明可见光/红外/融合识别入口属于当前系统界面。

- [ ] **Step 6: 采集火情识别结果图**

使用 Task 3 生成的当前识别事件或数据库现有事件，打开结果详情，保证检测框、类别、置信度、温度、风险等级或坐标信息中至少三项清晰可读，保存为 `assets/current-fire-result.png`。

Expected: 结果图由本轮 AI 服务或当前数据库事件生成；若为演示输入，标注“系统演示环境”。

- [ ] **Step 7: 完成截图台账**

对每张图片记录：文件名、页面 URL、采集时间、系统版本 commit、真实/演示数据、展示要点；检查五张图片的哈希与旧 PPT 提取图片、仓库历史预览图均不同。

---

### Task 3: 生成本轮火情识别事件和结果证据

**Interfaces:**
- Consumes: 当前 AI 服务、可见光/红外模型和合法测试图像或视频。
- Produces: 当前识别事件、系统页面可读取的结果记录和一张新采集结果图。

- [ ] **Step 1: 查找项目已有合法测试输入**

在 `ai-service/data`、`ai-service/tests` 和用户提供的项目素材中查找火焰/烟雾图像或短视频；记录来源和用途，不使用旧 PPT 截图充当识别输入。

- [ ] **Step 2: 创建并启动本地双流识别任务**

使用 AI 服务 `POST /api/v1/dual-stream/tasks` 创建唯一任务 ID，并用当前输入文件作为 visible/thermal URL；随后调用 `/start`。

Expected: `/api/v1/dual-stream/tasks/{task_id}/events` 返回至少一条包含分数或风险等级的事件；后端可查询到对应火情事件，或 AI 结果页面可直接展示该事件。

- [ ] **Step 3: 验证结果没有伪造**

保存任务请求、事件响应的脱敏摘要和截图对应关系到 `source-notes.txt`；只记录业务字段，不记录登录令牌。

---

### Task 4: 获取 DJI 官方 FlyCart 100 素材

**Interfaces:**
- Consumes: DJI Enterprise 官方网页和公开产品资料。
- Produces: 一至两张高分辨率 FlyCart 100 官方图片及来源链接。

- [ ] **Step 1: 搜索 DJI 官方来源**

仅检索 `dji.com`、`enterprise.dji.com` 或 DJI 官方资料下载域名，确认产品名称、图片主体和官方归属。

- [ ] **Step 2: 保存适合 16:9 裁切的主图**

下载能够展示 FlyCart 100 机体、大载重吊运或消防应用语境的图片到 scratch `assets/`，避免使用低分辨率缩略图。

- [ ] **Step 3: 记录来源**

在 `source-notes.txt` 中记录页面标题、直接来源 URL、访问日期和拟使用页码；PPT 不加入“大疆官方合作/背书”等未经证实的表述。

---

### Task 5: 按旧稿模板制作 15 页更新版

**Interfaces:**
- Consumes: 源 PPT、五张本轮系统截图、FlyCart 100 官方素材和批准的设计说明。
- Produces: 15 页最终 PPTX、模板映射、构建脚本和来源记录。

- [ ] **Step 1: 初始化 artifact-tool 工作区并复用已完成的模板审计**

Run:

```powershell
$env:HOME='C:\Users\51799'
& 'C:\Users\51799\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe' 'C:\Users\51799\.codex\plugins\cache\openai-primary-runtime\presentations\26.709.11516\skills\presentations\container_tools\setup_artifact_tool_workspace.mjs' --workspace $tmp
```

Expected: scratch `node_modules/@oai/artifact-tool` 可解析，源稿 13 页审计文件存在。

- [ ] **Step 2: 编写并验证模板映射**

创建 15 页 `template-frame-map.json`，每页指定源页、叙事角色和精确 `editTargets`；封面、内容页、时间线和结尾页只能从旧稿中选择最接近的源页复制。

Run:

```powershell
node "$skill\template_following_scripts\validate_template_plan.mjs" --workspace $tmp --map "$tmp\template-frame-map.json"
```

Expected: 验证通过，无未解析 edit target 和无未经授权的新对象。

- [ ] **Step 3: 生成模板 starter deck**

Run:

```powershell
node "$skill\template_following_scripts\prepare_template_starter_deck.mjs" --workspace $tmp --pptx "$tmp\source-deck.pptx" --map "$tmp\template-frame-map.json" --out "$tmp\template-starter.pptx" --preview-dir "$tmp\template-starter-preview" --layout-dir "$tmp\template-starter-layout" --contact-sheet "$tmp\template-starter-contact-sheet.png"
```

Expected: 15 页 starter deck 全部由源页复制，品牌、字体、页脚和背景保留。

- [ ] **Step 4: 编写 `build-deck.mjs` 并替换继承对象**

使用 `PresentationFile.importPptx()` 导入 starter deck；通过解析出的 shape/image ID 修改继承文本和图片，不使用 Python PPTX，不在旧对象上叠放平行设计。

Expected: 页面顺序和文案严格对应设计说明第 4 节，五类截图均来自 Task 2，FlyCart 图片来自 Task 4。

- [ ] **Step 5: 导出独立最终文件**

通过 `PresentationFile.exportPptx()` 输出到全局约束指定路径；确认原始 PPT 的长度和修改时间未变化。

---

### Task 6: 逐页验证并交付

**Interfaces:**
- Consumes: 最终 PPTX、starter deck、模板映射和来源记录。
- Produces: 通过视觉与结构 QA 的交付文件。

- [ ] **Step 1: 渲染全部 15 页并生成总览**

使用 presentation skill 的渲染工具输出逐页 PNG 和 montage；总览只用于检查节奏，每页仍需单独全尺寸查看。

- [ ] **Step 2: 逐页检查**

对 15 页分别检查标题单行、文字换行、截图裁切、识别结果可读性、页脚、来源说明、品牌元素、无调试浮层和无敏感信息，并把结论写入 `qa-ledger.txt`。

- [ ] **Step 3: 运行自动检查**

Run:

```powershell
python "$skill\container_tools\slides_test.py" "$finalPptx"
node "$skill\template_following_scripts\check_template_fidelity.mjs" --workspace $tmp --starter-pptx "$tmp\template-starter.pptx" --final-pptx "$finalPptx" --map "$tmp\template-frame-map.json" --starter-layout-dir "$tmp\template-starter-layout" --final-layout-dir "$tmp\layout\final" --edit-dir $tmp
```

Expected: 无画布溢出、无意外重叠、无空结构占位符、无未授权模板偏离。

- [ ] **Step 4: 最终证据检查**

确认五张系统截图的采集时间属于本轮，且哈希不同于旧稿媒体和历史预览；确认 FlyCart 100 来源是 DJI 官方；确认原始 PPT 未修改。

- [ ] **Step 5: 交付**

提供最终 PPTX 的单一可点击链接，并简要说明系统截图为本轮启动后重新采集、FlyCart 100 素材来自 DJI 官方页面。
