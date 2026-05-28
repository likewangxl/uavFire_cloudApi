#!/usr/bin/env bash
set -euo pipefail

LAYOUT="Mobile-SDK-Android-V5/SampleCode-V5/android-sdk-v5-uxsdk/src/main/res/layout/uxsdk_activity_default_layout.xml"
ACTIVITY="Mobile-SDK-Android-V5/SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/sample/showcase/defaultlayout/DefaultLayoutActivity.java"

grep -q 'android:id="@+id/widget_primary_fpv"' "$LAYOUT"
grep -q 'app:uxsdk_sourceCameraNameVisibility="false"' "$LAYOUT"
grep -q 'app:uxsdk_sourceCameraSideVisibility="false"' "$LAYOUT"
grep -q 'horizontalSituationIndicatorWidget.setSimpleModeEnable(true);' "$ACTIVITY"
if grep -q 'pfvFlightDisplayWidget.setVisibility(View.VISIBLE);' "$ACTIVITY"; then
  echo "PrimaryFlightDisplayWidget must not be forced full-screen over camera controls" >&2
  exit 1
fi
