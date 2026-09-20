# 设备名称规范

所有用户可见的机型、负载名称及数据库名称字段使用 `device-models.json` 中的官方完整名称，例如 `DJI Matrice 4T`、`DJI FlyCart 100`。大小写以厂商名称为准。新增型号先核对厂商官方产品页，再补充目录及兼容别名。

前端调用 `deviceModelName`，后端调用 `DeviceModelNames.full`。任务标题中的型号通过 `formatDeviceText` / `DeviceModelNames.text` 规范化。禁止各页面复制机型简称作为展示文案。

设备序列号、SDK 枚举、协议代码、接口路由、CSS 类名、数据库表名、对象存储键及历史原始证据具有技术语义，不是显示名称；不得直接替换。存储的任务机型、负载名称经类型处理器转回内部 SDK 编码，仅在兼容边界使用。`L1/L2/P1` 等也可能表示任务层级，只有明确的型号字段才自动转换。

变更后运行：

```
node scripts/sync-device-models.mjs --check
node scripts/check-device-names.mjs
```

数据库变更须先生成旧值/新值计划与备份，再按主键和原值条件更新，迁移后独立回读。数据库脚本不得修改原始日志、备份表、证据 JSON 或设备编号。
