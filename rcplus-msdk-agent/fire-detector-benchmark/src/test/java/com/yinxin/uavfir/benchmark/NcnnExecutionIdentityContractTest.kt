package com.yinxin.uavfir.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

class NcnnExecutionIdentityContractTest {
    private val runtime = "1".repeat(64)
    private val bridge = "2".repeat(64)
    private val source = "3".repeat(64)
    private val apk = "4".repeat(64)

    @Test
    fun parse_bindsTrustToTheActuallyExecutingApkLibrariesAndReviewedSource() {
        val identity = NcnnExecutionIdentityContract.validate(
            trustJson = trust(runtime, bridge, source),
            executingBenchmarkApkSha256 = apk,
            executingRuntimeSha256 = runtime,
            executingBridgeSha256 = bridge,
            reviewedSourceSha256 = source,
            approvedVersion = APPROVED_NCNN_VERSION,
            approvedArchiveSha256 = APPROVED_NCNN_ARCHIVE_SHA256,
        )

        assertEquals(apk, identity.executingBenchmarkApkSha256)
        assertEquals(runtime, identity.executingRuntimeSha256)
        assertEquals(bridge, identity.executingBridgeSha256)
        assertEquals(source, identity.reviewedBridgeSourceSha256)
    }

    @Test(expected = IllegalStateException::class)
    fun parse_rejectsSelfReportedSourceThatIsNotTheReviewedCurrentSource() {
        NcnnExecutionIdentityContract.validate(
            trustJson = trust(runtime, bridge, "9".repeat(64)),
            executingBenchmarkApkSha256 = apk,
            executingRuntimeSha256 = runtime,
            executingBridgeSha256 = bridge,
            reviewedSourceSha256 = source,
            approvedVersion = APPROVED_NCNN_VERSION,
            approvedArchiveSha256 = APPROVED_NCNN_ARCHIVE_SHA256,
        )
    }

    @Test(expected = IllegalStateException::class)
    fun parse_rejectsTrustForDifferentLibrariesThanThoseActuallyExecuting() {
        NcnnExecutionIdentityContract.validate(
            trustJson = trust("8".repeat(64), bridge, source),
            executingBenchmarkApkSha256 = apk,
            executingRuntimeSha256 = runtime,
            executingBridgeSha256 = bridge,
            reviewedSourceSha256 = source,
            approvedVersion = APPROVED_NCNN_VERSION,
            approvedArchiveSha256 = APPROVED_NCNN_ARCHIVE_SHA256,
        )
    }

    private fun trust(runtimeSha256: String, bridgeSha256: String, sourceSha256: String) =
        """{"version":"$APPROVED_NCNN_VERSION","packageArchiveSha256":"$APPROVED_NCNN_ARCHIVE_SHA256","bridgeSourceSha256":"$sourceSha256","runtimeSha256":"$runtimeSha256","bridgeSha256":"$bridgeSha256"}"""
}
