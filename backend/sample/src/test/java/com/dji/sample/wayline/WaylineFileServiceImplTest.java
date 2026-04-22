package com.dji.sample.wayline;

import com.dji.sample.component.oss.model.OssConfiguration;
import com.dji.sample.component.oss.service.impl.OssServiceContext;
import com.dji.sample.wayline.dao.IWaylineFileMapper;
import com.dji.sample.wayline.model.dto.PublishedWaylineCreateDTO;
import com.dji.sample.wayline.model.dto.PublishedWaylineFileDTO;
import com.dji.sample.wayline.model.entity.WaylineFileEntity;
import com.dji.sample.wayline.service.impl.WaylineFileServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WaylineFileServiceImplTest {

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

    private static byte[] buildMinimalKmz() throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
            zipOutputStream.putNextEntry(new ZipEntry("wpmz/template.kml"));
            zipOutputStream.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<kml xmlns:wpml=\"http://www.dji.com/wpmz/1.0.2\">"
                    + "<Document>"
                    + "<wpml:templateType>waypoint</wpml:templateType>"
                    + "<wpml:droneInfo><wpml:droneEnumValue>67</wpml:droneEnumValue><wpml:droneSubEnumValue>1</wpml:droneSubEnumValue></wpml:droneInfo>"
                    + "<wpml:payloadInfo><wpml:payloadEnumValue>53</wpml:payloadEnumValue><wpml:payloadSubEnumValue>0</wpml:payloadSubEnumValue></wpml:payloadInfo>"
                    + "</Document>"
                    + "</kml>").getBytes(StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();
            zipOutputStream.putNextEntry(new ZipEntry("wpmz/waylines.wpml"));
            zipOutputStream.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<kml xmlns:wpml=\"http://www.dji.com/wpmz/1.0.2\">"
                    + "<Document><wpml:waylineCoordinateSysParam/></Document>"
                    + "</kml>").getBytes(StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();
        }
        return outputStream.toByteArray();
    }
}
