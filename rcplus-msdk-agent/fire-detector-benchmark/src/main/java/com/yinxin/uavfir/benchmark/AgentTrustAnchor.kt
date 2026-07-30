package com.yinxin.uavfir.benchmark

import org.json.JSONObject

internal data class ApprovedAgentRelease(
    val signingCertificateSha256: String,
    val apkSha256: String,
    val versionName: String,
    val versionCode: Long,
    val buildId: String,
)

internal data class AgentTrustAnchor(
    val allowedSigningCertificates: Set<String>,
    val approvedReleases: List<ApprovedAgentRelease>,
) {
    fun requireApproved(
        signerSha256: String,
        apkSha256: String,
        versionName: String,
        versionCode: Long,
        buildId: String,
    ): ApprovedAgentRelease {
        check(allowedSigningCertificates.isNotEmpty() && approvedReleases.isNotEmpty()) {
            "Formal Agent trust anchor is unconfigured; review a release signer/APK first"
        }
        check(signerSha256 in allowedSigningCertificates) {
            "Formal Agent signer is not approved"
        }
        return approvedReleases.singleOrNull {
            it.signingCertificateSha256 == signerSha256 &&
                it.apkSha256 == apkSha256 &&
                it.versionName == versionName &&
                it.versionCode == versionCode &&
                it.buildId == buildId
        } ?: error("Formal Agent release identity is not approved")
    }

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")

        fun parse(json: String): AgentTrustAnchor {
            val root = JSONObject(json)
            check(root.getInt("schemaVersion") == 1) { "Unsupported Agent trust anchor schema" }
            check(root.getString("packageName") == "com.yinxin.uavfir") { "Wrong Agent trust package" }
            check(root.getString("healthContract") == "agent-sdk-health-v1") {
                "Wrong Agent health contract"
            }
            val signers = root.getJSONArray("allowedSigningCertificates").let { values ->
                buildSet {
                    for (index in 0 until values.length()) {
                        val value = values.getString(index)
                        check(value.matches(SHA256)) { "Invalid approved Agent signer SHA-256" }
                        add(value)
                    }
                }
            }
            val releases = root.getJSONArray("approvedReleases").let { values ->
                buildList {
                    for (index in 0 until values.length()) {
                        val item = values.getJSONObject(index)
                        val signer = item.getString("signingCertificateSha256")
                        val apk = item.getString("apkSha256")
                        val versionName = item.getString("versionName")
                        val versionCode = item.getLong("versionCode")
                        val buildId = item.getString("buildId")
                        check(signer.matches(SHA256) && apk.matches(SHA256)) {
                            "Invalid approved Agent release hashes"
                        }
                        check(
                            signer in signers &&
                                versionName.isNotBlank() &&
                                versionCode > 0 &&
                                buildId == "uavfire-agent-$versionName-$versionCode",
                        ) { "Invalid approved Agent release identity" }
                        add(ApprovedAgentRelease(signer, apk, versionName, versionCode, buildId))
                    }
                }
            }
            return AgentTrustAnchor(signers, releases)
        }
    }
}
