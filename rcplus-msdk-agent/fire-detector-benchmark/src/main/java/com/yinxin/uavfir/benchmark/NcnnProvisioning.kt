package com.yinxin.uavfir.benchmark

import java.io.File

/** Validates the local, official CMake package before a Vulkan NCNN bridge is accepted. */
internal object NcnnProvisioning {
    fun validate(packageDir: File, runtimeLibrary: File, bridgeLibrary: File) {
        val config = File(packageDir, "ncnnConfig.cmake")
        check(config.isFile) { "Official ncnnConfig.cmake package is missing" }
        check(config.readText().contains("NCNN_VULKAN=1")) { "Official NCNN package is not Vulkan-capable" }
        check(runtimeLibrary.isFile && runtimeLibrary.name == "libncnn.so") { "Official NCNN arm64 runtime is missing" }
        check(bridgeLibrary.isFile && bridgeLibrary.name == "libfire_detector_ncnn.so") { "Built NCNN JNI bridge is missing" }
    }
}
