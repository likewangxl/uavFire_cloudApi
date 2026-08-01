# Agent 可见光火情闭环自动化验收证据

验收时间：2026-08-01 18:23:46—18:26:00（Asia/Shanghai）

代码提交：`b77f0eeebf3788f9351624ea628e853f25338a7d`

执行前工作区：干净（`git status --short` 无输出）

自动化结论：`AUTOMATED_PASS`

发布结论：`NOT_READY_DEFAULT_OFF`

## 结果

| 门禁 | 结果 |
| --- | --- |
| 生产静态策略 fixture + 当前 checkout | 通过 |
| 离线工具 pytest | 199 通过、1 跳过、0 失败 |
| backend Maven | 514 通过、0 失败、0 error、0 跳过 |
| Agent JVM | 491 通过、0 失败、0 error、0 跳过 |
| detector benchmark JVM | 76 通过、0 失败、0 error、0 跳过 |
| Agent debug APK | 成功，93 个 Gradle task 全部强制执行 |
| frontend Node tests | 303 通过、0 失败 |
| frontend production build | 成功，3009 modules transformed |

Agent 构建显式使用已安装的 Android NDK：
`-PncnnAndroidNdkDir=/Users/likewang/android-tools/android-ndk-r27c`。未传 NDK 的早期强制
构建会 fail-fast，验收手册已把该路径参数列为必需前置条件。

本轮并行启动时 backend 首次因阿里云 Maven 镜像临时未解析到
`com.dji:cloud-sdk:1.0.3` 而停止；原始失败日志被保留。依赖恢复后单独执行同一
`mvn -q test` 命令，514 项全部通过。这是依赖获取失败，不是测试失败。

## 原始证据

原始日志目录（本机、Git 忽略）：
`/Users/likewang/uavfire/.worktrees/agent-fire-detection/artifacts/acceptance/agent-visible-fire/20260801T102346Z-b77f0ee/automated/`

日志 SHA-256：

```text
241aab006c11856ae5e87500f267664b34ff6305359b17923070ab27a42138c4  agent-build-with-local-ndk.log
dff3c5213e25b710d14a9f86b247b70661bce144bdfaf6aab4246ead3dddae4f  backend-test-dependency-resolution-failure.log
890b0d74c01a7325a65544f39040e11dff5ca0a004d407172e75ee00595dfc0f  backend-test.log
e7aece103aa5b0a4b7321ae2e2c869c8d2f53fb5270b75f318e362c003d4b0ae  frontend-build.log
d3b31d72f60506535b88db2b90318c6314207ddef9a37147d07c68f8c4aff8a1  frontend-test.log
25b1e727bcfdfe28cfcf0b033f4e20030aac5d02837d97ac42c3209da8fd41ad  offline-tooling-pytest.log
37d61cfdf20d5de9fd851c69c5a8b2e9954ccff72edbf84bdab5d981ddae2d5d  static-policy.log
```

产物与模型 SHA-256：

```text
775162d845f99afda2e9f49c684b7e5f327ba3363be7c6216e1d69538f219362  rcplus-msdk-agent/app/build/outputs/apk/debug/app-debug.apk
16eb99260352c76b78a23e78eae42da0182e59420ac5c28be093797b9988e671  rcplus-msdk-agent/app/src/main/assets/fire-detection/model-manifest.json
1600cc47b5ca71330e99fc9ae0852409f0309142ea176f4c7a45eae56e87aec7  rcplus-msdk-agent/app/src/main/assets/fire-detection/visible-fire-960.ncnn.param
3bfb2288cff211b3afa2f3c1f9ab6f16cab8bc625f6e3a3e436984acc4a7e84d  rcplus-msdk-agent/app/src/main/assets/fire-detection/visible-fire-960.ncnn.bin
```

## 未完成门禁

RC Plus 当前不可用。visible-960 三引擎实机对比、正式 APK 30 分钟 soak、真实 fire/smoke、
实激光定位、OSD 降级、断网恢复、无桨台架和受控飞行均为
`BLOCKED_PENDING_DEVICE`。现有 NCNN 自动化结果只是 provisional 开发证据，不能替代这些
设备门禁；detector 必须保持默认关闭和 fail-closed。
