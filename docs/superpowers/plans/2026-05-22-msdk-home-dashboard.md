# MSDK Home Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the plain RC Plus agent home screen with a dark cockpit-style dashboard matching the provided reference while preserving existing buttons and command handlers.

**Architecture:** Implement the first version with native Android XML resources only: `activity_main.xml` for layout, drawable XML for cards/buttons/backgrounds, and string resources for Chinese copy. Keep existing `MainActivity` IDs (`statusText`, `refreshButton`, `startButton`, `probeWaypointButton`, `openSampleToolsButton`) so Kotlin logic does not change.

**Tech Stack:** Android XML layouts, AppCompat Activity, existing Kotlin command handlers, shape/vector drawable resources.

---

### Task 1: Add dashboard drawable resources

**Files:**
- Create: `rcplus-msdk-agent/app/src/main/res/drawable/home_dashboard_background.xml`
- Create: `rcplus-msdk-agent/app/src/main/res/drawable/home_card_panel.xml`
- Create: `rcplus-msdk-agent/app/src/main/res/drawable/home_feature_button.xml`
- Create: `rcplus-msdk-agent/app/src/main/res/drawable/home_primary_button.xml`
- Create: `rcplus-msdk-agent/app/src/main/res/drawable/home_hero_panel.xml`

- [ ] Add dark blue gradient background and translucent blue card/button shapes.

### Task 2: Replace home layout

**Files:**
- Modify: `rcplus-msdk-agent/app/src/main/res/layout/activity_main.xml`

- [ ] Replace the simple vertical ScrollView content with a landscape dashboard.
- [ ] Preserve IDs used by `MainActivity`: `statusText`, `refreshButton`, `startButton`, `probeWaypointButton`, `openSampleToolsButton`.

### Task 3: Update Chinese dashboard strings

**Files:**
- Modify: `rcplus-msdk-agent/app/src/main/res/values/strings.xml`

- [ ] Update button labels to match dashboard wording.

### Task 4: Verify and install

- [ ] Run: `./gradlew :app:assembleDebug --console=plain`
- [ ] Install: `adb install -r -d app/build/outputs/apk/debug/app-debug.apk`
- [ ] Relaunch: `adb shell am force-stop com.yinxin.uavfir && adb shell monkey -p com.yinxin.uavfir -c android.intent.category.LAUNCHER 1`
