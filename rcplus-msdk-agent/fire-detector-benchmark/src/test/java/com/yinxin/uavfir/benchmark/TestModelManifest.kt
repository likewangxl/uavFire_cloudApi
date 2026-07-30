package com.yinxin.uavfir.benchmark

internal fun testManifest() = ModelManifest(
    sha256 = "a".repeat(64),
    schemaVersion = 2,
    modelVersion = "visible-fire-wechat-best2-20260728",
    sourceName = "visible-fire-wechat-best2-20260728.pt",
    sourceSha256 = "957bec7a567ce1f57f9a57187a6b085c7c95149b889773479d018e3ed5e9f650",
    classNames = listOf("fire", "smoke"),
    inputWidth = 960,
    inputHeight = 960,
    normalizationScale = 1f / 255f,
    confidenceThreshold = 0.25f,
    iouThreshold = 0.7f,
    artifactsByEngine = Engine.entries.associateWith { emptyList() },
)
