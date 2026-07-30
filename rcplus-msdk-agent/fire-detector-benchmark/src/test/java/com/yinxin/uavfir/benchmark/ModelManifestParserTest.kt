package com.yinxin.uavfir.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelManifestParserTest {
    @Test
    fun parse_acceptsVisibleSchemaV2AndCandidateArtifactHashes() {
        val manifest = ModelManifestParser.parse(validVisibleManifest())

        assertEquals(2, manifest.schemaVersion)
        assertEquals(listOf("fire", "smoke"), manifest.classNames)
        assertEquals("visible-fire-test.pt", manifest.sourceName)
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
      "modelVersion":"visible-fire-test",
      "source":{"name":"visible-fire-test.pt","sha256":"${"a".repeat(64)}"},
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
