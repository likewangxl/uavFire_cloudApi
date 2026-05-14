# 2026-04-21 交接文档：直播驾驶舱 / Agora 动态 Token / M4T 双流专项

## 最新补充摘要

当前仓库已经完成三块可继续接手的工作：

1. `leadership-cockpit` 已集成 Agora 直播 tab，保留直播 HUD，并修复了直播区域裁切和页面不可滚动的问题。
2. Agora token 已从“写死在配置里”改成后端动态生成，原先的 `dynamic key or token timeout` 问题已从实现层修掉。
3. 基于 `m_4_t双流直播与火情识别专项详细设计方案（评审修订版）.md`，仓库内已新增两个专项子工程骨架：
   - `rcplus-msdk-agent/`
   - `ai-service/`

当前最重要的未完成点只有一个：

- `rcplus-msdk-agent` 的最小 Android app 壳虽然已经写好并补了 Gradle wrapper，但本机还没有可用的 Java 17/21，因此 `./gradlew :app:assembleDebug` 卡在 AGP 的 Java 版本检查上。下一位应先解决这个环境问题，再继续 Task 3 之后的开发。

---

## 一、本轮已完成内容

### 1. 驾驶舱直播

相关文件：

- `frontend/src/pages/page-web/projects/leadership-cockpit.vue`
- `frontend/src/components/WorkspaceLivestreamPanel.vue`
- `frontend/src/pages/page-web/home.vue`
- `frontend/scripts/leadership-cockpit-livestream.test.mjs`

已完成：

- 驾驶舱视觉卡片支持 `态势图 / 直播画面` tab 切换
- 直播 tab 复用共享直播组件并保留 HUD
- 切到直播时底部指标切换为直播态信息
- 修复两类显示问题：
  - 卡片内部高度不足导致下半部分看不到
  - 页面壳不滚动导致整页被裁

验证：

```bash
node --test frontend/scripts/leadership-cockpit-livestream.test.mjs
npm --prefix frontend run build
```

### 2. Agora 动态 token

相关文件：

- `backend/sample/src/main/java/com/dji/sample/manage/service/impl/LiveStreamServiceImpl.java`
- `backend/cloud-sdk/src/main/java/com/dji/sdk/cloudapi/livestream/LivestreamAgoraUrl.java`
- `backend/sample/pom.xml`
- `backend/sample/src/main/resources/application.yml`
- `backend/sample/src/test/java/com/dji/sample/manage/service/impl/LiveStreamServiceImplAgoraConfigTest.java`

已完成：

- `/manage/api/v1/live/agora/config` 每次请求时动态签发 RTC token
- 前端无需改接口契约
- 推流启动路径也同步改成动态 token

验证：

```bash
mvn -pl sample -am -Dtest=LiveStreamServiceImplAgoraConfigTest -Dsurefire.failIfNoSpecifiedTests=false test
```

注意：

- `application.yml` 里已经落了 Agora `app-certificate`
- 功能上可用，但安全上更建议后续迁到环境变量

### 3. 前端中文化

已完成：

- 大部分前端可见英文已替换为中文
- 驾驶舱显示文案已由“领导驾驶舱”改为“驾驶舱”

代表文件：

- `frontend/src/components/common/topbar.vue`
- `frontend/src/pages/page-web/projects/leadership-cockpit.vue`
- `frontend/src/components/WorkspaceLivestreamPanel.vue`
- `frontend/src/pages/page-web/projects/livestream.vue`

验证：

```bash
node --test frontend/scripts/frontend-chinese-copy.test.mjs
npm --prefix frontend run build
```

### 4. M4T 双流专项设计与计划

专项来源：

- `m_4_t双流直播与火情识别专项详细设计方案（评审修订版）.md`

已写文档：

- `docs/superpowers/specs/2026-04-21-m4t-dual-stream-msdk-ai-design.md`
- `docs/superpowers/plans/2026-04-21-m4t-dual-stream-msdk-ai.md`

设计结论：

