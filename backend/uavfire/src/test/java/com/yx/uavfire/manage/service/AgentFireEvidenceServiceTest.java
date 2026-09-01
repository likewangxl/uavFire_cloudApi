package com.yx.uavfire.manage.service;

import com.yx.uavfire.manage.model.dto.AgentFireEvidenceReceiptDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentFireEvidenceServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void storeIsIdempotentAndVerifyChecksUrlHashAndCaptureTime() throws Exception {
        AgentFireEvidenceService service = new AgentFireEvidenceService(tempDir.toString(), "https://api.example.test");
        byte[] jpeg = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1, 2, (byte) 0xd9};
        String hash = hex(MessageDigest.getInstance("SHA-256").digest(jpeg));
        String eventId = "agent-0123456789abcdef0123456789abcdef0123456789abcdef";
        MockMultipartFile file = new MockMultipartFile("file", "event.jpg", "image/jpeg", jpeg);

        AgentFireEvidenceReceiptDTO first = service.store("DRONE-001", eventId, hash, 1_788_141_600_000L, file);
        AgentFireEvidenceReceiptDTO second = service.store("DRONE-001", eventId, hash, 1_788_141_600_000L, file);

        assertEquals(first.getVisibleImageUrl(), second.getVisibleImageUrl());
        assertTrue(service.verify(
                "DRONE-001", eventId, first.getVisibleImageUrl(), hash, 1_788_141_600_000L));
        assertFalse(service.verify(
                "DRONE-001", eventId, first.getVisibleImageUrl(), "a".repeat(64), 1_788_141_600_000L));
    }

    @Test
    void storeRejectsHashMismatchBeforePublishingEvidence() throws Exception {
        AgentFireEvidenceService service = new AgentFireEvidenceService(tempDir.toString(), "");
        byte[] jpeg = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1};
        MockMultipartFile file = new MockMultipartFile("file", "event.jpg", "image/jpeg", jpeg);

        assertThrows(IllegalArgumentException.class, () -> service.store(
                "DRONE-001",
                "agent-0123456789abcdef0123456789abcdef0123456789abcdef",
                "a".repeat(64),
                1_788_141_600_000L,
                file));
    }

    private String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte b : bytes) value.append(String.format("%02x", b & 0xff));
        return value.toString();
    }
}
