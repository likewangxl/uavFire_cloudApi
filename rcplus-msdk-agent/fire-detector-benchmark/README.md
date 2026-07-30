# Fire detector benchmark

This APK is the only place where ONNX Runtime, LiteRT, and NCNN may coexist. It is
not a dependency of `:app`. At build time it stages the ignored candidates and fixed
visible-light benchmark set from `../../ai-service/mobile-model/visible-960/` into
benchmark-only assets. Legacy thermal/640 manifests are rejected; only schema v2,
960x960 RGB, and the ordered classes `fire`, `smoke` are accepted.

Run the JVM gate with:

```bash
./gradlew :fire-detector-benchmark:testDebugUnitTest
```

Run the RC Plus gate only after the official NCNN bridge and candidate APK-delta metadata have been provisioned:

```bash
export UAVFIRE_ADB_SERIAL=192.168.50.141:5555
export ANDROID_SERIAL="$UAVFIRE_ADB_SERIAL"
export FORMAL_AGENT_APK=/absolute/path/to/formal-agent-with-real-uxsdk.apk
export NCNN_ARCHIVE=/absolute/path/to/ncnn-20260526-android-vulkan-shared.zip
export ANDROID_NDK_DIR=/absolute/path/to/android-ndk
adb -s "$UAVFIRE_ADB_SERIAL" get-state
./gradlew :fire-detector-benchmark:connectedDebugAndroidTest \
  -PformalAgentApk="$FORMAL_AGENT_APK" \
  -PncnnArchive="$NCNN_ARCHIVE" \
  -PncnnAndroidNdkDir="$ANDROID_NDK_DIR"
adb -s "$UAVFIRE_ADB_SERIAL" shell am instrument -w \
  -e exportOnly true \
  com.yinxin.uavfir.benchmark.test/androidx.test.runner.AndroidJUnitRunner
adb -s "$UAVFIRE_ADB_SERIAL" exec-out run-as com.yinxin.uavfir.benchmark \
  cat files/fire-detector-benchmark.json \
  > build/visible-960-fire-detector-benchmark.json
```

The device test verifies every candidate and image SHA-256, requires both visible
classes in the evaluated labels, performs 30 warm-up inferences, evaluates all 400
fixed images, and then cycles input for 30 minutes per engine.
It writes its evidence to `fire-detector-benchmark.json` under this APK's external
files directory. Each engine must have complete accuracy, P95, first/final five-minute,
and measured 30-minute evidence. ONNX and TFLite are comparison evidence only. NCNN is
the approved production target and `selectedEngine` remains null unless NCNN independently
stays within 2 recall points of PyTorch, has P95 at or below 200 ms, and degrades no
more than 20% from the first to final five-minute window. Do not substitute another
runtime or weaken these limits.

The gate fails unless `com.yinxin.uavfir` is installed and its installed base APK and
signing-certificate hashes match the build-bound `formal-agent-trust.json`. Gradle
accepts an APK only when its signer, whole-APK hash, version, and build ID exactly
match a reviewed entry in the committed `agent-trust-anchor.json`; the checked-in
anchor is intentionally empty and packaging fails closed until a formal release is
reviewed. Add only the exact release signer/APK/version/build ID approved for this
gate. The APK must expose the signature-protected `agent-sdk-health-v1` provider, and
the benchmark APK must use the same approved signer. The provider reports healthy
only after the real UXSDK class/source, MSDK initialization, and SDK registration
have all succeeded. No instrumentation argument can self-attest Agent identity.
The installed Agent process and provider must remain healthy at
the start, throughout, and end of every soak. The final JSON records the exact model, benchmark, PyTorch
baseline, benchmark APK, instrumentation APK, device fingerprint, hashed Android ID,
Agent version/APK/signing certificate, real-UXSDK marker, health checks, candidate APK,
runtime libraries, and NCNN package/source/bridge hashes.

Partial engine results are merged only when every provenance field, harness-generated
session nonce, boot ID, six-hour monotonic-clock expiry, provenance digest, adapter
target, and per-report digest matches. Operators do
not supply a run ID. For thermal safety on RC Plus 2, start the split sequence with
ONNX and then continue the same on-device session:

```bash
-Pandroid.testInstrumentationRunnerArguments.engine=onnx \
-Pandroid.testInstrumentationRunnerArguments.sessionAction=start

-Pandroid.testInstrumentationRunnerArguments.engine=tflite \
-Pandroid.testInstrumentationRunnerArguments.sessionAction=continue

-Pandroid.testInstrumentationRunnerArguments.engine=ncnn \
-Pandroid.testInstrumentationRunnerArguments.sessionAction=continue
```

Starting a gate deletes old final/partial files in both app storage locations.
`exportOnly` never exports partial evidence: it revalidates freshness, all three
report digests, actual adapter identities, final-result digest, and recomputes NCNN
selection before copying the completed result into `filesDir`. A schema-less result
or a partial without exact current provenance is legacy evidence
and must never be used for selection. Previously generated thermal results belong
under `build/quarantine/*.quarantined`, not at the normal result path.

