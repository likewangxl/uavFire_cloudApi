package com.yinxin.uavfir.firedetection

import android.content.Context
import com.yinxin.uavfir.BuildConfig
import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

sealed interface VisibleFireDetectorArmingResult {
    data class Armed(val detector: VisibleFireDetector) : VisibleFireDetectorArmingResult
    data class Failed(val failure: VisibleFireDetectorArmingFailure) : VisibleFireDetectorArmingResult
}

sealed interface VisibleFireDetectorArmingFailure {
    data object Disabled : VisibleFireDetectorArmingFailure
    data class InvalidManifest(val reason: String) : VisibleFireDetectorArmingFailure
    data class AssetReadFailed(val path: String, val reason: String) : VisibleFireDetectorArmingFailure
    data class ArtifactHashMismatch(
        val path: String,
        val expectedSha256: String,
        val actualSha256: String,
    ) : VisibleFireDetectorArmingFailure

    data class NativeLoadFailed(val reason: String) : VisibleFireDetectorArmingFailure
    data class SessionOpenFailed(val reason: String) : VisibleFireDetectorArmingFailure
}

object VisibleFireDetectorFactory {
    private const val MANIFEST_ASSET_PATH = "fire-detection/model-manifest.json"

    fun create(context: Context): VisibleFireDetectorArmingResult {
        if (!BuildConfig.VISIBLE_FIRE_DETECTION_ENABLED) {
            return VisibleFireDetectorArmingResult.Failed(VisibleFireDetectorArmingFailure.Disabled)
        }
        val manifestJson = try {
            context.assets.open(MANIFEST_ASSET_PATH).bufferedReader().use { it.readText() }
        } catch (error: Exception) {
            return VisibleFireDetectorArmingResult.Failed(
                VisibleFireDetectorArmingFailure.AssetReadFailed(
                    MANIFEST_ASSET_PATH,
                    error.message ?: error::class.java.simpleName,
                ),
            )
        }
        return createForTesting(
            enabled = true,
            manifestJson = manifestJson,
            openAsset = context.assets::open,
            stagingDirectory = File(context.cacheDir, "visible-fire-detector"),
            runtime = AndroidVisibleFireNcnnRuntime,
        )
    }

    internal fun createForTesting(
        enabled: Boolean,
        manifestJson: String,
        openAsset: (String) -> InputStream,
        stagingDirectory: File,
        runtime: VisibleFireNcnnRuntime,
    ): VisibleFireDetectorArmingResult {
        if (!enabled) {
            return VisibleFireDetectorArmingResult.Failed(VisibleFireDetectorArmingFailure.Disabled)
        }
        val manifest = try {
            VisibleFireModelManifestParser.parse(manifestJson)
        } catch (error: Exception) {
            return VisibleFireDetectorArmingResult.Failed(
                VisibleFireDetectorArmingFailure.InvalidManifest(
                    error.message ?: error::class.java.simpleName,
                ),
            )
        }

        if (!stagingDirectory.exists() && !stagingDirectory.mkdirs()) {
            return VisibleFireDetectorArmingResult.Failed(
                VisibleFireDetectorArmingFailure.AssetReadFailed(
                    "staging-directory",
                    "Unable to create ${stagingDirectory.absolutePath}",
                ),
            )
        }
        val staged = linkedMapOf<String, File>()
        manifest.artifacts.forEach { artifact ->
            val destination = File(stagingDirectory, artifact.path.substringAfterLast('/'))
            val temporary = File(stagingDirectory, ".${destination.name}.tmp")
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                openAsset(artifact.path).use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
            } catch (error: Exception) {
                temporary.delete()
                return VisibleFireDetectorArmingResult.Failed(
                    VisibleFireDetectorArmingFailure.AssetReadFailed(
                        artifact.path,
                        error.message ?: error::class.java.simpleName,
                    ),
                )
            }
            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (actualSha256 != artifact.sha256) {
                temporary.delete()
                return VisibleFireDetectorArmingResult.Failed(
                    VisibleFireDetectorArmingFailure.ArtifactHashMismatch(
                        artifact.path,
                        artifact.sha256,
                        actualSha256,
                    ),
                )
            }
            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (error: Exception) {
                temporary.delete()
                return VisibleFireDetectorArmingResult.Failed(
                    VisibleFireDetectorArmingFailure.AssetReadFailed(
                        artifact.path,
                        error.message ?: error::class.java.simpleName,
                    ),
                )
            }
            staged[artifact.path] = destination
        }

        try {
            runtime.loadLibrary()
        } catch (error: UnsatisfiedLinkError) {
            return VisibleFireDetectorArmingResult.Failed(
                VisibleFireDetectorArmingFailure.NativeLoadFailed(
                    error.message ?: error::class.java.simpleName,
                ),
            )
        } catch (error: SecurityException) {
            return VisibleFireDetectorArmingResult.Failed(
                VisibleFireDetectorArmingFailure.NativeLoadFailed(
                    error.message ?: error::class.java.simpleName,
                ),
            )
        }

        val param = staged.entries.single { it.key.endsWith(".param") }.value
        val bin = staged.entries.single { it.key.endsWith(".bin") }.value
        val handle = try {
            runtime.create(param.absolutePath, bin.absolutePath)
        } catch (error: RuntimeException) {
            return VisibleFireDetectorArmingResult.Failed(
                VisibleFireDetectorArmingFailure.SessionOpenFailed(
                    error.message ?: error::class.java.simpleName,
                ),
            )
        }
        if (handle == 0L) {
            return VisibleFireDetectorArmingResult.Failed(
                VisibleFireDetectorArmingFailure.SessionOpenFailed(
                    "NCNN rejected the verified visible-960 param/bin",
                ),
            )
        }
        return VisibleFireDetectorArmingResult.Armed(
            NcnnVisibleFireDetector(manifest, runtime, handle),
        )
    }
}
