# Design QA — 统一指挥台

**Result: passed（限本次已实施、已检查的界面范围）**

源：用户给出的三栏火情参考及本项目原型 `http://127.0.0.1:5188`。目标：正式 Vue/Java 项目 `http://127.0.0.1:8080/fire-events`。测试时间：2026-09-06。

## 对照方法和状态

原型与正式事件页在1440×960、可见光状态采集后同时打开比较。源图与实现都展示相同农田现场素材；事件数、编号、复核状态使用各自数据，不人为伪造后台业务状态。原型6条示例与正式3条QA记录的差异明确保留。截图工具的原型输出为1434×956、正式输出为1440×960，存在小于1%的采样差异；不作逐像素相等结论。1792宽屏截图右缘受工具画布裁剪，以DOM边界补充核验。另查1280×900、1792×900和390×844；参考原图2的右侧垂直处置时间轴结构，保留本项目既有蓝黑配色。

## 审查结果

- 层级：统一顶栏与功能导航；窄事件列表、主要现场图、右侧处置信息形成三栏；不遗漏原航线规划与专业作业入口。
- 图像：真实地址加载、可见光/红外切换、contain保全图；无图时清晰提示。图片不是直播占位图。
- 排版：长ID受控，审核人/说明换行；标题/按钮不互压；时间轴有独立滚动。缩略图和状态色保持对应选择。
- 交互：检索筛选、事件选择、复核提交、关联处置、时间轴收起/展开实测；航线两点规划并实际保存；多视频窗口在隔离API上操作验证。
- 页面一致性：修正新增执行页的亮色表格，保持专业旧页面和设备管理原有结构。保留旧管理页面英文和亮色主题为P3后续项。

## 修复与复测记录

P2：事件三栏底部溢出→修正可用高度；航线库高度截断→修正父容器计算；窄屏按钮拥挤→换行及列表横滑；长ID/账户占宽→溢出处理；重复错误toast→页面错误面板；执行表格主题冲突→局部暗色样式。修复后重新截图并检查；详见[视觉审查](docs/command-center/visual-audit.md)。

对比图：[原型](docs/command-center/evidence/12-reference-events-1440.jpg)、[正式版](docs/command-center/evidence/13-implementation-events-1440.jpg)。窄屏：[现场](docs/command-center/evidence/09-events-mobile.jpg)、[时间轴](docs/command-center/evidence/11-events-mobile-timeline.jpg)。执行页：[修复后](docs/command-center/evidence/19-execution-history-refined.jpg)。

## 通过范围

未发现已审核心页面剩余P0/P1/P2视觉阻断；数据来源和状态含义与正式接口一致。此结论不等于全部旧页面已全量重绘，不等于通过读屏器完整无障碍评测，也不等于真实飞行、媒体并发或生产发布验收。相关未执行项和复跑方式见[验证报告](docs/command-center/verification-report.md)。

## Navigation consolidation update

The five-section navigation, unified wayline library, retired legacy cockpit route and migrated device controls supersede the original navigation above. See [current report](docs/command-center/consolidation-report.md).
