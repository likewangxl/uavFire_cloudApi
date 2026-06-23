<claude-mem-context>
# Memory Context

# [uavfire] recent context, 2026-06-23 10:38am GMT+8

Legend: 🎯session 🔴bugfix 🟣feature 🔄refactor ✅change 🔵discovery ⚖️decision
Format: ID TIME TYPE TITLE
Fetch details: get_observations([IDs]) | Search: mem-search skill

Stats: 50 obs (20,721t read) | 572,068t work | 96% savings

### Jun 15, 2026
4020 2:19p 🔵 T11 Export Segment 0 (Session 85985) Completed — 1000 Rows, 1,049,183,187 Bytes
4021 2:24p 🔵 T11 Export Segment 2 (Session 4568) Completed — 1000 Rows, 1,049,191,365 Bytes; Only Part-001 Remains
4022 2:25p 🟣 T11_ORACLE_ALL_TYPES_5G Parallel Export 100% Complete — All 6 Segments Exited Code 0, 5,120 Rows, 5.0 GiB
4023 " ✅ T11 Export Archive Created: T11_ORACLE_ALL_TYPES_5G.tar.gz in dist/linux/data
4024 " 🔵 T11 Export Row Count Validated: Exactly 5,120 Data Rows Across 6 Part Files (5,126 Lines Including Headers)
4025 " 🟣 MySQL Target Table T11_ORACLE_ALL_TYPES_5G Created in cloud_sample Database for Import Phase
4026 2:26p 🔵 T11 tar.gz Archive Only 7.3 MB for 5.0 GiB Uncompressed — ~700:1 Compression Ratio on Repetitive CLOB Data
4027 " 🟣 Import Config dist/linux/conf/import-t11-generated.properties Created for T11 External CSV Import into MySQL
4028 " 🟣 T11 MySQL Import Started — ExternalCsvTaskScanner Registered 6 Shards, LOAD DATA LOCAL INFILE Running for Shard 0
4029 2:27p 🔵 TIMESTAMP_LTZ_COL Import Generates 2 Warnings Per Row — Oracle TIMESTAMP WITH LOCAL TIME ZONE Exports Named TZ Not Offset
4030 " 🔵 Migration Pipeline State: Shard 0 Already VERIFIED, Shard 1 IMPORTING, Shards 2–5 EXPORTED — 1000 Rows in MySQL
4031 2:28p 🔵 T11 Import Throughput Confirmed: ~26 Seconds Per 1000-Row/1GiB Shard — Shards 0–2 VERIFIED, 3000 Rows in MySQL
4032 2:29p 🟣 T11 MySQL Import 100% Complete — All 5,120 Rows Imported in 2m43s, Migration Program Exited Normally
4033 " 🔵 Final Migration Verification: All 6 Shards VERIFIED, 5,120 Rows in MySQL — Perfect Row Count Match
4034 2:30p 🔵 Oracle Source Confirmed: 5120 Rows, Exactly 5 GiB CLOB — End-to-End Migration Fully Validated
4035 " 🔵 Local Disk at 98% Capacity (436 GiB / 466 GiB Used, 11 GiB Free) — Critical Disk Pressure After 5 GiB CSV Export
4036 " 🔵 MySQL T11 Final Verification: 5120 Rows, IDs 1–5120, 5.00 GiB CLOB, All Shards VERIFIED With File Sizes
4037 6:23p ✅ System Promotional Documentation Requested for Video Script
4038 " 🔵 uavFire System Identity and Architecture Discovered from README
4039 " 🔵 Formal System Requirements Extracted from PDF: Smart Cluster UAV Fire Suppression System
4040 " 🔵 Current System Runtime Model and Subsystem Implementation Status Mapped
4041 6:25p 🔵 Existing Promotional Assets Found in docs/promo/ Including Two PPT Files
4042 " 🔵 Sub-Meter Fire Geolocation Design Completed: Multi-Observation Triangulation System
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

Access 572k tokens of past work via get_observations([IDs]) or mem-search skill.
</claude-mem-context>