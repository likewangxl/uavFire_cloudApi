package com.yinxin.uavfir.benchmark

import android.content.Context
import org.json.JSONObject

internal data class FormalAgentTrust(
    val apkSha256: String,
    val signingCertificateSha256: String,
    val versionName: String,
    val versionCode: Long,
    val buildId: String,
) {
    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")

        fun load(context: Context): FormalAgentTrust = parse(
            context.assets.open("formal-agent-trust.json").bufferedReader().use { it.readText() },
        )

        fun parse(json: String): FormalAgentTrust {
            val root = JSONObject(json)
            check(root.keys().asSequence().toSet() == setOf(
                "schemaVersion",
                "packageName",
                "apkSha256",
                "signingCertificateSha256",
                "versionName",
                "versionCode",
                "buildId",
            )) { "Formal Agent trust asset is incomplete" }
            check(root.optInt("schemaVersion") == 2) { "Unsupported Formal Agent trust schema" }
            check(root.optString("packageName") == "com.yinxin.uavfir") {
                "Formal Agent trust asset has the wrong package"
            }
            val apkSha256 = root.optString("apkSha256")
            val signingCertificateSha256 = root.optString("signingCertificateSha256")
            check(apkSha256.matches(SHA256) && signingCertificateSha256.matches(SHA256)) {
                "Formal Agent trust asset requires APK and certificate SHA-256"
            }
            val versionName = root.optString("versionName")
            val versionCode = root.optLong("versionCode")
            val buildId = root.optString("buildId")
            check(versionName.isNotBlank() && versionCode > 0 && buildId == "uavfire-agent-$versionName-$versionCode") {
                "Formal Agent trust asset has an invalid release identity"
            }
            return FormalAgentTrust(
                apkSha256,
                signingCertificateSha256,
                versionName,
                versionCode,
                buildId,
            )
        }
    }
}
