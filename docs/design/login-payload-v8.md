# 登录背景 v8

日期：2026-09-10

- 新增第二架 Matrice 4D 系列透明渲染素材到用户右侧椭圆位置，保留上方原有素材。
- 移除背景中右下方旧巡检机。
- FC200 红色软运输包替换为红色封闭式灭火载荷概念，保留长吊索与已确认的机体姿态。
- 载荷以 100L 级为外观设计目标；厂家参考实物是 TY02-UAV-50.0L，仅用于外形参考，不代表已验证的 100L 商品、实测容量或 FC200 适配认证。

## 文件

- 背景：frontend/src/assets/login/fire-response-panorama-payload-v8.png
- 透明飞机：frontend/src/assets/login/matrice-4d-rear-transparent-v1.png（复用原文件）
- 内置 imagegen 提示词：frontend/src/assets/login/fire-response-panorama-payload-v8.prompt.txt
- 来源与版本：frontend/src/assets/login/sources.json
- 旧 v7 背景继续保留；修改前页面保存于 .codex-temp/login-redesign-20260910/LoginPage.before-payload-v8.vue（仓库上一级）。

## 验证

- 前端生产构建成功，仅已有大分包警告。
- git diff --check 通过；本次修改前后 LoginPage.vue 的 script 块完全一致。
- 浏览器已加载 v8 背景哈希 3b1d0be3，与本地文件对应；两份透明飞机均加载成功。
- 1920×945 视口：新增飞机中心 (1221.11,431.44)，用户椭圆中心约 (1221,431)；宽度 65.27px，登录面板从 x=1436 开始，无重叠。
- 新版保留在 http://127.0.0.1:8080/project；检查结束后已恢复浏览器原视口。
