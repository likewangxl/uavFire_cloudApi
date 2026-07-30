package com.yinxin.uavfir.firedetection

import com.google.gson.JsonParser
import com.yinxin.uavfir.BuildConfig
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionFireDetectorPackagingTest {
    @Test
    fun buildPolicyKeepsDetectorDisabledAndExcludesRemoteAndAlternateRuntimes() {
        val buildScript = File("build.gradle.kts").readText()

        assertFalse(buildScript.contains("AGENT_AI_SERVICE_BASE_URL"))
        assertFalse(buildScript.contains("onnxruntime", ignoreCase = true))
        assertFalse(buildScript.contains("tensorflow-lite", ignoreCase = true))
        assertTrue(buildScript.contains("VISIBLE_FIRE_DETECTION_ENABLED"))
        assertFalse(BuildConfig.VISIBLE_FIRE_DETECTION_ENABLED)
        assertTrue(buildScript.contains("20260526"))
        assertTrue(buildScript.contains("eb205b332274974511890903828451ae7a4c19c309f21431536e0a8c9f3dd0c1"))
    }

    @Test
    fun debugApkContainsOneNcnnRuntimeAndExactlyOneVisibleFireModel() {
        val apk = File("build/outputs/apk/debug/app-debug.apk")
        assertTrue("packageDebug must run before this APK-content test", apk.isFile)

        ZipFile(apk).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toList()
            val forbidden = entries.filter {
                it.endsWith(".onnx", ignoreCase = true) ||
                    it.endsWith(".tflite", ignoreCase = true) ||
                    it.contains("onnxruntime", ignoreCase = true) ||
                    it.contains("tensorflowlite", ignoreCase = true)
            }
            assertTrue("Alternate inference runtime/model entries: $forbidden", forbidden.isEmpty())

            val detectorLibraries = entries.filter {
                it == "lib/arm64-v8a/libncnn.so" ||
                    it == "lib/arm64-v8a/libvisible_fire_ncnn.so"
            }
            assertEquals(
                setOf("lib/arm64-v8a/libncnn.so", "lib/arm64-v8a/libvisible_fire_ncnn.so"),
                detectorLibraries.toSet(),
            )

            val modelEntries = entries.filter {
                it.endsWith(".param", ignoreCase = true) ||
                    it.endsWith(".onnx", ignoreCase = true) ||
                    it.endsWith(".tflite", ignoreCase = true) ||
                    (it.startsWith("assets/") && it.endsWith(".bin", ignoreCase = true))
            }
            assertEquals(
                setOf(
                    "assets/fire-detection/visible-fire-960.ncnn.param",
                    "assets/fire-detection/visible-fire-960.ncnn.bin",
                ),
                modelEntries.toSet(),
            )
            val manifestEntry = zip.getEntry("assets/fire-detection/model-manifest.json")
            assertNotNull(manifestEntry)
            val manifest = VisibleFireModelManifestParser.parse(
                zip.getInputStream(manifestEntry).bufferedReader().use { it.readText() },
            )
            manifest.artifacts.forEach { artifact ->
                assertEquals(
                    artifact.sha256,
                    zip.sha256("assets/${artifact.path}"),
                )
            }

            val trustEntry = zip.getEntry("assets/fire-detection/ncnn-runtime-trust.json")
            assertNotNull(trustEntry)
            val trust = JsonParser.parseReader(
                zip.getInputStream(trustEntry).bufferedReader(),
            ).asJsonObject
            assertEquals("20260526", trust.get("version").asString)
            assertEquals(
                "eb205b332274974511890903828451ae7a4c19c309f21431536e0a8c9f3dd0c1",
                trust.get("packageArchiveSha256").asString,
            )
            assertEquals(
                trust.get("runtimeSha256").asString,
                zip.sha256("lib/arm64-v8a/libncnn.so"),
            )
            assertEquals(
                trust.get("bridgeSha256").asString,
                zip.sha256("lib/arm64-v8a/libvisible_fire_ncnn.so"),
            )
        }
    }
}

private fun ZipFile.sha256(path: String): String = getInputStream(getEntry(path)).use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}
