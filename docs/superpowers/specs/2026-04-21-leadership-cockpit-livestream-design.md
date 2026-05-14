# Leadership Cockpit Livestream Design

## Goal

在 `leadership-cockpit` 页面中，为当前“森林火场综合态势图”卡片增加显眼的双 tab 切换能力：

- 默认显示现有态势图
- 切换到“直播画面”时显示 Agora/WebRTC 直播
- 保留当前直播 HUD
- 切换到直播后，卡片底部 KPI 同步切换成直播态信息，而不是沿用态势图指标

## Constraints

- 不替换掉原有态势图实现，仍保留现有静态态势图内容
- 不继续扩散旧的 `livestream-agora.vue` 页面级实现
- 复用已经在线上工作区中使用的、带 HUD 的直播面板能力
- 驾驶舱中不应再出现独立页面风格的额外标题条或突兀的白底容器

## Chosen Approach

复用 `frontend/src/components/WorkspaceLivestreamPanel.vue` 作为共享直播核心，并给它增加“驾驶舱模式”适配能力：

- 支持隐藏内部 header
- 支持驾驶舱深色视觉变体
- 向父组件暴露直播状态摘要，供 `leadership-cockpit` 渲染直播态 KPI

`leadership-cockpit.vue` 继续拥有卡片外层、tab、标题说明和 KPI 区，不把这些职责塞进直播组件内部。

## Data Flow

`WorkspaceLivestreamPanel` 内部继续负责：

- Agora 配置获取
- 直播容量获取
- 直播启动、停止、清晰度调整、镜头切换
- HUD 读取 store 中的 OSD 数据

它额外向父层发出一个简洁的状态摘要，至少包括：

- 是否已加载配置
- 是否检测到远端视频正在播放
- 是否为手动启动的直播态
- 当前选中的无人机
- 当前选中的相机
- 当前清晰度标签
- HUD 当前模式文本
- 当前状态消息

`leadership-cockpit.vue` 根据这个摘要切换直播态 KPI 文案。

## UI Behavior

- tab 使用更显眼的方案 B 风格
- 默认选中 `态势图`
- 选中 `直播画面` 后：
  - 主区域显示带 HUD 的直播画面
  - 底部 KPI 改成直播态 4 项信息
- 再切回 `态势图` 时：
  - 恢复态势图主区域
  - 恢复原态势 KPI

## Testing

先补最小回归测试，验证：

- `leadership-cockpit.vue` 存在 tab 切换状态和 `WorkspaceLivestreamPanel` 引用
- `leadership-cockpit.vue` 为态势 KPI / 直播 KPI 分别提供数据源
- `WorkspaceLivestreamPanel.vue` 支持 headerless / cockpit 变体和状态事件输出

然后运行对应 `node --test` 脚本和前端构建。