- 在当前仓库新增两个子工程
- `rcplus-msdk-agent` 负责 RC Plus 2 Android / DJI MSDK 执行层
- `ai-service` 负责双流 visible + thermal 融合识别服务
- AI 第一阶段只承诺 PoC 能力，不承诺真实温度分析和正式商用稳定性

---

## 二、当前专项代码状态

### 1. `rcplus-msdk-agent/`

已经存在的文件：

- `settings.gradle.kts`
- `build.gradle.kts`
- `gradle.properties`
- `README.md`
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/uavfire/rcplus/App.kt`
- `app/src/main/java/com/uavfire/rcplus/MainActivity.kt`
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values/themes.xml`
- `gradlew`
- `gradlew.bat`
- `gradle/wrapper/gradle-wrapper.properties`
- `gradle/wrapper/gradle-wrapper.jar`

当前状态：

- Task 1 已完成
- Task 2 已完成到“最小 app 壳 + wrapper + 真实执行构建验证”
- Task 3 以后还未开始

真实构建结果：

```bash
cd rcplus-msdk-agent
./gradlew :app:assembleDebug
```

当前失败点：

```text
Android Gradle plugin requires Java 17 to run. You are currently using Java 11.
```

定性：

- 这不是代码文件缺失
- 这是本机 JDK 环境阻塞

已知环境事实：

- 默认 `java -version` 还是 1.8
- 可用 OpenJDK 11
- 可用 OpenJDK 25
- 没有现成 Java 17
- Java 25 不能稳定替代 Java 17 跑当前 Kotlin DSL / AGP 组合

### 2. `ai-service/`

已经存在的文件：

- `pyproject.toml`
- `README.md`
- `.env.example`

当前状态：

- 只完成了工程级骨架
- 还没有 FastAPI app
- 还没有任务生命周期、视频源抽象、推理模块、融合模块

---

## 三、下一位接手的建议顺序

### 第一步：先解 `rcplus-msdk-agent` 的 Java 环境

优先目标：

- 给本机补一个 Java 17 或 Java 21
- 确保 `./gradlew` 实际用到的是这个版本

建议先验证：

```bash
cd rcplus-msdk-agent
./gradlew -version
./gradlew :app:assembleDebug
```

如果后续继续失败，优先确认：

- Android SDK 是否安装
- `ANDROID_HOME` / `sdk.dir` 是否可见
- Build Tools / Platform 34 是否存在

### 第二步：按计划继续 `rcplus-msdk-agent` Task 3-5

对应文档：

- `docs/superpowers/plans/2026-04-21-m4t-dual-stream-msdk-ai.md`

顺序建议：

1. Task 3：状态模型 + `MockStreamProvider` + `DualStreamSessionManager`
2. Task 4：MSDK 能力查询抽象
3. Task 5：后端 heartbeat/status/capability 契约

### 第三步：再开始 `ai-service` Task 6-9

等 RC 侧状态机和接口边界稳定后，再落：

- FastAPI app
- 任务创建 / start / stop / query
- visible / thermal / fusion 抽象
- 配置与本地运行入口

---

## 四、最关键的文件入口

接手时优先看：

- `WORK_RECORD.md`
- `HANDOFF_2026-04-21_M4T_DUAL_STREAM_AND_LIVESTREAM.md`
- `docs/superpowers/specs/2026-04-21-m4t-dual-stream-msdk-ai-design.md`
- `docs/superpowers/plans/2026-04-21-m4t-dual-stream-msdk-ai.md`
- `rcplus-msdk-agent/`
- `ai-service/`
- `frontend/src/pages/page-web/projects/leadership-cockpit.vue`
- `frontend/src/components/WorkspaceLivestreamPanel.vue`
- `backend/sample/src/main/java/com/dji/sample/manage/service/impl/LiveStreamServiceImpl.java`

---

## 五、不要误判的点

- `rcplus-msdk-agent` 当前不是“还没写 app 壳”，而是“app 壳和 wrapper 都写了，但卡 JDK 17”
- `ai-service` 当前不是“功能写了一半”，而是“只有工程骨架，代码还没开始”
- 驾驶舱直播页面问题不是未修，而是已经修到可滚动、可看下半部分
- Agora token 过期问题不是未处理，而是已经改成后端动态签发
