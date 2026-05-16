package com.yinxin.uavfir.wayline

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Downloads a KMZ from the backend wayline-agent endpoint, verifies the MD5
 * the server sent in the `X-Kmz-Md5` header (and optionally the one the
 * dispatch command supplied), then writes the bytes to [cacheDir] so the
 * MSDK [WaypointMissionExecutor.pushKmz] can ingest from a file path.
 *
 * Threading: synchronous OkHttp call — must run off the Android main thread.
 */
class WaylineKmzDownloader(
    private val httpClient: OkHttpClient,
    private val cacheDir: File,
) {

    sealed class Result {
        data class Success(val file: File, val md5: String) : Result()
        data class Failure(val reason: String) : Result()
    }

    fun download(url: String, agentToken: String, expectedMd5: String?, missionId: String): Result {
        val req = Request.Builder()
            .url(url)
            .header(HEADER_AGENT_TOKEN, agentToken)
            .get()
            .build()

        return try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return Result.Failure("http-status:${resp.code}")
                }
                val body = resp.body?.bytes() ?: return Result.Failure("empty-body")
                val serverMd5 = resp.header(HEADER_KMZ_MD5)
                val actualMd5 = md5Hex(body)

                if (serverMd5 != null && !serverMd5.equals(actualMd5, ignoreCase = true)) {
                    return Result.Failure("md5-mismatch-server:expected=$serverMd5,actual=$actualMd5")
                }
                if (expectedMd5 != null && !expectedMd5.equals(actualMd5, ignoreCase = true)) {
                    return Result.Failure("md5-mismatch-dispatch:expected=$expectedMd5,actual=$actualMd5")
                }

                if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                    return Result.Failure("cache-dir-create-failed:${cacheDir.absolutePath}")
                }
                val file = File(cacheDir, "$missionId.kmz")
                file.writeBytes(body)
                Result.Success(file, actualMd5)
            }
        } catch (e: Exception) {
            Result.Failure("io-error:${e.message ?: e::class.simpleName.orEmpty()}")
        }
    }

    companion object {
        const val HEADER_AGENT_TOKEN = "x-agent-token"
        const val HEADER_KMZ_MD5 = "X-Kmz-Md5"

        private fun md5Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("MD5").digest(bytes)
            val hex = BigInteger(1, digest).toString(16)
            return hex.padStart(32, '0')
        }
    }
}
