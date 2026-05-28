<claude-mem-context>
# Memory Context

# [uavfire] recent context, 2026-05-29 2:42am GMT+8

Legend: 🎯session 🔴bugfix 🟣feature 🔄refactor ✅change 🔵discovery ⚖️decision
Format: ID TIME TYPE TITLE
Fetch details: get_observations([IDs]) | Search: mem-search skill

Stats: 50 obs (17,383t read) | 494,374t work | 96% savings

### May 29, 2026
2548 1:19a 🔵 ZLM日志揭示外网HTTP失败根因：vicp.fun仅转发TCP连接不转发HTTP数据，导致"end of file"立即关闭
2549 1:26a 🔵 Port Mapping 3-Rule Limit Constrains Dual-Mapping for Port 10000
2550 " ✅ ZLMediaKit RTC preferred_tcp=1 Set on Remote Deploy Server
2551 1:27a ✅ ZLMediaKit preferred_tcp=1 Deployed and Container Restarted via SSH
2552 " 🔵 SSH Session 48198 Hung Awaiting Remote Command Completion
2553 1:28a 🔵 write_stdin Fails When exec_command Not Run with tty=true
2554 " 🔴 Stalled SSH/expect Processes Killed and Retried with Heredoc expect Syntax
2555 " 🔴 ZLMediaKit preferred_tcp Patch Failed Due to PermissionError on Backup File
2556 1:29a 🔴 ZLMediaKit preferred_tcp=1 Successfully Applied via sudo sed
2557 1:38a ✅ ZLMediaKit RTC Port Changed from 10000 to 19586, Container Recreated with New Port Mappings
2558 " ✅ ZLMediaKit Successfully Redeployed on Port 19586 TCP-Only, Port 10000 Eliminated
2559 " 🔵 ZLMediaKit Started Successfully but HTTP Probe Returns 502 Bad Gateway via Reverse Proxy
2560 1:39a ✅ ZLMediaKit HTTP Confirmed Healthy — Full Redeployment on Port 19586 Complete
2561 1:44a 🔵 Remote Service Status Check on uavfire Production Server
2562 " 🔵 uavfire Production Server Service Status: Backend and Frontend NOT Running
2563 1:52a 🔵 Local Dev Environment: Backend Running on 6789, Frontend Running on 8081 (Not 5173)
2564 1:53a ✅ Media Server Endpoints Migrated from LAN IP (192.168.0.30) to Public frp Tunnel (1916dn17xs12.vicp.fun)
2565 " 🔵 ZLMediaKit Port 55932 Returns 花生壳 frp Portal Page, Not ZLMediaKit API
2566 " ✅ Backend and Frontend Successfully Restarted with New Config via tmux Sessions
2567 " 🔵 Remote Server AI Service Runs Python 3.10.12 Without torch/ultralytics Explicitly Confirmed
2568 1:54a ✅ Remote Server AI Service ML Dependencies Installing: torch 2.2.2, ultralytics 8.4.56, numpy 1.26.4
2569 1:56a 🔵 Local YOLOv6 Fire Detection Model Present in uavfire Project
2570 " 🔵 Remote Server Actively Installing PyTorch and Ultralytics via pip
2571 " ✅ Remote pip install Process Killed on Ubuntu Server
2572 " ✅ Local Trained Model Being SCP'd to Remote Server
2573 " 🔵 SCP Transfer of Model File Stalling at 1% Completion
2574 1:57a ✅ Model File SCP Transfer Completed Successfully Despite Initial Stall
2575 1:58a ✅ Remote uavfire-deploy Environment: Model Placed and CPU-Only PyTorch Installing
2576 " 🟣 Remote uavfire-deploy ML Stack Fully Installed and YOLO Model Load Verified
2577 2:00a 🔵 uavfire Local Service Architecture: Three Components Running on Mac
2578 2:05a 🔵 Remote AI Service Running But YOLO Model Path Env Var Is Empty
2579 2:06a 🔵 AI Service Detector Mode Controlled by YOLO Model Path Env Var
2580 " 🔴 Remote .env Updated with YOLO Model Path But Service Restart Failed (sudo requires terminal)
2581 " 🔴 Remote uavfire-ai.service Restarted with YOLO Model Path Config Active
2582 " 🔵 AI Service Visible Detector Selection Logic in task_registry.py
2583 2:07a 🔵 YoloVisibleDetector Uses imgsz=1920 and Lazy Model Loading
2584 " ✅ Local uavfire-ai-service tmux Session Killed on Mac
2585 " 🟣 Bidirectional SSH Tunnel Established Between Mac and Remote AI Server
2586 " 🔵 SSH Tunnel Expect Script Failing to Auto-Submit Password
2587 2:08a 🔴 SSH Tunnel Fixed by Writing Expect Script to File Instead of Inline
2588 " 🟣 End-to-End Verification: YOLO Model Loads Correctly and Reverse Tunnel Reaches Java Backend
2589 2:09a 🔵 Full Stack Health Confirmed from Mac: AI Tunnel and Java Backend Both Responding
2590 2:12a 🔵 Remote Server GPU Check Timed Out — nvidia-smi Command Hung
2591 2:22a 🔵 YOLOv6 Fire Detection Model Present Locally in uavfire Project
2592 2:27a 🔵 uavfire Project Has Extensive Uncommitted Changes Including New FC100 Bypass Stream DTOs and Cockpit Plans
2593 " 🔵 uavfire True Hardware E2E Validated: M4T RTMP Stream Live, AI Detection Running at LOW Risk
2594 " ✅ MSDK Agent Data Plane Consolidation Round 1: Four Code Fixes and Tests All Passing
2595 2:28a ✅ RUNBOOK.md Updated: ai-service Now Runs on Public VM with SSH Tunnel, Not Locally
2596 " ✅ FC100 Public VM Deployment Fully Documented: ZLM on VM, SSH Tunnel for ai-service, Config Switched to Public Endpoints
2597 " ✅ Network Baseline IP Migrated from 192.168.0.30 to 172.20.10.7 Across All Project Docs

Access 494k tokens of past work via get_observations([IDs]) or mem-search skill.
</claude-mem-context>