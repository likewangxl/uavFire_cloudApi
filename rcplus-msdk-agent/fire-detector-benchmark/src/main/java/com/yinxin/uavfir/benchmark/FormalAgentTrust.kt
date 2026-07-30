package com.yinxin.uavfir.benchmark

import android.content.Context
import org.json.JSONObject

internal data class FormalAgentTrust(
    val apkSha256: String,
    val signingCertificateSha256: String,
) {
    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")

        fun load(context: Context): FormalAgentTrust = parse(
            context.assets.open("formal-agent-trust.json").bufferedReader().use { it.readText() },
        )

        fun parse(json: String): FormalAgentTrust {
            val root = JSONObject(json)
            check(root.keys().asSequence().toSet() == setOf(
                "packageName",
                "apkSha256",
                "signingCertificateSha256",
            )) { "Formal Agent trust asset is incomplete" }
            check(root.optString("packageName") == "com.yinxin.uavfir") {
                "Formal Agent trust asset has the wrong package"
            }
            val apkSha256 = root.optString("apkSha256")
            val signingCertificateSha256 = root.optString("signingCertificateSha256")
            check(apkSha256.matches(SHA256) && signingCertificateSha256.matches(SHA256)) {
                "Formal Agent trust asset requires APK and certificate SHA-256"
            }
            return FormalAgentTrust(apkSha256, signingCertificateSha256)
        }
    }
}
