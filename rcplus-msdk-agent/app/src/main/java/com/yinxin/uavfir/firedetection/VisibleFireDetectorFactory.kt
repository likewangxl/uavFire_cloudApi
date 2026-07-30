package com.yinxin.uavfir.firedetection

import android.content.Context
import com.yinxin.uavfir.BuildConfig
import java.io.File
import java.io.InputStream
import java.nio.file.CopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

sealed interface VisibleFireDetectorArmingResult {
    data class Armed(val detector: VisibleFireDetector) : VisibleFireDetectorArmingResult
    data class Failed(val failure: VisibleFireDetectorArmingFailure) : VisibleFireDetectorArmingResult
}

sealed interface VisibleFireDetectorArmingFailure {
    data object Disabled : VisibleFireDetectorArmingFailure
    data class InvalidManifest(val reason: String) : VisibleFireDetectorArmingFailure
    data class AssetIoFailed(val path: String, val reason: String) : VisibleFireDetectorArmingFailure
    data class ArtifactHashMismatch(
        val path: String,
        val expectedSha256: String,
        val actualSha256: String,
    ) : VisibleFireDetectorArmingFailure

    data class NativeLoadFailed(val reason: String) : VisibleFireDetectorArmingFailure
    data class SessionOpenFailed(val reason: String) : VisibleFireDetectorArmingFailure
}

internal fun interface VisibleFireFileMover {
    fun move(source: Path, target: Path, options: Set<CopyOption>)

    companion object {
        val SYSTEM = VisibleFireFileMover { source, target, options ->
            Files.move(source, target, *options.toTypedArray())
        }
    }
}

object VisibleFireDetectorFactory {
    private const val MANIFEST_ASSET_PATH = "fire-detection/model-manifest.json"
    private val MODEL_PUBLICATION_LOCK = ReentrantLock()

