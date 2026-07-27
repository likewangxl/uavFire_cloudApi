<claude-mem-context>
# Memory Context

# [uavfire] recent context, 2026-07-27 11:37am GMT+8

Legend: 🎯session 🔴bugfix 🟣feature 🔄refactor ✅change 🔵discovery ⚖️decision
Format: ID TIME TYPE TITLE
Fetch details: get_observations([IDs]) | Search: mem-search skill

Stats: 50 obs (18,894t read) | 605,983t work | 97% savings

### Jun 15, 2026
4043 6:26p ✅ System Promotional Documentation Requested for Video Script
4044 " 🟣 智能集群大载重无人机灭火系统宣传文案母稿创建
4045 " 🔵 docs/promo/ Directory Is Gitignored in uavfire Project
### Jun 23, 2026
4415 9:07a 🔵 Duplicate Flight Position Marker Bug on Wayline Task Deploy
4416 9:15a 🔵 OpenAI Codex Desktop 403 Forbidden Error on Reconnect
4417 9:16a 🔵 Codex Manual Fetched and Cached for 403 Troubleshooting
4418 " 🔵 Codex Desktop 403 Root Cause: Enterprise Toggle or Expired Auth Token
4419 " 🔵 Codex Version Mismatch: System CLI 0.140.0 vs App-Bundled 0.142.0-alpha.6
4420 9:17a 🔵 Root Cause Confirmed: chatgpt.com Returns 403 for ALL Requests Via Local Proxy
4421 " 🔵 Dual Proxy Configuration with Port Mismatch on Codex Desktop Host
4422 " 🔵 Codex Desktop App Version and Config State at Time of 403 Error
4423 9:18a 🔵 Two Proxy Ports Route Through Different Cloudflare Regions With Different Block Types
4424 9:46a 🟣 FC100 Map Position Marker Added for Delivery Tab
4425 " 🟣 Tab-Scoped Aircraft Overlay Visibility: Monitor vs Delivery
4426 9:47a 🔵 Playwright evaluate() Context Lacks fetch and XMLHttpRequest
4427 9:48a 🔵 Dev Server at Port 8080 Returns SPA Shell for /api Routes (No Backend Proxy)
4429 " 🔵 FC100 Marker Correctly Absent When No Device Selected or No GPS Coordinates
4431 9:49a 🔴 Added Dedicated Watcher for FC100 Device Props to Drive Marker Updates
4432 9:50a 🔵 Runtime Error in GMap.vue Watcher After HMR: Cannot Read 'state' of Undefined
4433 " 🔵 GMap.vue 'state' TypeError Caused by Vue inject() Failure During HMR, Not FC100 Code
4434 9:51a 🔄 Extracted fc100PositionState into Separate Module to Break Circular HMR Dependency
4435 " ✅ use-planner-overlays.ts Switched Import from use-fc100-delivery to use-fc100-position
4437 9:52a 🟣 FC100 Map Marker Feature Complete: Tests Pass, Production Build Clean
4439 9:53a 🔵 TypeError 'state' Also Occurs in wayline.vue Line 138 ComputedRef — Pre-existing HMR Fragility Across Multiple Components
4440 " 🔵 GMap/Wayline HMR 'state' TypeError Root Cause: useMyStore() Returns Undefined During HMR
4452 9:57a 🔵 Test Assertions Added to Enforce Transparent FC100 Marker Design — CSS Update Required
4453 " ✅ FC100 Map Marker Redesigned: Transparent Container, SVG-Only Rendering
4476 10:39a 🟣 监测规划页面蓝色图标替换为DJI Matrice 4T机体图标
4479 10:43a 🟣 监测规划页面蓝色图标替换为DJI Matrice 4T机体图标
4482 10:44a 🔵 规划页面飞行位置图标架构：双标记系统
4484 " 🔵 监测tab飞行位置标记的完整CSS样式与渲染条件
4485 " 🟣 TDD红灯：为Matrice 4T图标替换添加测试断言，实现待补
4486 10:45a 🟣 实现matrice4tPositionContent()：DJI Matrice 4T机体SVG图标
4487 " 🟣 Matrice 4T SVG图标补丁成功应用至use-planner-overlays.ts
4488 " 🟣 GMap.vue新增Matrice 4T机体图标CSS，移除flight-position-marker的青色圆形气泡样式
4489 10:46a 🟣 TDD绿灯：Matrice 4T图标替换测试通过
4490 10:47a 🟣 Matrice 4T图标替换后生产构建成功
### Jul 27, 2026
5231 10:36a 🔵 Codex CLI Environment Profile on macOS x86_64
5232 10:37a 🔵 Codex Config Has Architecture Mismatch: x86_64 CLI vs arm64 node_repl
5233 " 🔵 Confirmed: node_repl MCP Fails with "bad CPU type" on Intel Mac
5234 " 🔵 Config.toml References ChatGPT.app-Bundled Codex Binary and Local Marketplaces
5235 10:38a 🔴 Fixed Codex Config: Removed arm64 node_repl MCP and Upgraded Deprecated Hook Flag
5236 " 🔵 Codex MCP Server Inventory and Config Fix Verified
5237 " 🔵 Codex CLI Gets 403 Forbidden from chatgpt.com API — Authentication/Region Issue
5238 11:33a ⚖️ Fire Detection Logic Migration: Backend → Agent Execution
5239 11:34a 🔵 uavfire Project: Fire Detection Architecture Mapped
5240 " 🔵 rcplus-msdk-agent Already Has On-Device Thermal Detection Infrastructure
5241 11:35a 🔵 All uavfire Services Currently Running; Agent Thermal Probe Interval Confirmed at 500ms
5242 11:36a 🔵 ai-service YOLO Visible Detection Confirmed 2.3–3.5s Per Frame on CPU (Mac)
5243 " 🔵 ThermalFrameProbe Saves Snapshots Every 2s; OpenCvVideoSource Reopens Every 8 Reads

Access 606k tokens of past work via get_observations([IDs]) or mem-search skill.
</claude-mem-context>