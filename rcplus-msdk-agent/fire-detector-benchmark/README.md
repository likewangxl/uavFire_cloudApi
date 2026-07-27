# Fire detector benchmark

This APK is the only place where ONNX Runtime, LiteRT, and NCNN may coexist. It is
not a dependency of `:app`. At build time it stages the ignored candidates and fixed
benchmark set from `../../ai-service/mobile-model/` into benchmark-only assets.

Run the JVM gate with:

```bash
./gradlew :fire-detector-benchmark:testDebugUnitTest
```

Run the RC Plus gate only after the official NCNN bridge has been provisioned:

```bash
export ADB_SERIAL=192.168.50.141:5555
./gradlew :fire-detector-benchmark:connectedDebugAndroidTest
adb -s "$ADB_SERIAL" pull \
  /sdcard/Android/data/com.yinxin.uavfir.benchmark/files/fire-detector-benchmark.json \
  build/fire-detector-benchmark.json
```

The device test verifies every candidate and image SHA-256, performs 30 warm-up
inferences, evaluates all 400 fixed images, and then cycles input for 30 minutes.
It writes its evidence to `fire-detector-benchmark.json` under this APK's external
files directory. `selectedEngine` remains null unless all hard gates pass.

## NCNN provisioning contract

NCNN has no supported Maven Android runtime. Before a device run, obtain an official,
checksum-recorded arm64-v8a build and provision these files locally, without committing
them:

```text
fire-detector-benchmark/src/main/jniLibs/arm64-v8a/libncnn.so
fire-detector-benchmark/src/main/jniLibs/arm64-v8a/libfire_detector_ncnn.so
```

`libfire_detector_ncnn.so` must export JNI methods for Kotlin object
`com.yinxin.uavfir.benchmark.NcnnBridge`:

```text
create(String paramPath, String binPath): long
infer(long handle, java.nio.ByteBuffer nchwFloat32): float[42000]
close(long handle): void
```

`create` must load the staged NCNN param/bin files. `infer` receives a direct,
native-order NCHW float32 buffer shaped `[1,3,640,640]`, must call NCNN input blob
`in0`, extract output blob `out0`, and return the contiguous `[5,8400]` YOLO output.
The JNI library must reject a non-direct buffer, invalid handle, or output whose size
is not 42,000. The benchmark applies the shared confidence threshold, NMS, and source
coordinate mapping in Kotlin. Until both files are present, the NCNN adapter fails
before any measurements are recorded and no engine can be selected.
