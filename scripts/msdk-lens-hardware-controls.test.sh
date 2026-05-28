#!/usr/bin/env bash
set -euo pipefail

ACTIVITY="Mobile-SDK-Android-V5/SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/sample/showcase/defaultlayout/DefaultLayoutActivity.java"
LENS_WIDGET="Mobile-SDK-Android-V5/SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/cameracore/widget/cameracontrols/lenscontrol/LensControlWidget.kt"
LENS_LAYOUT="Mobile-SDK-Android-V5/SampleCode-V5/android-sdk-v5-uxsdk/src/main/res/layout/uxsdk_camera_lens_control_widget.xml"
FLIGHT_LAYOUT="Mobile-SDK-Android-V5/SampleCode-V5/android-sdk-v5-uxsdk/src/main/res/layout/uxsdk_activity_default_layout.xml"

grep -q 'RemoteControllerKey.KeyCustomButton1Down' "$ACTIVITY"
grep -q 'RemoteControllerKey.KeyCustomButton2Down' "$ACTIVITY"
grep -q 'RemoteControllerKey.KeyCustomButton3Down' "$ACTIVITY"
grep -q 'KEYCODE_BUTTON_L1' "$ACTIVITY"
grep -q 'KEYCODE_BUTTON_L2' "$ACTIVITY"
grep -q 'KEYCODE_BUTTON_THUMBL' "$ACTIVITY"
grep -q 'KEYCODE_F1' "$ACTIVITY"
grep -q 'KEYCODE_F2' "$ACTIVITY"
grep -q 'KEYCODE_F3' "$ACTIVITY"
grep -q 'lensControlWidget.selectPreviousLens()' "$ACTIVITY"
grep -q 'lensControlWidget.selectNextLens()' "$ACTIVITY"
grep -q 'markCenterRangingPinPoint' "$ACTIVITY"
grep -q 'KeyLaserMeasureInformation' "$ACTIVITY"
grep -q 'getLiveViewLocationWithGPS' "$ACTIVITY"
grep -q 'PinPointInfo' "$ACTIVITY"
if grep -q 'lensControlWidget.selectZoomLens()' "$ACTIVITY"; then
  echo "L3 must create a ranging Pin point instead of selecting the zoom lens" >&2
  exit 1
fi
grep -q 'lensControlWidget.setVisibility(CameraUtil.isFPVTypeView(devicePosition) ? View.INVISIBLE : View.VISIBLE);' "$ACTIVITY"
grep -q 'lensControlWidget.updateCameraSource(devicePosition, lensType);' "$ACTIVITY"

grep -q 'android:id="@+id/thermal_len_btn"' "$LENS_LAYOUT"
grep -q 'android:id="@+id/wide_len_btn"' "$LENS_LAYOUT"
grep -q 'android:id="@+id/zoom_len_btn"' "$LENS_LAYOUT"
grep -q '<TextView' "$LENS_LAYOUT"
grep -q 'android:clickable="false"' "$LENS_LAYOUT"
grep -q 'android:focusable="false"' "$LENS_LAYOUT"
grep -q 'android:background="@android:color/transparent"' "$LENS_LAYOUT"
grep -q 'android:textColor="@color/uxsdk_lens_selector_button_text"' "$LENS_LAYOUT"
grep -q 'app:layout_constraintStart_toStartOf="parent"' "$FLIGHT_LAYOUT"
grep -q 'app:layout_constraintBottom_toBottomOf="parent"' "$FLIGHT_LAYOUT"
grep -q 'android:layout_marginStart="6dp"' "$FLIGHT_LAYOUT"
grep -q 'android:translationY="44dp"' "$FLIGHT_LAYOUT"
grep -q 'android:layout_marginTop="18dp"' "$LENS_LAYOUT"
grep -q 'android:textSize="13sp"' "$LENS_LAYOUT"
grep -q 'android:translationY="18dp"' "$LENS_LAYOUT"
grep -q 'android:translationY="-18dp"' "$LENS_LAYOUT"

grep -q 'fun selectThermalLens()' "$LENS_WIDGET"
grep -q 'fun selectWideLens()' "$LENS_WIDGET"
grep -q 'fun selectZoomLens()' "$LENS_WIDGET"
grep -q 'fun selectPreviousLens()' "$LENS_WIDGET"
grep -q 'fun selectNextLens()' "$LENS_WIDGET"
grep -q 'selectAdjacentLens' "$LENS_WIDGET"
grep -q 'selectLens(CameraVideoStreamSourceType.WIDE_CAMERA)' "$LENS_WIDGET"
grep -q 'selectLens(CameraVideoStreamSourceType.ZOOM_CAMERA)' "$LENS_WIDGET"
grep -q 'selectLens(CameraVideoStreamSourceType.INFRARED_CAMERA)' "$LENS_WIDGET"
grep -q 'lensLabel(source)' "$LENS_WIDGET"
if grep -q 'setOnClickListener' "$LENS_WIDGET"; then
  echo "Lens labels must not be touch controls" >&2
  exit 1
fi
if grep -q 'View.OnClickListener' "$LENS_WIDGET"; then
  echo "Lens labels must not implement touch click handling" >&2
  exit 1
fi
grep -q 'CameraVideoStreamSourceType.WIDE_CAMERA' "$LENS_WIDGET"
grep -q 'CameraVideoStreamSourceType.ZOOM_CAMERA' "$LENS_WIDGET"
grep -q 'CameraVideoStreamSourceType.INFRARED_CAMERA' "$LENS_WIDGET"
