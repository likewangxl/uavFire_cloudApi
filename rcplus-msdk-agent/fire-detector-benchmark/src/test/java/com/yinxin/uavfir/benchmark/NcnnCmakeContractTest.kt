package com.yinxin.uavfir.benchmark

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Test

class NcnnCmakeContractTest {
    @Test
    fun cmake_buildsTheBridgeAgainstOfficialCmakePackageWithVulkanTarget() {
        val cmake = File("src/main/cpp/CMakeLists.txt").readText()
        val bridge = File("src/main/cpp/ncnn_bridge.cpp").readText()

        assertTrue(cmake.contains("find_package(ncnn CONFIG REQUIRED)"))
        assertTrue(cmake.contains("ncnn_DIR"))
        assertTrue(cmake.contains("NCNN_VULKAN=1"))
        assertTrue(cmake.contains("target_link_libraries(fire_detector_ncnn ${'$'}{NCNN_TARGET} android log)"))
        assertTrue(bridge.contains("extractor.input(\"in0\""))
        assertTrue(bridge.contains("extractor.extract(\"out0\""))
        assertTrue(bridge.contains("use_vulkan_compute = true"))
    }

    @Test(expected = IllegalStateException::class)
    fun provisioning_rejectsOfficialPackageWithoutVulkanCapability() {
        val directory = Files.createTempDirectory("ncnn-package").toFile()
        File(directory, "ncnnConfig.cmake").writeText("add_library(ncnn INTERFACE IMPORTED)")
        val runtime = File(directory, "libncnn.so").apply { writeBytes(byteArrayOf()) }
        val bridge = File(directory, "libfire_detector_ncnn.so").apply { writeBytes(byteArrayOf()) }

        NcnnProvisioning.validate(directory, runtime, bridge)
    }
}
