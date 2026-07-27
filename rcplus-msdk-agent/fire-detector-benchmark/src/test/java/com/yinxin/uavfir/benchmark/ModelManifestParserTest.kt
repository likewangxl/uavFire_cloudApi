package com.yinxin.uavfir.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelManifestParserTest {
    @Test
    fun parse_enforcesSharedModelContractAndCandidateArtifactHashes() {
        val manifest = ModelManifestParser.parse(
            """
            {
              "inputSize":[640,640],
              "normalization":{"mean":[0,0,0],"std":[1,1,1],"scale":0.00392156862745098},
              "confidenceThreshold":0.25,
              "iouThreshold":0.7,
              "outputLayout":"xywh, class scores; postprocess with NMS",
              "candidates":[
                {"engine":"onnx","artifacts":[{"path":"model.onnx","sha256":"aa"}]},
                {"engine":"tflite","artifacts":[{"path":"model.tflite","sha256":"bb"}]},
                {"engine":"ncnn","artifacts":[{"path":"model.param","sha256":"cc"},{"path":"model.bin","sha256":"dd"}]}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(640, manifest.inputWidth)
        assertEquals(0.25f, manifest.confidenceThreshold, 0f)
        assertEquals("model.onnx", manifest.artifact(Engine.ONNX).single().path)
        assertEquals(2, manifest.artifact(Engine.NCNN).size)
    }

    @Test(expected = IllegalStateException::class)
    fun parse_rejectsUnexpectedOutputContract() {
        ModelManifestParser.parse("""{"inputSize":[640,640],"normalization":{"mean":[0,0,0],"std":[1,1,1],"scale":1},"confidenceThreshold":0.25,"iouThreshold":0.7,"outputLayout":"unknown","candidates":[]}""")
    }
}
