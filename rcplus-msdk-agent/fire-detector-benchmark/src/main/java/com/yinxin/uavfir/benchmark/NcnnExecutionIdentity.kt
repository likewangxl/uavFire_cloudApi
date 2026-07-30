package com.yinxin.uavfir.benchmark

import android.content.Context
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.json.JSONObject

internal data class NcnnExecutionIdentity(
    val executingBenchmarkApkSha256: String,
    val executingRuntimeSha256: String,
    val executingBridgeSha256: String,
    val reviewedBridgeSourceSha256: String,
)

internal object NcnnExecutionIdentityContract {
    fun validate(
        trustJson: String,
        executingBenchmarkApkSha256: String,
        executingRuntimeSha256: String,
        executingBridgeSha256: String,
        reviewedSourceSha256: String,
        approvedVersion: String,
        approvedArchiveSha256: String,
    ): NcnnExecutionIdentity {
        val trust = JSONObject(trustJson)
        check(
            trust.getString("version") == approvedVersion &&
                trust.getString("packageArchiveSha256") == approvedArchiveSha256,
        ) { "Executing NCNN package is not the approved package" }
        check(trust.getString("bridgeSourceSha256") == reviewedSourceSha256) {
            "Executing NCNN bridge was not built from the reviewed current source"
        }
        check(trust.getString("runtimeSha256") == executingRuntimeSha256) {
            "NCNN trust does not identify the runtime in the executing APK"
        }
        check(trust.getString("bridgeSha256") == executingBridgeSha256) {
            "NCNN trust does not identify the bridge in the executing APK"
        }
        check(
            listOf(
                executingBenchmarkApkSha256,
                executingRuntimeSha256,
                executingBridgeSha256,
                reviewedSourceSha256,
            ).all { it.matches(SHA256) },
        ) { "Executing NCNN identity is incomplete" }
        return NcnnExecutionIdentity(
            executingBenchmarkApkSha256,
            executingRuntimeSha256,
            executingBridgeSha256,
            reviewedSourceSha256,
        )
    }

    private val SHA256 = Regex("[0-9a-f]{64}")
}

internal object NcnnExecutionIdentityLoader {
    private const val RUNTIME_ENTRY = "lib/arm64-v8a/libncnn.so"
    private const val BRIDGE_ENTRY = "lib/arm64-v8a/libfire_detector_ncnn.so"
    private const val TRUST_ENTRY = "assets/ncnn-runtime-trust.json"

    fun load(context: Context): NcnnExecutionIdentity {
        val apk = context.applicationInfo.sourceDir
        val apkSha256 = java.io.File(apk).inputStream().use(::sha256)
        return ZipFile(apk).use { zip ->
            fun entryBytes(path: String) = zip.getInputStream(
                checkNotNull(zip.getEntry(path)) { "Executing benchmark APK lacks $path" },
            )
            val runtimeSha256 = entryBytes(RUNTIME_ENTRY).use(::sha256)
            val bridgeSha256 = entryBytes(BRIDGE_ENTRY).use(::sha256)
            val trustJson = entryBytes(TRUST_ENTRY).bufferedReader().use { it.readText() }
            NcnnExecutionIdentityContract.validate(
                trustJson,
                apkSha256,
                runtimeSha256,
                bridgeSha256,
                BuildConfig.NCNN_BRIDGE_SOURCE_SHA256,
                BuildConfig.NCNN_VERSION,
                BuildConfig.NCNN_ARCHIVE_SHA256,
            )
        }
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
