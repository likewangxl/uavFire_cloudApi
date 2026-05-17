<claude-mem-context>
# Memory Context

# [uavfire] recent context, 2026-05-17 10:24pm GMT+8

Legend: 🎯session 🔴bugfix 🟣feature 🔄refactor ✅change 🔵discovery ⚖️decision
Format: ID TIME TYPE TITLE
Fetch details: get_observations([IDs]) | Search: mem-search skill

Stats: 50 obs (14,311t read) | 326,111t work | 96% savings

### Apr 29, 2026
801 5:50p 🔵 RC_PLUS_LOCAL 热成像流状态 degraded，与 MSDK v5 限制吻合
802 5:51p 🔵 RTMP 端口 1935 由 SSH 隧道转发，非本地媒体服务器
803 " ✅ ai-service 重启：杀旧 screen 会话并重新启动
804 5:52p 🔵 RTMP 流确认为 1280×720 H.264，missing picture 警告为良性
805 " 🔵 focus-visible 指令端到端延迟约 2.6 秒，thermal_state 随后从 degraded 转为 idle
806 " 🔵 端到端火灾检测管线在真实 RTMP 流上完整运行验证
807 6:27p 🔵 View Switch Not Triggered Due to No Qualifying Risk-Level Scenes Detected
### Apr 30, 2026
808 10:19a 🟣 AI Risk Identification Records Relocated to Live Page Right Panel
809 10:20a 🔵 AI Risk Panel Already Exists in leadership-cockpit.vue — Positioned Before Right-Side Sections
810 " 🔵 Untracked leadership-cockpit-live-layout.mjs Module Exists Alongside Main Cockpit Vue
811 10:21a 🔵 AI Risk Panel Is Nested Inside Center Map/Live Article — Not a Column-Level Element
812 " 🔵 map-panel CSS Grid Has Three Rows — Third Row Is the ai-risk-panel
813 10:22a ⚖️ Two Layout Options Proposed for AI Risk Panel Right-Column Placement
814 10:25a ⚖️ User Selected Merged Alert-Card Approach with Right-Column Reorder
815 10:27a 🔵 Existing Test Constraints for AI Risk Panel — Text-Based Source Assertions Will Survive DOM Relocation
816 " ✅ Test File Updated with TDD RED Assertions for Merged AI Panel and Right-Column Reorder
817 10:28a 🔵 TDD RED Confirmed — Two Tests Fail with Precise Failure Messages
818 " 🟣 AI Risk Panel Relocated from Center Map Article into Right-Column Key Alerts Card
819 " ✅ CSS Cleanup After AI Risk Panel Relocation — Renamed Classes and Removed Standalone Panel Styles
820 10:29a 🔴 All 4 Tests Pass GREEN — AI Risk Panel Relocation Complete
821 " 🔵 Final Right-Column Template Structure Verified in leadership-cockpit.vue
822 10:30a 🔵 Vite Build Succeeds (exit 0) — leadership-cockpit.vue Compiles Clean with No Errors
823 " 🔵 Dev Server Started on Port 8082 for Visual Inspection
824 10:31a 🔴 Git Diff Confirms Cumulative Scope — Hero Subtitle Copy Also Changed
825 " 🟣 AI Risk Panel Relocation to Right Column Complete — All Steps Done
826 11:29a 🔵 uavfire 项目待办事项全量扫描结果
827 11:31a 🔵 ai-service 实际已完成 PoC 真实视频输入与启发式识别，口径需更新
828 " 🔵 驾驶舱 WebRTC 链路现状：播放器已接入，E2E 真机推流验证仍缺失
829 " 🟣 新建 AI 风险事件面板实施计划（2026-04-30）
830 11:33a ⚖️ 确定本轮工作范围：下线 Agora 遗留入口 + 清理源码 TODO 标记
831 " 🔵 Agora 遗留代码和源码 TODO 精确定位完成
832 11:34a 🔵 Agora 全量分布盘点：cloud-sdk 不可删，sample 层可安全清理
833 " 🔵 use-connect-mqtt @TODO 分析：UranusMqtt 已处理异常，上层监听体为空体
834 " 🔵 ControlServiceImpl firmware TODO 和 WebSocket TODO 均为低风险遗留注释
835 11:35a ✅ 添加 Agora 下线回归测试（TDD RED 步骤）
836 " ✅ 新建 TODO 策略测试文件（TDD RED 步骤）
837 " 🔵 回归测试 RED 状态确认：精确定位 5 个 Agora 残留文件和 5 个 TODO 文件
838 11:36a ✅ WorkspaceLivestreamPanel.vue 移除 Agora 引用，用中性措辞替换弃用提示
839 " ✅ 前端 Agora 引用全部清除（GREEN 阶段）
840 " 🔴 修正 ELiveTypeValue 枚举值避免 Agora 删除引起的数值位移
841 " ✅ 后端 LiveStreamServiceImpl 和 LiveStreamProperty 移除 Agora 分支代码
842 " ✅ 后端 application.yml Agora 凭证和 LiveUrlTypeEnum.AGORA 枚举值完全移除
843 11:37a ✅ 删除 LiveUrlAgoraDTO.java 孤立文件，后端 Agora 代码清理完毕
844 " 🔴 use-connect-mqtt.ts 实现三处 @TODO：expire_time 校验、onStatus 错误处理、认证失败处理
845 " 🔄 ConnectWebSocket 改用 EventEmitter 替代单一回调 messageHandler
846 11:38a ✅ 清理前端 TODO 注释：device.ts 和 payload.ts 改写为正式说明
847 " 🔴 ControlServiceImpl.java 实现固件版本不兼容检查，填补飞行前安全守卫缺口
848 " ✅ no-agora-browser-sdk.test.mjs 全部转绿：6/6 pass，Agora 下线回归验收通过
849 " ✅ 所有技术债清理完成：两组测试全绿，全域扫描零残留
850 11:39a ✅ 全量回归验收通过：前端 10 项测试 + 后端 Maven 构建编译和测试均绿

Access 326k tokens of past work via get_observations([IDs]) or mem-search skill.
</claude-mem-context>