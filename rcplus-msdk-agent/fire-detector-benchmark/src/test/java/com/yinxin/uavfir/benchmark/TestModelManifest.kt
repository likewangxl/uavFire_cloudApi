package com.yinxin.uavfir.benchmark

internal fun testManifest() = ModelManifest(
    sha256 = "a".repeat(64),
    schemaVersion = 2,
    modelVersion = "visible-fire-test",
    sourceName = "visible-fire-test.pt",
    sourceSha256 = "a".repeat(64),
    classNames = listOf("fire", "smoke"),
    inputWidth = 960,
    inputHeight = 960,
    normalizationScale = 1f / 255f,
    confidenceThreshold = 0.25f,
    iouThreshold = 0.7f,
    artifactsByEngine = Engine.entries.associateWith { emptyList() },
)
