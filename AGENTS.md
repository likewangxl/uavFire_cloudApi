<claude-mem-context>
# Memory Context

# [uavfire] recent context, 2026-06-01 6:19pm GMT+8

Legend: 🎯session 🔴bugfix 🟣feature 🔄refactor ✅change 🔵discovery ⚖️decision
Format: ID TIME TYPE TITLE
Fetch details: get_observations([IDs]) | Search: mem-search skill

Stats: 50 obs (23,771t read) | 412,559t work | 94% savings

### May 30, 2026
2779 2:01a 🔵 UAVFire Module Dependency Stack Identified from pom.xml
2780 " 🔵 FireEventHistory Data Model Schema Documented
2781 " 🔵 DeliveryController Workflow Test Suite Covers Three Core Scenarios
2782 2:02a 🔵 DeliveryController Uses Three Constructor Overloads for Progressive Dependency Injection
2783 " 🔵 FireEventServiceImpl OSD Position Fill Logic and Fallback Chain Documented
2784 " 🟣 FireGeoLocationServiceTest Added for Ray-DEM Fire Geo-Location
2785 " 🟣 Geo Quality Fields Added to FireEvent Persistence Test and Delivery Rejection Gate Test
2786 " 🔵 Build Fails: FireGeoSnapshotDTO and RayDemFireGeoLocationService Production Classes Do Not Yet Exist
2787 2:03a 🟣 Fire Geo-Location Subsystem Production Classes Created
2788 " 🟣 RayDemFireGeoLocationService Implemented with Iterative Ray-DEM Intersection Algorithm
2789 2:04a 🟣 Geo-Location Metadata Fields Propagated Across Fire Event Data Model
2790 " 🟣 Geo-Location Fields Extended to FireEventDTO and FireEventHistoryDTO Response Layer
2791 " 🟣 FireGeoLocationService Integrated into FireEventServiceImpl Create Pipeline
2792 " 🟣 FireEventServiceImpl Geo-Resolution Pipeline Implementation Completed
2793 2:05a 🟣 DualStream Pipeline Extended to Pass Geo-Snapshot and Quality Fields to Fire Event Creation
2794 " 🟣 Geo-Quality Gate Activated in DeliveryController and API Field Name Fixed
2795 " 🟣 Python AI Service Extended to Forward geo_snapshot in Fire Event Reports
2796 2:06a 🟣 RC+ MSDK Android Agent Extended with GeoSnapshot Kotlin Data Classes
2797 " 🟣 Database Migration and Init SQL Updated for Geo-Quality Columns
2798 " 🟣 All 39 Tests Pass After Full Geo-Location Feature Implementation
2799 2:07a 🔵 RC+ Agent and AI Service Test Helpers Do Not Yet Populate geoSnapshot in Event Requests
2800 " 🟣 GeoSnapshot Wired Through AgentBackendClient and Python Reporter Test Added
2801 " 🔴 Jackson @JsonAlias Added to Accept Both snake_case and camelCase geo_snapshot on Inbound DTOs
2802 2:08a 🟣 All Three Build Systems Green After Complete Geo-Location Feature Implementation
2803 " 🔵 Full Branch Changeset Scope Revealed by git status
2804 " 🟣 Frontend TypeScript FireEventDTO and FireEventHistoryDTO Types Updated with Geo Fields
2805 2:09a 🟣 Geo Quality Column Added to FireEventList Vue Table with Chinese Labels and Error Radius
2806 " 🔵 Frontend Stack Identified: Vue 3 + Ant Design Vue 2 + Vite 2 with Amap and MQTT
2807 2:10a 🟣 Frontend Vite Build Succeeds — All Four Build Systems Green
2808 3:27p 🔵 AI-Service MSDK Visible Detector Performance Bottlenecks Identified
2809 3:28p ⚖️ TDD-First Immediate Visible Snapshot Mechanism for MSDK Capture Optimization
2811 " 🔵 rcplus-msdk-agent Stream Package: Collaborator Uncommitted Changes Already Present
2810 " 🔵 Full Codebase Audit: AI-Service Visible Detector Architecture and Pre-existing Uncommitted Changes
2812 3:29p 🟣 TDD Red Phase: Tests Written for imgsz Configurability and Process-Level Detector Caching
2813 " 🔵 ThermalFrameProbe.kt Full Architecture: Throttle Gate Location and Frame Flow
2814 " 🟣 TDD RED: Failing Source Test for One-Shot Immediate Visible Snapshot Bypass
2816 " 🔵 TDD RED Confirmed: New Test Fails at Line 32 as Expected
2817 " 🔵 DjiMsdkStreamBinder Poll Budget vs Throttle Gap: 1.2s Window vs 2s Throttle
2815 " 🔵 Test Runner Setup: PYTHONPATH Required; RED Phase Confirmed for New Tests
2818 " 🟣 TDD GREEN: Immediate Visible Snapshot Bypass Implemented in ThermalFrameProbe and DjiMsdkStreamBinder
2819 3:30p 🟣 GREEN Phase: imgsz Configurability, Process-Level Detector Cache, and Timing Logs Implemented
2820 " 🔵 Full Git Diff Reveals Extent of Collaborator Changes in DjiMsdkStreamBinder.kt
2821 " 🔴 Pre-existing Test Isolation Bug: ai-service/.env Pollutes Settings Tests
2822 " 🟣 TDD GREEN Phase Complete: 24/24 Tests Pass Including All New imgsz and Cache Tests
2823 " 🟣 Immediate Visible Snapshot Optimization: Final State Verified
2824 " 🟣 Route Timing Log Verified by Integration Test: caplog Assertion Added to test_routes.py
2825 3:31p 🔵 Two Pre-existing Test Failures: Continuous Runner Mode Causes RTSP Resolution in Integration Tests
2826 " 🟣 Full Suite Green: 132/132 Tests Pass with AI_SERVICE_USE_CONTINUOUS_RUNNER=false
2827 " 🔄 Thread-Safe Detector Cache: Lock Added to _get_cached_visible_detector
2828 3:32p ⚖️ Suppress Redundant Focus-Visible Commands for MSDK Thermal Events in Backend

Access 413k tokens of past work via get_observations([IDs]) or mem-search skill.
</claude-mem-context>