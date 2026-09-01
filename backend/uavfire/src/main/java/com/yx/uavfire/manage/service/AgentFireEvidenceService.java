package com.yx.uavfire.manage.service;

import com.yx.uavfire.manage.model.dto.AgentFireEvidenceReceiptDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class AgentFireEvidenceService {

    private static final Pattern SAFE_DRONE = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern SAFE_EVENT = Pattern.compile("agent-[0-9a-f]{48}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final long MAX_JPEG_BYTES = 20L * 1024 * 1024;

    private final Path storageRoot;
    private final String publicBaseUrl;

    public AgentFireEvidenceService(
            @Value("${wayline-agent.fire-evidence.storage-dir:data/agent-fire-evidence}") String storageDir,
            @Value("${wayline-agent.fire-evidence.public-base-url:}") String publicBaseUrl) {
        this.storageRoot = Path.of(storageDir).toAbsolutePath().normalize();
        this.publicBaseUrl = trimTrailingSlash(publicBaseUrl);
    }

    public AgentFireEvidenceReceiptDTO store(
            String droneSn,
            String eventId,
            String expectedSha256,
            long capturedAt,
            MultipartFile jpeg) throws IOException {
        validateIdentity(droneSn, eventId, expectedSha256, capturedAt);
        if (jpeg == null || jpeg.isEmpty() || jpeg.getSize() > MAX_JPEG_BYTES) {
            throw new IllegalArgumentException("invalid-evidence-size");
        }
        byte[] bytes = jpeg.getBytes();
        if (bytes.length < 4
                || (bytes[0] & 0xff) != 0xff
                || (bytes[1] & 0xff) != 0xd8
                || (bytes[2] & 0xff) != 0xff) {
            throw new IllegalArgumentException("evidence-not-jpeg");
        }
        String actualSha256 = sha256(bytes);
        if (!actualSha256.equals(expectedSha256.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("evidence-sha256-mismatch");
        }

        Path droneDir = safeDroneDir(droneSn);
        Files.createDirectories(droneDir);
        String filename = evidenceFilename(eventId, capturedAt, actualSha256);
        Path target = droneDir.resolve(filename).normalize();
        ensureWithin(target, droneDir);
        if (!Files.exists(target)) {
            Path temp = Files.createTempFile(droneDir, eventId + "-", ".uploading");
            try {
                try (OutputStream output = Files.newOutputStream(temp)) {
                    output.write(bytes);
                    output.flush();
                }
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (FileAlreadyExistsException ignored) {
                    // A concurrent retry stored the same deterministic object first.
                } catch (AtomicMoveNotSupportedException ignored) {
                    try {
                        Files.move(temp, target);
                    } catch (FileAlreadyExistsException concurrentRetry) {
                        // The target will be verified below before success is returned.
                    }
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        }
        if (!actualSha256.equals(sha256(Files.readAllBytes(target)))) {
            throw new IllegalStateException("existing-evidence-corrupt");
        }
        return new AgentFireEvidenceReceiptDTO()
                .setEventId(eventId)
                .setStatus("stored")
                .setVisibleImageUrl(publicUrl(droneSn, filename))
                .setEvidenceSha256(actualSha256)
                .setEvidenceCapturedAt(capturedAt);
    }

    public boolean verify(
            String droneSn,
            String eventId,
            String visibleImageUrl,
            String expectedSha256,
            Long capturedAt) {
        try {
            validateIdentity(droneSn, eventId, expectedSha256, capturedAt == null ? 0L : capturedAt);
            String filename = evidenceFilename(eventId, capturedAt, expectedSha256.toLowerCase(Locale.ROOT));
            if (!publicUrl(droneSn, filename).equals(visibleImageUrl)) {
                return false;
            }
            Path file = safeDroneDir(droneSn).resolve(filename).normalize();
            ensureWithin(file, safeDroneDir(droneSn));
            return Files.isRegularFile(file)
                    && expectedSha256.equalsIgnoreCase(sha256(Files.readAllBytes(file)));
        } catch (Exception ignored) {
            return false;
        }
    }

    public Resource load(String droneSn, String filename) throws IOException {
        if (!SAFE_DRONE.matcher(droneSn).matches()
                || filename == null
                || !filename.matches("agent-[0-9a-f]{48}-[0-9]{1,19}-[0-9a-f]{64}\\.jpg")) {
            throw new IllegalArgumentException("invalid-evidence-path");
        }
        Path droneDir = safeDroneDir(droneSn);
        Path file = droneDir.resolve(filename).normalize();
        ensureWithin(file, droneDir);
        Resource resource = new UrlResource(file.toUri());
        if (!resource.exists() || !resource.isReadable()) {
            throw new IllegalArgumentException("evidence-not-found");
        }
        return resource;
    }

    private void validateIdentity(String droneSn, String eventId, String sha256, long capturedAt) {
        if (!StringUtils.hasText(droneSn) || !SAFE_DRONE.matcher(droneSn).matches()
                || !StringUtils.hasText(eventId) || !SAFE_EVENT.matcher(eventId).matches()
                || !StringUtils.hasText(sha256) || !SHA256.matcher(sha256.toLowerCase(Locale.ROOT)).matches()
                || capturedAt <= 0) {
            throw new IllegalArgumentException("invalid-evidence-metadata");
        }
    }

    private Path safeDroneDir(String droneSn) {
        if (!SAFE_DRONE.matcher(droneSn).matches()) {
            throw new IllegalArgumentException("invalid-drone-sn");
        }
        Path dir = storageRoot.resolve(droneSn).normalize();
        ensureWithin(dir, storageRoot);
        return dir;
    }

    private static void ensureWithin(Path path, Path parent) {
        if (!path.startsWith(parent)) {
            throw new IllegalArgumentException("unsafe-evidence-path");
        }
    }

    private String publicUrl(String droneSn, String filename) {
        String relative = "/manage/api/v1/dual-stream/fire-evidence/" + droneSn + "/" + filename;
        return publicBaseUrl + relative;
    }

    private static String evidenceFilename(String eventId, long capturedAt, String sha256) {
        return eventId + "-" + capturedAt + "-" + sha256 + ".jpg";
    }

    private static String trimTrailingSlash(String value) {
        if (!StringUtils.hasText(value)) return "";
        String result = value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
