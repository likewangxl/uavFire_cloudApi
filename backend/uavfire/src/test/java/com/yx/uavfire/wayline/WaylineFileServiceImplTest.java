package com.yx.uavfire.wayline;

import com.yx.uavfire.component.oss.model.OssConfiguration;
import com.yx.uavfire.component.oss.service.impl.OssServiceContext;
import com.yx.uavfire.wayline.dao.IWaylineFileMapper;
import com.yx.uavfire.wayline.model.dto.PublishedWaylineCreateDTO;
import com.yx.uavfire.wayline.model.dto.PublishedWaylineFileDTO;
import com.yx.uavfire.wayline.model.entity.WaylineFileEntity;
import com.yx.uavfire.wayline.service.impl.WaylineFileServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WaylineFileServiceImplTest {

    @BeforeEach
    void resetOssConfiguration() {
        OssConfiguration.enable = true;
    }

    @Test
    void createPublishedWaylineShouldUploadValidKmzAndPersistFormalRow() throws IOException {
        WaylineFileServiceImpl service = new WaylineFileServiceImpl();
        IWaylineFileMapper mapper = mock(IWaylineFileMapper.class);
        OssServiceContext ossService = mock(OssServiceContext.class);
        ReflectionTestUtils.setField(service, "mapper", mapper);
        ReflectionTestUtils.setField(service, "ossService", ossService);
        OssConfiguration.bucket = "bucket-001";
        OssConfiguration.objectDirPrefix = "wayline";

        when(mapper.insert(any(WaylineFileEntity.class))).thenAnswer(invocation -> {
            WaylineFileEntity entity = invocation.getArgument(0);
            entity.setId(1);
            return 1;
        });

        PublishedWaylineFileDTO result = service.createPublishedWayline("workspace-001", PublishedWaylineCreateDTO.builder()
                .filename("Survey A.kmz")
                .objectKey("wayline/pw-001.kmz")
                .username("alice")
                .content(buildMinimalKmz())
                .build());

        assertNotNull(result);
        assertEquals("Survey A", result.getName());
        assertEquals("wayline/pw-001.kmz", result.getObjectKey());
        assertNotNull(result.getWaylineId());
        verify(ossService).putObject(org.mockito.ArgumentMatchers.eq("bucket-001"),
                org.mockito.ArgumentMatchers.eq("wayline/pw-001.kmz"), any(ByteArrayInputStream.class));
        ArgumentCaptor<WaylineFileEntity> entityCaptor = ArgumentCaptor.forClass(WaylineFileEntity.class);
        verify(mapper).insert(entityCaptor.capture());
        assertAll(
                () -> assertEquals("workspace-001", entityCaptor.getValue().getWorkspaceId()),
                () -> assertEquals("Survey A", entityCaptor.getValue().getName()),
                () -> assertEquals("alice", entityCaptor.getValue().getUsername()),
                () -> assertEquals("wayline/pw-001.kmz", entityCaptor.getValue().getObjectKey()));
        assertAll(
                () -> assertTrue(readZipEntry(buildMinimalKmz(), "wpmz/template.kml").contains("<wpml:templateType>waypoint</wpml:templateType>")),
                () -> assertTrue(readZipEntry(buildMinimalKmz(), "wpmz/waylines.wpml").contains("<wpml:waylineCoordinateSysParam/>")));
    }

    @Test
    void createPublishedWaylineShouldUseLocalStorageWhenOssIsDisabled() throws Exception {
        WaylineFileServiceImpl service = new WaylineFileServiceImpl();
        IWaylineFileMapper mapper = mock(IWaylineFileMapper.class);
        OssServiceContext ossService = mock(OssServiceContext.class);
        ReflectionTestUtils.setField(service, "mapper", mapper);
        ReflectionTestUtils.setField(service, "ossService", ossService);
        Path localRoot = Files.createTempDirectory("planned-wayline-local-oss");
        ReflectionTestUtils.setField(service, "localObjectStorageRoot", localRoot);
        OssConfiguration.enable = false;
        OssConfiguration.bucket = "bucket-001";
        OssConfiguration.objectDirPrefix = "wayline";

        when(mapper.insert(any(WaylineFileEntity.class))).thenAnswer(invocation -> {
            WaylineFileEntity entity = invocation.getArgument(0);
            entity.setId(1);
            return 1;
        });

        PublishedWaylineFileDTO result = service.createPublishedWayline("workspace-001", PublishedWaylineCreateDTO.builder()
                .filename("Survey A.kmz")
                .objectKey("wayline/pw-local.kmz")
                .username("alice")
                .content(buildMinimalKmz())
                .build());

        assertEquals("wayline/pw-local.kmz", result.getObjectKey());
        Path storedFile = localRoot.resolve("bucket-001").resolve("wayline/pw-local.kmz");
        assertTrue(Files.exists(storedFile));
        verify(ossService, never()).putObject(any(), any(), any(ByteArrayInputStream.class));

        ArgumentCaptor<WaylineFileEntity> entityCaptor = ArgumentCaptor.forClass(WaylineFileEntity.class);
        verify(mapper).insert(entityCaptor.capture());
        when(mapper.selectOne(any())).thenReturn(entityCaptor.getValue());
        URL url = service.getObjectUrl("workspace-001", result.getWaylineId());
        assertEquals(storedFile.toUri().toURL(), url);
        try (InputStream input = service.getObject("bucket-001", "wayline/pw-local.kmz")) {
            assertTrue(input.readAllBytes().length > 0);
        }
        assertTrue(service.deleteByObjectKey("bucket-001", "wayline/pw-local.kmz"));
    }

    @Test
    void createPublishedWaylineShouldFailWhenKmzIsInvalid() {
        WaylineFileServiceImpl service = new WaylineFileServiceImpl();
        ReflectionTestUtils.setField(service, "mapper", mock(IWaylineFileMapper.class));
        ReflectionTestUtils.setField(service, "ossService", mock(OssServiceContext.class));
        OssConfiguration.bucket = "bucket-001";

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> service.createPublishedWayline("workspace-001", PublishedWaylineCreateDTO.builder()
                        .filename("broken.kmz")
                        .objectKey("wayline/broken.kmz")
                        .username("alice")
                        .content("not-a-kmz".getBytes(StandardCharsets.UTF_8))
                        .build()));

        assertEquals("The file format is incorrect.", thrown.getMessage());
    }

    @Test
    void createPublishedWaylineShouldFailWhenWaylinesWpmlIsMissing() throws IOException {
        WaylineFileServiceImpl service = new WaylineFileServiceImpl();
        IWaylineFileMapper mapper = mock(IWaylineFileMapper.class);
        OssServiceContext ossService = mock(OssServiceContext.class);
        ReflectionTestUtils.setField(service, "mapper", mapper);
        ReflectionTestUtils.setField(service, "ossService", ossService);
        OssConfiguration.bucket = "bucket-001";

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> service.createPublishedWayline("workspace-001", PublishedWaylineCreateDTO.builder()
                        .filename("broken.kmz")
                        .objectKey("wayline/broken.kmz")
                        .username("alice")
                        .content(buildTemplateOnlyKmz())
                        .build()));

        assertEquals("The file format is incorrect.", thrown.getMessage());
        verify(ossService, never()).putObject(any(), any(), any(ByteArrayInputStream.class));
        verify(mapper, never()).insert(any(WaylineFileEntity.class));
    }

    @Test
    void createPublishedWaylineShouldCleanupUploadedObjectWhenInsertThrows() throws IOException {
        WaylineFileServiceImpl service = new WaylineFileServiceImpl();
        IWaylineFileMapper mapper = mock(IWaylineFileMapper.class);
        OssServiceContext ossService = mock(OssServiceContext.class);
        ReflectionTestUtils.setField(service, "mapper", mapper);
        ReflectionTestUtils.setField(service, "ossService", ossService);
        OssConfiguration.bucket = "bucket-001";
        when(mapper.insert(any(WaylineFileEntity.class))).thenThrow(new RuntimeException("db insert failed"));
        when(ossService.deleteObject(eq("bucket-001"), eq("wayline/pw-001.kmz"))).thenReturn(true);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> service.createPublishedWayline("workspace-001", PublishedWaylineCreateDTO.builder()
                        .filename("Survey A.kmz")
                        .objectKey("wayline/pw-001.kmz")
                        .username("alice")
                        .content(buildMinimalKmz())
                        .build()));

        assertEquals("db insert failed", thrown.getMessage());
        verify(ossService).putObject(eq("bucket-001"), eq("wayline/pw-001.kmz"), any(ByteArrayInputStream.class));
        verify(ossService).deleteObject("bucket-001", "wayline/pw-001.kmz");
    }

    @Test
    void createPublishedWaylineShouldSurfaceCleanupFailureWhenInsertThrows() throws IOException {
        WaylineFileServiceImpl service = new WaylineFileServiceImpl();
        IWaylineFileMapper mapper = mock(IWaylineFileMapper.class);
        OssServiceContext ossService = mock(OssServiceContext.class);
        ReflectionTestUtils.setField(service, "mapper", mapper);
        ReflectionTestUtils.setField(service, "ossService", ossService);
        OssConfiguration.bucket = "bucket-001";
        when(mapper.insert(any(WaylineFileEntity.class))).thenThrow(new RuntimeException("db insert failed"));
        when(ossService.deleteObject(eq("bucket-001"), eq("wayline/pw-001.kmz"))).thenReturn(false);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.createPublishedWayline("workspace-001", PublishedWaylineCreateDTO.builder()
                        .filename("Survey A.kmz")
                        .objectKey("wayline/pw-001.kmz")
                        .username("alice")
                        .content(buildMinimalKmz())
                        .build()));

        assertEquals("Failed to cleanup uploaded published wayline object.", thrown.getMessage());
        assertInstanceOf(RuntimeException.class, thrown.getCause());
        verify(ossService).deleteObject("bucket-001", "wayline/pw-001.kmz");
    }

    private static byte[] buildMinimalKmz() throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
            zipOutputStream.putNextEntry(new ZipEntry("wpmz/template.kml"));
            zipOutputStream.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<kml xmlns:wpml=\"http://www.dji.com/wpmz/1.0.6\">"
                    + "<Document>"
                    + "<wpml:templateType>waypoint</wpml:templateType>"
                    + "<wpml:droneInfo><wpml:droneEnumValue>67</wpml:droneEnumValue><wpml:droneSubEnumValue>1</wpml:droneSubEnumValue></wpml:droneInfo>"
                    + "<wpml:payloadInfo><wpml:payloadEnumValue>53</wpml:payloadEnumValue><wpml:payloadSubEnumValue>0</wpml:payloadSubEnumValue></wpml:payloadInfo>"
                    + "</Document>"
                    + "</kml>").getBytes(StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();
            zipOutputStream.putNextEntry(new ZipEntry("wpmz/waylines.wpml"));
            zipOutputStream.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<kml xmlns:wpml=\"http://www.dji.com/wpmz/1.0.6\">"
                    + "<Document><name>Survey A</name><wpml:waylineCoordinateSysParam/>"
                    + "<Folder><Placemark><name>1</name><Point><coordinates>120.0,30.1,80.0</coordinates></Point></Placemark></Folder>"
                    + "</Document>"
                    + "</kml>").getBytes(StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();
        }
        return outputStream.toByteArray();
    }

    private static byte[] buildTemplateOnlyKmz() throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
            zipOutputStream.putNextEntry(new ZipEntry("wpmz/template.kml"));
            zipOutputStream.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<kml xmlns:wpml=\"http://www.dji.com/wpmz/1.0.6\">"
                    + "<Document>"
                    + "<wpml:templateType>waypoint</wpml:templateType>"
                    + "<wpml:droneInfo><wpml:droneEnumValue>67</wpml:droneEnumValue><wpml:droneSubEnumValue>1</wpml:droneSubEnumValue></wpml:droneInfo>"
                    + "<wpml:payloadInfo><wpml:payloadEnumValue>53</wpml:payloadEnumValue><wpml:payloadSubEnumValue>0</wpml:payloadSubEnumValue></wpml:payloadInfo>"
                    + "</Document>"
                    + "</kml>").getBytes(StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();
        }
        return outputStream.toByteArray();
    }

    private static String readZipEntry(byte[] content, String entryName) throws IOException {
        try (ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(content), StandardCharsets.UTF_8)) {
            ZipEntry entry = zipInputStream.getNextEntry();
            while (entry != null) {
                if (entryName.equals(entry.getName())) {
                    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                    zipInputStream.transferTo(outputStream);
                    return outputStream.toString(StandardCharsets.UTF_8);
                }
                entry = zipInputStream.getNextEntry();
            }
        }
        throw new AssertionError("Missing zip entry: " + entryName);
    }
}
