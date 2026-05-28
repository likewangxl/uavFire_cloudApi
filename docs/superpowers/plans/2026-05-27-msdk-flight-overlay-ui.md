# MSDK Flight Overlay UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the MSDK flight view camera/lens debug label and show richer bottom flight telemetry in camera views.

**Architecture:** Keep the existing DJI UXSDK default layout and widgets. Disable FPV source labels through XML attributes, then adjust `DefaultLayoutActivity` visibility logic so `HorizontalSituationIndicatorWidget` shows its speed, attitude, and gimbal-pitch telemetry in camera views without placing a full-screen flight-display overlay above camera controls.

**Tech Stack:** Android, Kotlin/Java, DJI MSDK v5 UXSDK, Gradle.

---

### Task 1: Guard the Flight UI Contract

**Files:**
- Create: `scripts/msdk-flight-ui.test.sh`
- Modify: `Mobile-SDK-Android-V5/SampleCode-V5/android-sdk-v5-uxsdk/src/main/res/layout/uxsdk_activity_default_layout.xml`
- Modify: `Mobile-SDK-Android-V5/SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/sample/showcase/defaultlayout/DefaultLayoutActivity.java`

- [ ] **Step 1: Write the failing shell test**

The test checks the exact behavior requested for the flight screen:

```bash
#!/usr/bin/env bash
set -euo pipefail

LAYOUT="Mobile-SDK-Android-V5/SampleCode-V5/android-sdk-v5-uxsdk/src/main/res/layout/uxsdk_activity_default_layout.xml"
ACTIVITY="Mobile-SDK-Android-V5/SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/sample/showcase/defaultlayout/DefaultLayoutActivity.java"

grep -q 'android:id="@+id/widget_primary_fpv"' "$LAYOUT"
grep -q 'app:uxsdk_sourceCameraNameVisibility="false"' "$LAYOUT"
grep -q 'app:uxsdk_sourceCameraSideVisibility="false"' "$LAYOUT"
grep -q 'app:uxsdk_sourceCameraSideVisibility="false"' "$LAYOUT"
grep -q 'horizontalSituationIndicatorWidget.setSimpleModeEnable(true);' "$ACTIVITY"
if grep -q 'pfvFlightDisplayWidget.setVisibility(View.VISIBLE);' "$ACTIVITY"; then
  echo "PrimaryFlightDisplayWidget must not be forced full-screen over camera controls" >&2
  exit 1
fi
```

- [ ] **Step 2: Verify RED**

Run: `bash scripts/msdk-flight-ui.test.sh`

Expected before implementation: the command exits non-zero because the primary FPV label visibility attributes and always-on HSI telemetry mode are not present.

- [ ] **Step 3: Implement the minimal UI change**

Add `app:uxsdk_sourceCameraNameVisibility="false"` and `app:uxsdk_sourceCameraSideVisibility="false"` to the primary FPV widget. Change `DefaultLayoutActivity.updateViewVisibility` so `horizontalSituationIndicatorWidget.setSimpleModeEnable(true)` runs for camera and FPV views, while `PrimaryFlightDisplayWidget` keeps its original FPV-only visibility to avoid blocking camera controls.

- [ ] **Step 4: Verify GREEN**

Run: `bash scripts/msdk-flight-ui.test.sh`

Expected after implementation: command exits with status 0.

- [ ] **Step 5: Build and install**

Run: `./gradlew :app:installDebug` from `rcplus-msdk-agent`.

Expected: Gradle exits with status 0 and installs the updated APK onto the connected RC Plus.
