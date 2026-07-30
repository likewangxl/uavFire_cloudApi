package com.yinxin.uavfir.firedetection

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleFireModelManifestTest {
    @Test
    fun productionManifestBindsApprovedVisible960ModelAndOnlyNcnnArtifacts() {
        val manifestFile = File("src/main/assets/fire-detection/model-manifest.json")
        val manifest = VisibleFireModelManifestParser.parse(manifestFile.readText())

        assertEquals("PROVISIONAL_NCNN_SELECTED", manifest.status)
        assertFalse(manifest.enabledByDefault)
        assertFalse(manifest.visible960GatePassed)
        assertEquals("ncnn", manifest.runtime)
        assertEquals("20260526", manifest.runtimeVersion)
        assertEquals(
            "eb205b332274974511890903828451ae7a4c19c309f21431536e0a8c9f3dd0c1",
            manifest.runtimeArchiveSha256,
        )
        assertEquals("visible-fire-wechat-best2-20260728.pt", manifest.sourceName)
        assertEquals(
            "957bec7a567ce1f57f9a57187a6b085c7c95149b889773479d018e3ed5e9f650",
            manifest.sourceSha256,
        )
        assertEquals(listOf("fire", "smoke"), manifest.classNames)
        assertEquals(960, manifest.inputWidth)
        assertEquals(960, manifest.inputHeight)
        assertEquals(0.25f, manifest.confidenceThreshold, 0f)
        assertEquals(0.7f, manifest.iouThreshold, 0f)
        assertEquals(
            mapOf(
                "fire-detection/visible-fire-960.ncnn.param" to
                    "1600cc47b5ca71330e99fc9ae0852409f0309142ea176f4c7a45eae56e87aec7",
                "fire-detection/visible-fire-960.ncnn.bin" to
                    "3bfb2288cff211b3afa2f3c1f9ab6f16cab8bc625f6e3a3e436984acc4a7e84d",
            ),
            manifest.artifacts.associate { it.path to it.sha256 },
        )
    }

    @Test(expected = IllegalStateException::class)
    fun parserRejectsUnknownRuntime() {
        VisibleFireModelManifestParser.parse(validManifest().replace("\"ncnn\"", "\"onnx\""))
    }

    @Test
    fun parserRejectsChangedHashesClassOrderInputOrThresholds() {
        listOf(
            validManifest().replace("\"fire\",\"smoke\"", "\"smoke\",\"fire\""),
            validManifest().replace("\"width\":960", "\"width\":640"),
            validManifest().replace("\"confidenceThreshold\":0.25", "\"confidenceThreshold\":0.20"),
            validManifest().replace("\"iouThreshold\":0.7", "\"iouThreshold\":0.5"),
            validManifest().replace(
                "957bec7a567ce1f57f9a57187a6b085c7c95149b889773479d018e3ed5e9f650",
                "a".repeat(64),
            ),
            validManifest().replace(
                "1600cc47b5ca71330e99fc9ae0852409f0309142ea176f4c7a45eae56e87aec7",
                "b".repeat(64),
            ),
            validManifest().replace(
                "eb205b332274974511890903828451ae7a4c19c309f21431536e0a8c9f3dd0c1",
                "c".repeat(64),
            ),
        ).forEach { changed ->
            val failure = runCatching { VisibleFireModelManifestParser.parse(changed) }.exceptionOrNull()
            assertTrue("Changed manifest contract must fail closed", failure is IllegalStateException)
        }
    }
}

internal fun validManifest(
    paramSha256: String = "1600cc47b5ca71330e99fc9ae0852409f0309142ea176f4c7a45eae56e87aec7",
    binSha256: String = "3bfb2288cff211b3afa2f3c1f9ab6f16cab8bc625f6e3a3e436984acc4a7e84d",
): String =
    """
    {
      "schemaVersion":1,
      "status":"PROVISIONAL_NCNN_SELECTED",
      "enabledByDefault":false,
      "visible960GatePassed":false,
      "runtime":{"name":"ncnn","version":"20260526","archiveSha256":"eb205b332274974511890903828451ae7a4c19c309f21431536e0a8c9f3dd0c1"},
      "modelVersion":"visible-fire-wechat-best2-20260728",
      "source":{"name":"visible-fire-wechat-best2-20260728.pt","sha256":"957bec7a567ce1f57f9a57187a6b085c7c95149b889773479d018e3ed5e9f650"},
      "classes":["fire","smoke"],
      "input":{"width":960,"height":960,"channels":3,"colorSpace":"RGB","normalizationScale":0.00392156862745098},
      "postprocess":{"confidenceThreshold":0.25,"iouThreshold":0.7,"outputLayout":"xywh, class scores; postprocess with NMS"},
      "artifacts":[
        {"path":"fire-detection/visible-fire-960.ncnn.param","sha256":"$paramSha256"},
        {"path":"fire-detection/visible-fire-960.ncnn.bin","sha256":"$binSha256"}
      ]
    }
    """.trimIndent()
