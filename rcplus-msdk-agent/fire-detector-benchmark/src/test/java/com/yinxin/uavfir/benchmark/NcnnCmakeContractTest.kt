package com.yinxin.uavfir.benchmark

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NcnnCmakeContractTest {
    @Test
    fun cmake_buildsTheBridgeAgainstOfficialCmakePackageWithVulkanTarget() {
        val cmake = File("src/main/cpp/CMakeLists.txt").readText()
        val bridge = File("src/main/cpp/ncnn_bridge.cpp").readText()

        assertTrue(cmake.contains("find_package(ncnn CONFIG REQUIRED)"))
        assertTrue(cmake.contains("ncnn_DIR"))
        assertTrue(cmake.indexOf("find_package(ncnn CONFIG REQUIRED)") < cmake.indexOf("if(NOT NCNN_VULKAN)"))
        assertTrue(cmake.contains("if(NOT NCNN_VULKAN)"))
        assertFalse(cmake.contains("INTERFACE_COMPILE_DEFINITIONS"))
        assertFalse(cmake.contains("NCNN_VULKAN=1"))
        assertTrue(cmake.contains("target_link_libraries(fire_detector_ncnn ${'$'}{NCNN_TARGET} android log)"))
        assertTrue(bridge.contains("extractor.input(\"in0\""))
        assertTrue(bridge.contains("extractor.extract(\"out0\""))
        assertTrue(bridge.contains("use_vulkan_compute = true"))
        assertTrue(bridge.contains("kInputWidth = 960"))
        assertTrue(bridge.contains("kOutputElements = 6 * 18900"))
    }

    @Test
    fun provisioning_acceptsOfficialPackageConfigThatSetsVulkanOn() {
        val directory = Files.createTempDirectory("ncnn-package").toFile()
        File(directory, "ncnnConfig.cmake").writeText("set(NCNN_VULKAN ON)")
        val runtime = File(directory, "libncnn.so").apply { writeBytes(byteArrayOf()) }
        val bridge = File(directory, "libfire_detector_ncnn.so").apply { writeBytes(byteArrayOf()) }

        NcnnProvisioning.validate(directory, runtime, bridge)
    }

    @Test(expected = IllegalStateException::class)
    fun provisioning_rejectsMissingOfficialPackageConfig() {
        val directory = Files.createTempDirectory("ncnn-package").toFile()
        val runtime = File(directory, "libncnn.so").apply { writeBytes(byteArrayOf()) }
        val bridge = File(directory, "libfire_detector_ncnn.so").apply { writeBytes(byteArrayOf()) }

        NcnnProvisioning.validate(directory, runtime, bridge)
    }
}
