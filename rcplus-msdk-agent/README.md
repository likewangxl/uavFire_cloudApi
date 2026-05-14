# rcplus-msdk-agent

`rcplus-msdk-agent/` 是面向 RC Plus 2 Android 端的 DJI MSDK v5 执行层子工程骨架，当前已经具备最小 `:app` 模块壳、Gradle Wrapper 和后续会话/能力/通信层的接入边界。

## 当前定位

- 承载双流 PoC 的 Android 执行层工程级配置与最小应用壳。
- 为后续会话状态机、MSDK 接入、后端通信与本地联调提供独立工程边界。
- 当前 `:app` 只包含最小入口页面，不包含 session / sdk / api 等后续实现。

## 目录结构

```text
rcplus-msdk-agent/
├── README.md
├── app/
├── build.gradle.kts
├── gradle/
├── gradle.properties
├── gradlew
├── gradlew.bat
└── settings.gradle.kts
```

## 本地启动与检查

当前仓库已经包含最小 `:app` 模块和 Gradle Wrapper，可以直接执行以下命令尝试构建验证：

```bash
cd rcplus-msdk-agent
./gradlew :app:assembleDebug
```

当前已知的主要环境前提和阻塞如下：

- Android Gradle Plugin `8.5.2` 需要 Java 17 或更高版本。
- 当前这台机器实际运行时落到 Java 11，因此构建会失败。
- 后续仍可能继续卡在 Android SDK / Build Tools 缺失，需要在 Java 版本满足后再验证。

当前真实失败形态为：

```text
Android Gradle plugin requires Java 17 to run. You are currently using Java 11.
```

## 当前不承诺项

- 不承诺当前阶段已经完成可编译 APK、真机安装或遥控器联调闭环。
- 不承诺已接入 DJI MSDK、视频解码、双流推送或状态同步实现。
- 不承诺提供生产级权限、签名、设备兼容性和发布流程配置。
