<claude-mem-context>
# Memory Context

# [uavfire] recent context, 2026-06-12 10:06am GMT+8

Legend: 🎯session 🔴bugfix 🟣feature 🔄refactor ✅change 🔵discovery ⚖️decision
Format: ID TIME TYPE TITLE
Fetch details: get_observations([IDs]) | Search: mem-search skill

Stats: 50 obs (18,705t read) | 250,493t work | 93% savings

### Jun 11, 2026
3329 4:24a 🟣 008+009 Concatenation Transcoding Started Using nv12 Format and h264_videotoolbox, Bypassing colorspace Filter
3330 4:25a ⚖️ Aborted nv12-Based Concatenation Transcode Mid-Run and Deleted Partial Output
3331 " 🟣 008+009 Video Concatenation Completed via Two-Pass Transcode+StreamCopy Pipeline
3332 4:26a 🔵 merged_008_009.mp4 Verified: Format Matches 008.mp4, First/Last Frames Decode, Temp Files Cleaned
3333 4:27a 🔵 uavfire Local Service Startup Procedures Documented in MEMORY.md
3334 " 🔵 uavfire Pre-Startup Port Scan: MySQL and MQTT Up, Redis/Backend/Frontend Down
3335 " 🔵 Previous Backend Crash: Port 6789 Already In Use at Last Shutdown
3336 " 🟣 Redis Started Successfully via Daemonize Workaround
3337 4:28a 🟣 Backend and Frontend Launched in Dedicated tmux Sessions
3338 " 🟣 Backend and Frontend Confirmed Running After ~26s Startup Wait
3339 4:29a 🟣 Full Stack Verified Healthy: Backend 302, Frontend 200, MQTT Connected
3340 4:31a 🟣 New Concatenation Task Started: 009+010 in 009's Format (4K HDR HEVC)
3341 4:32a 🔵 009.mp4 and 010.mp4 Share Identical Codec/Color Format, Enabling Potential Stream-Copy Concat
3342 " 🟣 009+010 Stream-Copy Concat Completed in 1.6s with Non-Monotonic DTS Warnings and Dolby Vision Box Dropped
3343 " 🔵 merged_009_010.mp4 Verified: HEVC BT.2020 HLG Format Preserved, Duration 71.3s, First/Last Frames Clean
3344 4:33a 🔵 Full Decode of merged_009_010.mp4 Reveals Persistent Non-Monotonic DTS Errors Around Splice Point
3345 " 🔵 merged_009_010.mp4 Full Decode Completes Successfully Despite Non-Monotonic DTS Warnings
3346 " 🔵 hevc_videotoolbox Encoder Supports Main10/p010le and Has frames_before/frames_after Concat Smoothing Options
3347 4:34a 🟣 Stable 009+010 Re-encode Started via concat Filter + hevc_videotoolbox Main10 to Fix DTS Issues
3348 4:43a 🟣 merged_009_010_stable.mp4 Completed: 9m12s Re-encode Produces Clean HEVC 4K HDR Output at 38.6 Mbps
3349 4:44a 🔴 merged_009_010_stable.mp4 Full Decode Completes with Zero Errors, Confirming DTS Fix
3350 4:47a 🟣 Video Concatenation Task: 010 + 011 with Format Preservation
3351 " 🔵 Video Files Confirmed: 010.mp4 and 011.mp4 in /videos
3352 " ⚖️ Three-Step Concat Strategy: Probe → Stream-Copy → Re-encode Fallback
3353 " 🔵 010 and 011 Are Fundamentally Incompatible: Stream-Copy Concat Impossible
3354 4:48a 🔵 h264_videotoolbox Cannot Set BT.709 Color Metadata — Errors -12902 and -12915
3355 4:50a 🟣 Video Concatenation Completed: merged_010_011.mp4 Created Successfully
3356 " 🔵 ffprobe Does Not Accept Multiple Input Files — Unlike ffmpeg
3357 4:51a 🟣 merged_010_011.mp4 Fully Verified: Color Metadata Clean, First/Last Frames Decode OK
3358 " 🟣 Full End-to-End Decode of merged_010_011.mp4 Passed — Zero Errors
3359 " ✅ Cleanup Complete: Only merged_010_011.mp4 Remains in videos/
3360 10:50a 🔵 Disk Space Exhaustion Overnight During Video Splicing and PPT Creation
3361 " 🔵 Mac Data Volume Confirmed at 100% Capacity — 444 GiB Used of 466 GiB
3362 10:51a 🔵 Root Cause Identified: 34 GB Git Object Store Bloat from Abandoned tmp_pack Files + Large Video Files Committed to Repo
3363 " 🔵 Exactly 249 Orphaned tmp_pack Files Quantified at 30.32 GiB — Primary Disk Exhauster Confirmed
3364 10:52a 🔵 Git count-objects Confirms 30.32 GiB Garbage; Codex Agent Identified as Overnight Executor
3365 10:53a 🔵 Full Home Directory Disk Map: Library at 213 GiB and uavfire at 56 GiB Are Top Two Consumers
3366 10:54a 🔵 Video Files Are Untracked in Git; Three PPT Files Created by Claude Confirmed; Promo Pipeline Identified
3367 " 🔵 tmp_pack Accumulation Window Precisely Timed: 02:43–04:17 AM, 249 Files in 94 Minutes
3368 " 🔵 Complete Overnight Timeline: Git GC Storm Triggered 6 Minutes After 734 MB Video Was Written
3369 10:55a 🔵 Large Loose Git Object Blobs Confirmed as Video Content — 9 Directories Hold ~2.87 GiB of Orphaned Video Blobs
3370 10:56a 🔵 Library Containers Breakdown: WeChat (88 GiB), Telegram (41 GiB), WPS Office (41 GiB) Are Pre-Existing Long-Term Disk Consumers
3371 11:13a ✅ Git Garbage Collection Requested
3372 " 🔵 30 GiB of Git Garbage Files Found in uavfire Repository
3373 11:14a ✅ Git Garbage File Deletion Running — 30 GiB Cleanup In Progress
3374 " ✅ Git Garbage Files Successfully Deleted — 30.32 GiB Freed
3375 " 🔵 Disk State Post-Cleanup: 93% Full, 34 GiB Free on 466 GiB Volume
3376 " 🔵 uavfire Git Repository State After Garbage Cleanup
### Jun 12, 2026
3399 9:57a 🔵 uavfire 项目中被折中或暂缓的技术决策汇总
3413 10:06a 🔵 YOLO 再训练数据集缺失已通过代码库扫描确认

Access 250k tokens of past work via get_observations([IDs]) or mem-search skill.
</claude-mem-context>