## Human override: development-only NCNN selection

The older RC Plus thermal-640 result (recall 0.985, P95 152 ms, first-window
138 ms, final-window 153 ms) is recorded separately in
`src/main/assets/provisional-ncnn-selection.json` as
`PROVISIONAL_NCNN_SELECTED`. It may unblock Task 3 development builds only. It
does not set `VISIBLE_960_GATE_PASSED`, enable the detector by default, or authorize
a production release. Those remain blocked until a fresh formal RC Plus visible-960
three-engine run passes this harness.

## NCNN provisioning contract

NCNN has no supported Maven Android runtime. This module is locked to the official
`ncnn-20260526-android-vulkan-shared.zip` archive with SHA-256
`eb205b332274974511890903828451ae7a4c19c309f21431536e0a8c9f3dd0c1`.
Keep that archive local. A missing archive, wrong hash, missing NDK, or failed source
build stops packaging.

Gradle verifies and extracts the archive, then builds the committed bridge source
itself:

```bash
./gradlew :fire-detector-benchmark:buildNcnnBridgeFromSource \
  -PncnnArchive=/absolute/path/to/ncnn-20260526-android-vulkan-shared.zip \
  -PncnnAndroidNdkDir=/absolute/path/to/android-ndk
```

Use only the archive and NDK locations as local Gradle properties:

```text
ncnnArchive=/absolute/path/to/ncnn-20260526-android-vulkan-shared.zip
ncnnAndroidNdkDir=/absolute/path/to/android-ndk
```

The build writes `ncnn-runtime-trust.json` containing the locked package/version,
aggregate current-source hash, runtime hash, and newly built bridge hash.
`libfire_detector_ncnn.so` is built from `src/main/cpp/ncnn_bridge.cpp` and exports JNI methods for Kotlin object
`com.yinxin.uavfir.benchmark.NcnnBridge`:

```text
create(String paramPath, String binPath): long
infer(long handle, java.nio.ByteBuffer nchwFloat32): float[113400]
close(long handle): void
```

`create` must load the staged NCNN param/bin files. `infer` receives a direct,
native-order NCHW float32 buffer shaped `[1,3,960,960]`, must call NCNN input blob
`in0`, extract output blob `out0`, and return the contiguous `[6,18900]` YOLO output.
The JNI library must reject a non-direct buffer, invalid handle, or output whose size
is not 113,400. The benchmark applies the same RGBA-to-RGB letterbox conversion,
confidence threshold, class-aware NMS, and source
coordinate mapping in Kotlin. Until both files are present, the NCNN adapter fails
before any measurements are recorded and no engine can be selected.
The official CMake package must set `NCNN_VULKAN` to `ON`; the bridge validates that
value after `find_package`, enables Vulkan compute, and rejects provisioning when no
Vulkan GPU is available.

## APK delta metadata

The device gate accepts only `apk-delta.json` produced from whole arm64-only APKs,
not a sum of entries from the benchmark APK. Build each production-like candidate
with its selected runtime/model only, capture its APK, then write metadata:

```bash
./gradlew :fire-detector-benchmark:captureCandidateMeasurementApk -PfireDetectorCandidate=baseline
./gradlew :fire-detector-benchmark:captureCandidateMeasurementApk -PfireDetectorCandidate=onnx
./gradlew :fire-detector-benchmark:captureCandidateMeasurementApk -PfireDetectorCandidate=tflite
./gradlew :fire-detector-benchmark:captureCandidateMeasurementApk -PfireDetectorCandidate=ncnn \
  -PncnnArchive=/absolute/path/to/ncnn-20260526-android-vulkan-shared.zip \
  -PncnnAndroidNdkDir=/absolute/path/to/android-ndk
./gradlew :fire-detector-benchmark:writeApkDeltaMetadata
```

For a local, synthetic contract check that executes the metadata task against
generated arm64 APK fixtures, run:

```bash
./gradlew :fire-detector-benchmark:verifyWriteApkDeltaMetadataFixture
```

The metadata task rejects missing APKs, non-arm64 native entries, candidate APKs
without native runtime code, and any candidate smaller than the runtime-free
baseline. It writes `build/generated/apkDeltaMetadata/apk-delta.json`; the device
test fails closed when that file is not packaged. Each runtime entry carries its
archive SHA-256; NCNN metadata additionally binds the locked archive/version,
current bridge source, packaged runtime, packaged bridge, and whole candidate APK.
Candidate APK metadata is size/provenance evidence only. During the NCNN run the
harness separately hashes the benchmark APK that is actually executing and the
`libncnn.so`/`libfire_detector_ncnn.so` entries inside it, then requires those hashes
and the BuildConfig-pinned reviewed source digest to match `ncnn-runtime-trust.json`.
