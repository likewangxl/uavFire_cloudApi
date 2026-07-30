package com.yinxin.uavfir.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelManifestParserTest {
    @Test
    fun parse_acceptsVisibleSchemaV2AndCandidateArtifactHashes() {
        val manifest = ModelManifestParser.parse(validVisibleManifest())

        assertEquals(2, manifest.schemaVersion)
        assertEquals(listOf("fire", "smoke"), manifest.classNames)
        assertEquals("visible-fire-wechat-best2-20260728.pt", manifest.sourceName)
        assertEquals("957bec7a567ce1f57f9a57187a6b085c7c95149b889773479d018e3ed5e9f650", manifest.sourceSha256)
        assertEquals(960, manifest.inputWidth)
        assertEquals(0.25f, manifest.confidenceThreshold, 0f)
        assertEquals("model.onnx", manifest.artifact(Engine.ONNX).single().path)
        assertEquals(2, manifest.artifact(Engine.NCNN).size)
    }

    @Test(expected = IllegalStateException::class)
    fun parse_rejectsLegacyThermalManifest() {
        ModelManifestParser.parse(
            """
            {
              "inputSize":[640,640],
              "classNames":["fire"],
              "normalization":{"mean":[0,0,0],"std":[1,1,1],"scale":0.00392156862745098},
              "confidenceThreshold":0.25,
              "iouThreshold":0.7,
              "outputLayout":"xywh, class scores; postprocess with NMS",
              "candidates":[]
            }
            """.trimIndent(),
        )
    }

    @Test(expected = IllegalStateException::class)
    fun parse_rejectsSchemaV2At640Pixels() {
        ModelManifestParser.parse(validVisibleManifest().replace("\"width\":960", "\"width\":640"))
    }

    @Test(expected = IllegalStateException::class)
    fun parse_rejectsSchemaV2WithoutBothVisibleClasses() {
        ModelManifestParser.parse(validVisibleManifest().replace("\"classes\":[\"fire\",\"smoke\"]", "\"classes\":[\"fire\"]"))
    }
}

internal fun validVisibleManifest() =
    """
    {
      "schemaVersion":2,
      "modality":"visible",
      "modelVersion":"visible-fire-wechat-best2-20260728",
      "source":{"name":"visible-fire-wechat-best2-20260728.pt","sha256":"957bec7a567ce1f57f9a57187a6b085c7c95149b889773479d018e3ed5e9f650"},
      "classes":["fire","smoke"],
      "input":{
        "width":960,
        "height":960,
        "channels":3,
        "colorSpace":"RGB",
        "normalization":{"mean":[0,0,0],"std":[1,1,1],"scale":0.00392156862745098}
      },
      "postprocess":{
        "confidenceThreshold":0.25,
        "iouThreshold":0.7,
        "outputLayout":"xywh, class scores; postprocess with NMS"
      },
      "candidates":[
        {"engine":"onnx","artifacts":[{"path":"model.onnx","sha256":"aa"}]},
        {"engine":"tflite","artifacts":[{"path":"model.tflite","sha256":"bb"}]},
        {"engine":"ncnn","artifacts":[{"path":"model.param","sha256":"cc"},{"path":"model.bin","sha256":"dd"}]}
      ]
    }
    """.trimIndent()
