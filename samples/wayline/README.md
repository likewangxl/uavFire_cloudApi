# Wayline 测试样本

## `m30t_min_v1.kmz`

最小可执飞的 WPML 航线文件,用于在 RC Plus 2 + MSDK demo 上验证 **M4T 实机能否接受
机型为 M30T 的 KMZ**(droneEnumValue=67 / droneSubEnumValue=1 / payloadEnumValue=53)。

### 参数

| 项 | 值 | 来源 |
|---|---|---|
| droneEnumValue | 67 (M30/M30T) | DJI WPML v1.11.3 `common-element.md` |
| droneSubEnumValue | 1 (M30T 三光) | 同上 |
| payloadEnumValue | 53 (M30T 三光相机) | 同上 |
| heightMode | relativeToStartPoint(模板)/ WGS84(执行) | DJI WPML `template-kml.md` / `waylines-wpml.md` |
| 高度 | 30m | 占位 |
| 速度 | 5m/s | 占位 |
| finishAction | goHome | 业务确认 |
| exitOnRCLost | goContinue | 业务确认 |
| executeRCLostAction | goBack | 占位(exitOnRCLost=goContinue 时不生效,留以备改) |
| 航点 | 3 点三角(经纬度 113.0/22.0 附近) | 占位 |
| imageFormat | zoom,ir | M30T 默认 |

### 改坐标方法

直接编辑 KMZ 里的两个文件:

```
wpmz/template.kml   ← 改 3 处 <coordinates> + missionConfig
wpmz/waylines.wpml  ← 改 3 处 <coordinates>
```

或用 DJI Pilot 2 导入这份 KMZ 后用 GUI 编辑保存。

### 解包/重打包

```bash
# 解
mkdir m30t_edit && cd m30t_edit && unzip ../m30t_min_v1.kmz

# 改 wpmz/template.kml 和 wpmz/waylines.wpml 的 <coordinates>

# 重打(注意保持 wpmz/ 目录结构,不要把内容直接放根)
zip -r ../m30t_min_v2.kmz wpmz/
```

### M4T 兼容性测试步骤

1. 在 RC Plus 2 上装 DJI 官方 MSDK demo:`Mobile-SDK-Android-V5/SampleCode-V5/android-sdk-v5-sample`
2. 把这份 KMZ adb push 到 RC Plus 2 上
3. 启动 demo → `WayPointV3Fragment`
4. 选这份 KMZ → 上传 → 起飞 → 看是否被 M4T 拒绝
5. 如果被拒,记录拒绝原因(MSDK callback 的 errorCode + errorMsg),报回来调整 enum

### 改完后自检

```bash
python3 samples/wayline/validate_kmz.py samples/wayline/<your-edited>.kmz
```

校验器(`validate_kmz.py`)做的事:解压、按 WPML spec 检查必填 `wpml:*`
元素、检查枚举值合法、检查 `<coordinates>` 至少有逗号。**这是下限校验,
不等于 M4T 一定接受**——只能用来抓掉字段、namespace 错、coordinates
残缺之类的低级问题。

### 失败信号对照

| 现象 | 可能原因 |
|---|---|
| `pushKMZFileToAircraft` 直接报 INVALID_FILE | XML 解析失败,KMZ 结构错 |
| `pushKMZFileToAircraft` 报 NOT_SUPPORTED_AIRCRAFT | droneEnumValue=67 不被 M4T 接受 |
| 上传成功但 `WaypointMissionExecuteState` 进 NOT_SUPPORTED | 机型支持 WPML 但不支持这套字段 |
| `startMission` 报 BATTERY_LOW / RC_NOT_CONNECTED 等 | 跟 KMZ 无关,环境问题 |
