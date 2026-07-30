package com.yinxin.uavfir.firedetection

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertTrue
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
                "if (visibleInferenceLoop != null) latestVisibleFrameBuffer else VisibleFrameOffer.NO_OP",
            ),
        )
        assertTrue(
            "only an armed loop may start",
            source.contains("visibleInferenceLoop?.start(appScope)"),
        )
    }
}