    fun create(context: Context): VisibleFireDetectorArmingResult {
        if (!BuildConfig.VISIBLE_FIRE_DETECTION_ENABLED) {
            return VisibleFireDetectorArmingResult.Failed(VisibleFireDetectorArmingFailure.Disabled)
        }
        val manifestJson = try {
            context.assets.open(MANIFEST_ASSET_PATH).bufferedReader().use { it.readText() }
        } catch (error: Exception) {
            return VisibleFireDetectorArmingResult.Failed(
                VisibleFireDetectorArmingFailure.AssetIoFailed(
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
        fileMover: VisibleFireFileMover = VisibleFireFileMover.SYSTEM,
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

        val publication = verifyAndPublishArtifacts(
            manifest = manifest,
            openAsset = openAsset,
            stagingDirectory = stagingDirectory,
            fileMover = fileMover,
        )
        if (publication is ArtifactPublicationResult.Failed) {
            return VisibleFireDetectorArmingResult.Failed(publication.failure)
        }
        val staged = (publication as ArtifactPublicationResult.Published).files

        try {
            runtime.loadLibrary()
        } catch (error: LinkageError) {
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
        } catch (error: LinkageError) {
            return VisibleFireDetectorArmingResult.Failed(
                VisibleFireDetectorArmingFailure.NativeLoadFailed(
                    error.message ?: error::class.java.simpleName,
                ),
            )
        } catch (error: SecurityException) {
            return VisibleFireDetectorArmingResult.Failed(
                VisibleFireDetectorArmingFailure.SessionOpenFailed(
                    error.message ?: error::class.java.simpleName,
                ),
            )
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

    private fun verifyAndPublishArtifacts(
        manifest: VisibleFireModelManifest,
        openAsset: (String) -> InputStream,
        stagingDirectory: File,
        fileMover: VisibleFireFileMover,
    ): ArtifactPublicationResult {
        val verificationDirectory = try {
            Files.createDirectories(stagingDirectory.toPath())
            Files.createTempDirectory(stagingDirectory.toPath(), ".verify-").toFile()
        } catch (error: Exception) {
            return ArtifactPublicationResult.Failed(
                VisibleFireDetectorArmingFailure.AssetIoFailed(
                    "staging-directory",
                    error.message ?: error::class.java.simpleName,
                ),
            )
        }
        try {
            for (artifact in manifest.artifacts) {
                val output = File(verificationDirectory, artifact.path.substringAfterLast('/'))
                val actualSha256 = try {
                    copyAndSha256(openAsset(artifact.path), output)
                } catch (error: Exception) {
                    return ArtifactPublicationResult.Failed(
                        VisibleFireDetectorArmingFailure.AssetIoFailed(
                            artifact.path,
                            error.message ?: error::class.java.simpleName,
                        ),
                    )
                }
                if (actualSha256 != artifact.sha256) {
                    return ArtifactPublicationResult.Failed(
                        VisibleFireDetectorArmingFailure.ArtifactHashMismatch(
                            artifact.path,
                            artifact.sha256,
                            actualSha256,
                        ),
                    )
                }
            }
            return MODEL_PUBLICATION_LOCK.withLock {
                publishVerifiedDirectory(
                    manifest = manifest,
                    verificationDirectory = verificationDirectory,
                    stagingDirectory = stagingDirectory,
                    fileMover = fileMover,
                )
            }
        } finally {
            deleteRecursivelyWithoutEscaping(verificationDirectory)
        }
    }

    private fun publishVerifiedDirectory(
        manifest: VisibleFireModelManifest,
        verificationDirectory: File,
        stagingDirectory: File,
        fileMover: VisibleFireFileMover,
    ): ArtifactPublicationResult {
        val modelsDirectory = File(stagingDirectory, "models")
        try {
            Files.createDirectories(modelsDirectory.toPath())
        } catch (error: Exception) {
            return ArtifactPublicationResult.Failed(
                VisibleFireDetectorArmingFailure.AssetIoFailed(
                    modelsDirectory.absolutePath,
                    error.message ?: error::class.java.simpleName,
                ),
            )
        }
        val destination = File(
            modelsDirectory,
            "${manifest.modelVersion}-${artifactSetSha256(manifest).take(24)}",
        )
        if (destination.exists()) {
            val existingValid = manifest.artifacts.all { artifact ->
                val file = File(destination, artifact.path.substringAfterLast('/'))
                if (!file.isFile) {
                    false
                } else {
                    try {
                        sha256(file) == artifact.sha256
                    } catch (_: Exception) {
                        false
                    }
                }
            }
            if (existingValid) return publishedArtifacts(manifest, destination)
            if (!deleteRecursivelyWithoutEscaping(destination) && destination.exists()) {
                return ArtifactPublicationResult.Failed(
                    VisibleFireDetectorArmingFailure.AssetIoFailed(
                        destination.absolutePath,
                        "Unable to remove corrupt visible model cache",
                    ),
                )
            }
        }

        val moveFailure = moveDirectoryWithFallback(
            source = verificationDirectory.toPath(),
            target = destination.toPath(),
            fileMover = fileMover,
        )
        if (moveFailure != null) {
            deleteRecursivelyWithoutEscaping(destination)
            return ArtifactPublicationResult.Failed(
                VisibleFireDetectorArmingFailure.AssetIoFailed(
                    destination.absolutePath,
                    moveFailure.message ?: moveFailure::class.java.simpleName,
                ),
            )
        }
        return publishedArtifacts(manifest, destination)
    }

    private fun publishedArtifacts(
        manifest: VisibleFireModelManifest,
        directory: File,
    ): ArtifactPublicationResult.Published = ArtifactPublicationResult.Published(
        manifest.artifacts.associate { artifact ->
            artifact.path to File(directory, artifact.path.substringAfterLast('/'))
        },
    )

    private fun moveDirectoryWithFallback(
        source: Path,
        target: Path,
        fileMover: VisibleFireFileMover,
    ): Exception? = try {
        fileMover.move(source, target, setOf(StandardCopyOption.ATOMIC_MOVE))
        null
    } catch (_: AtomicMoveNotSupportedException) {
        try {
            fileMover.move(source, target, setOf(StandardCopyOption.REPLACE_EXISTING))
            null
        } catch (error: Exception) {
            error
        }
    } catch (error: Exception) {
        error
    }

    private fun copyAndSha256(input: InputStream, output: File): String = input.use {
        output.outputStream().buffered().use { destination ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                destination.write(buffer, 0, count)
            }
            digest.digest().toHex()
        }
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().toHex()
    }

    private fun artifactSetSha256(manifest: VisibleFireModelManifest): String =
        MessageDigest.getInstance("SHA-256")
            .digest(
                manifest.artifacts.joinToString("\n") { "${it.path}:${it.sha256}" }.toByteArray(),
            )
            .toHex()

    private fun deleteRecursivelyWithoutEscaping(file: File): Boolean = try {
        !file.exists() || file.deleteRecursively() || !file.exists()
    } catch (_: Exception) {
        false
    }

    private sealed interface ArtifactPublicationResult {
        data class Published(val files: Map<String, File>) : ArtifactPublicationResult
        data class Failed(val failure: VisibleFireDetectorArmingFailure) : ArtifactPublicationResult
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
