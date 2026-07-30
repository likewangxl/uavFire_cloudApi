package com.yinxin.uavfir.firedetection

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

class AppServicesVisibleInferenceSourceTest {
    @Test
    fun appServicesArmsIngressAndLoopOnlyAfterFactoryReturnsArmed() {
        val source = String(
            Files.readAllBytes(Paths.get("src/main/java/com/yinxin/uavfir/AppServices.kt")),
        )

        assertTrue(source.contains("VisibleFireDetectorFactory.create(application)"))
        assertTrue(
            source.contains(
                "visibleFireDetectorArming as? VisibleFireDetectorArmingResult.Armed",
            ),
        )
        assertTrue(
            "disabled/failed detector must leave callback ingress as a no-op",
            source.contains(
                "if (visibleInferenceLoop != null) latestVisibleFrameBuffer else VisibleFrameIngress.NO_OP",
            ),
        )
        assertTrue(
            "only an armed loop may start",
            source.contains("visibleInferenceLoop?.start(appScope)"),
        )
    }

    @Test
    fun productionVisibleLocalizationUsesOnlyTheSingleLocalInferenceStream() {
        val productionRoot = Paths.get("src/main/java")
        val productionSource = Files.walk(productionRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .map { String(Files.readAllBytes(it)) }
                .toList()
                .joinToString("\n")
        }
        val appServices = String(
            Files.readAllBytes(Paths.get("src/main/java/com/yinxin/uavfir/AppServices.kt")),
        )
        assertFalse(productionSource.contains("latestVisibleRoi"))
        assertFalse(productionSource.contains("latest-visible-roi"))
        assertFalse(productionSource.contains("BackendVisibleTargetAimer"))
        assertTrue(appServices.contains("VisibleInferenceResultStream()"))
        assertTrue(appServices.contains("resultPublisher = visibleInferenceResults"))
        assertTrue(appServices.contains("detectionSource = visibleInferenceResults"))
        assertEquals(
            "production must create exactly one visible inference loop",
            1,
            Regex("VisibleInferenceLoop\\(").findAll(appServices).count(),
        )
    }
}
