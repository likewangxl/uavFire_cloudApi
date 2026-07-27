package com.yinxin.uavfir.benchmark

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NcnnCmakeContractTest {
    @Test
    fun cmake_buildsTheBridgeAgainstProvisionedOfficialSdkWithoutVendoringBinaries() {
        val cmake = File("src/main/cpp/CMakeLists.txt").readText()
        val bridge = File("src/main/cpp/ncnn_bridge.cpp").readText()

        assertTrue(cmake.contains("NCNN_SDK_DIR"))
        assertTrue(cmake.contains("lib/${'$'}{ANDROID_ABI}/libncnn.so"))
        assertTrue(cmake.contains("target_link_libraries(fire_detector_ncnn ncnn android log)"))
        assertTrue(bridge.contains("extractor.input(\"in0\""))
        assertTrue(bridge.contains("extractor.extract(\"out0\""))
    }
}
