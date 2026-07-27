package com.yinxin.uavfir.benchmark

internal fun testManifest() = ModelManifest(
    sha256 = "a".repeat(64),
    inputWidth = 640,
    inputHeight = 640,
    normalizationScale = 1f / 255f,
    confidenceThreshold = 0.25f,
    iouThreshold = 0.7f,
    artifactsByEngine = Engine.entries.associateWith { emptyList() },
)
