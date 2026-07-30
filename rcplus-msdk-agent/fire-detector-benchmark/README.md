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
export FORMAL_AGENT_APK_SHA256="$(shasum -a 256 "$FORMAL_AGENT_APK" | awk '{print $1}')"
adb -s "$UAVFIRE_ADB_SERIAL" get-state
./gradlew :fire-detector-benchmark:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.agentApkSha256="$FORMAL_AGENT_APK_SHA256"
adb -s "$UAVFIRE_ADB_SERIAL" pull \
  /sdcard/Android/data/com.yinxin.uavfir.benchmark/files/fire-detector-benchmark.json \
  build/visible-960-fire-detector-benchmark.json
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

The gate fails unless `com.yinxin.uavfir` is installed and its installed base APK hash
matches `agentApkSha256`. The final JSON records the exact model, benchmark, PyTorch
baseline, benchmark APK, instrumentation APK, device fingerprint, hashed Android ID,
Agent version/APK/signing certificate, and whether the Agent process was co-running.
Partial engine results are merged only
when every provenance field and `runId` matches. When running one engine at a time,
pass the same explicit run ID to every invocation:

```bash
-Pandroid.testInstrumentationRunnerArguments.runId=visible-960-20260730-01
```

## NCNN provisioning contract

NCNN has no supported Maven Android runtime. Before a device run, obtain an official,
checksum-recorded arm64-v8a build and provision these files locally, without committing
them:

Build the committed bridge source against a locally provisioned official SDK:

```bash
cmake -S src/main/cpp -B build/local-ncnn \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-26 \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -Dncnn_DIR=/absolute/path/to/checksum-recorded-ncnn-package/lib/cmake/ncnn
cmake --build build/local-ncnn
```

Use the SDK and bridge locations only as local Gradle properties, never source files:

```text
ncnnPackageDir=/absolute/path/to/checksum-recorded-ncnn-package/lib/cmake/ncnn
ncnnRuntimeLibrary=/absolute/path/to/checksum-recorded-ncnn-package/lib/arm64-v8a/libncnn.so
ncnnBridgeDir=/absolute/path/to/build/local-ncnn
```

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
  -PncnnPackageDir=/absolute/path/to/checksum-recorded-ncnn-package/lib/cmake/ncnn \
  -PncnnRuntimeLibrary=/absolute/path/to/checksum-recorded-ncnn-package/lib/arm64-v8a/libncnn.so \
  -PncnnBridgeDir=/absolute/path/to/build/local-ncnn
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
test fails closed when that file is not packaged.
