<claude-mem-context>
# Memory Context

# [uavfire] recent context, 2026-05-22 12:35am GMT+8

Legend: 🎯session 🔴bugfix 🟣feature 🔄refactor ✅change 🔵discovery ⚖️decision
Format: ID TIME TYPE TITLE
Fetch details: get_observations([IDs]) | Search: mem-search skill

Stats: 50 obs (20,947t read) | 2,096,266t work | 99% savings

### Apr 30, 2026
847 11:38a 🔴 ControlServiceImpl.java 实现固件版本不兼容检查，填补飞行前安全守卫缺口
848 " ✅ no-agora-browser-sdk.test.mjs 全部转绿：6/6 pass，Agora 下线回归验收通过
849 " ✅ 所有技术债清理完成：两组测试全绿，全域扫描零残留
850 11:39a ✅ 全量回归验收通过：前端 10 项测试 + 后端 Maven 构建编译和测试均绿
### May 20, 2026
1966 11:50p 🔵 uavfire Project Architecture Overview
1967 " 🔵 M4T Dual-Stream Runtime Architecture and Verification Status
1968 " 🔵 DJI MSDK v5 Real-Device Thermal Stream Limitation Confirmed
1969 " 🔵 RC Plus Agent Backend Auth Bypass and Runtime Loop Real-Device Validation
1970 " 🔵 DRC Takeoff Implementation and Official takeoff_to_point API Constraints
1972 11:53p 🔵 Backend StreamSplitterService: FFmpeg-Based Composite Stream Splitting
1973 " 🔵 DjiMsdkStreamBinder: bindThermal Always Throws, focusThermal Uses THERMAL_ONLY Mode
1974 " 🔵 AgentRuntimeLoop: 5-Second Heartbeat/Status/Capability/Command Poll Cycle
1975 " 🔵 AI Service: Three-Tier Detection Stack with Optional YOLO and Continuous Runner
1976 " 🔵 Leadership Cockpit Frontend: ZLMRTCClient WebRTC Player with Focus-Switch Commands
1977 " ⚖️ Pilot2 Composite Stream PoC: Design Approved to Validate PIP RTMP Feasibility
1978 " 🔵 takeoff_to_point Error Code Progression and Coordinate Offset Fix
1979 " 🔵 Three Android Agent Bugs Found and Fixed During 2026-04-24 Real-Device Integration
1980 " 🔵 Uncommitted Modifications in Working Tree
### May 21, 2026
2120 4:58p 🔵 UAVFire Project Structure Overview
2121 " 🔵 Uncommitted Changes Span Livestream and Wayline Features
2122 4:59p 🔵 Pilot 2 Composite Stream PoC (Plan B) Declared Dead
2123 " 🔵 Dual Stream Current Blockers: visiblePlayUrl Missing and Thermal Second Channel Unimplemented
2124 " 🔵 MSDK v5 Thermal Stream Hard Limitation Confirmed on Real Device
2125 " 🟣 RC Plus Agent Runtime Loop and Backend Integration Complete
2126 " 🟣 Agora Dynamic Token Generation and Frontend Chinese Localization
2127 5:02p ⚖️ MSDK Migration Plan Phase 1: Full Dataplane Shift from Cloud SDK to Agent
2128 " 🟣 OsdReporter and HmsReporter Added: Agent Impersonates Pilot 2 on MQTT
2129 " 🟣 AI Service Full Pipeline: ContinuousTaskRunner with YOLO, Fusion, Snapshot, and FireEvent Reporting
2130 " 🔵 Backend DualStreamServiceImpl: Auto URL Generation and Single-Stream Review Logic
2131 " 🟣 Leadership Cockpit: Full Dual-Stream WebRTC Player with AI Risk Panel and Fire Notification
2132 " 🔵 Current IP Baseline Changed: All Services Now on 192.168.2.34
2133 " 🟣 Wayline Agent Contract and KMZ Format Verified Against Real M4T Hardware
2134 " 🔵 Cockpit Dual Livestream Strategy: Cloud API Plan C' as Primary Override for Agent Stream
2135 " 🔵 Project CLAUDE.md Coding Standards and Backend Start Command
2136 " ⚖️ Dual-Stream Strategy Matrix: Plans A/B/C/D Evaluated, B Dead, C' Discovered
2137 5:17p ⚖️ TDD-First Migration Plan for UAV Fire Project Cockpit/Backend Changes
2138 5:18p 🔵 UAV Fire Project 3-Tier Test Architecture Map
2139 " 🔵 Leadership Cockpit Livestream Behavioral Contracts (Frontend Tests)
2140 " 🔵 DJI MSDK Stream Binder Thermal Focus Behavioral Contracts
2141 " 🔵 RealMsdkStreamProvider Stream Start/Focus/Stop Contracts
2142 5:19p 🔵 DJI KMZ/WPML Generation Compliance Contracts for M30T and M4T
2143 " 🔵 Android Agent Build Config: Runtime Parameters via Gradle Properties
2144 " 🟣 RED Tests Added: Cockpit Fallback Removal, AI Stream URL, MQTT Payload, M4T Thermal Error Rename
2145 " 🔴 apply_patch Fails on PlannedWaylineServiceTest.java Due to readZipEntry Signature Mismatch
2146 5:20p 🟣 All RED Tests Written and Confirmed Failing — TDD RED Phase Complete
2147 5:21p 🔵 AI Detection RTSP URL Format Uses Extended Video ID — Test Expected Wrong Format
2148 " 🔵 OsdReporter.kt Fails to Compile — DJI MSDK Attitude Class Unresolved
2149 5:22p 🔵 OsdReporter.kt Uses Wrong Package for Attitude Class — Fix is Import Change Only
2150 " 🔵 WaylineMqttPublisher Does Not Have Static buildCloudOsdEnvelope/buildCloudEventEnvelope Methods
2151 " 🔵 Cockpit Pilot Livestream Fallback — Full Scope of Code to Remove Identified

Access 2096k tokens of past work via get_observations([IDs]) or mem-search skill.
</claude-mem-context>