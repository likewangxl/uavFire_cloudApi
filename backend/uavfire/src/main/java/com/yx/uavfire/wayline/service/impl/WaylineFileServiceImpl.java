package com.yx.uavfire.wayline.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yx.uavfire.component.oss.model.OssConfiguration;
import com.yx.uavfire.component.oss.service.impl.OssServiceContext;
import com.yx.uavfire.wayline.dao.IWaylineFileMapper;
import com.yx.uavfire.wayline.model.dto.KmzFileProperties;
import com.yx.uavfire.wayline.model.dto.PublishedWaylineCreateDTO;
import com.yx.uavfire.wayline.model.dto.PublishedWaylineFileDTO;
import com.yx.uavfire.wayline.model.dto.WaylineFileDTO;
import com.yx.uavfire.wayline.model.entity.WaylineFileEntity;
import com.yx.uavfire.wayline.service.IWaylineFileService;
import com.dji.sdk.cloudapi.device.DeviceDomainEnum;
import com.dji.sdk.cloudapi.device.DeviceEnum;
import com.dji.sdk.cloudapi.device.DeviceSubTypeEnum;
import com.dji.sdk.cloudapi.device.DeviceTypeEnum;
import com.dji.sdk.cloudapi.wayline.GetWaylineListRequest;
import com.dji.sdk.cloudapi.wayline.GetWaylineListResponse;
import com.dji.sdk.cloudapi.wayline.WaylineTypeEnum;
import com.dji.sdk.common.Pagination;
import com.dji.sdk.common.PaginationData;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Node;
import org.dom4j.io.SAXReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.yx.uavfire.wayline.model.dto.KmzFileProperties.WAYLINE_FILE_SUFFIX;

/**
 * @author sean
 * @version 0.3
 * @date 2021/12/22
 */
@Service
@Transactional
public class WaylineFileServiceImpl implements IWaylineFileService {

    private Path localObjectStorageRoot = Paths.get(System.getProperty("user.dir"), "target", "local-oss");

    @Autowired
    private IWaylineFileMapper mapper;

    @Autowired
    private OssServiceContext ossService;

    @Override
    public PaginationData<GetWaylineListResponse> getWaylinesByParam(String workspaceId, GetWaylineListRequest param) {
        // Paging Query
        Page<WaylineFileEntity> page = mapper.selectPage(
                new Page<WaylineFileEntity>(param.getPage(), param.getPageSize()),
                new LambdaQueryWrapper<WaylineFileEntity>()
                        .eq(WaylineFileEntity::getWorkspaceId, workspaceId)
                        .eq(Objects.nonNull(param.getFavorited()), WaylineFileEntity::getFavorited, param.getFavorited())
                        .and(param.getTemplateType() != null, wrapper ->  {
                                for (WaylineTypeEnum type : param.getTemplateType()) {
                                    wrapper.like(WaylineFileEntity::getTemplateTypes, type.getValue()).or();
                                }
                        })
                        .and(param.getPayloadModelKey() != null, wrapper ->  {
                                for (DeviceEnum type : param.getPayloadModelKey()) {
                                    wrapper.like(WaylineFileEntity::getPayloadModelKeys, type.getType()).or();
                                }
                        })
                        .and(param.getDroneModelKeys() != null, wrapper ->  {
                                for (DeviceEnum type : param.getDroneModelKeys()) {
                                    wrapper.eq(WaylineFileEntity::getDroneModelKey, type.getType()).or();
                                }
                        })
                        .like(Objects.nonNull(param.getKey()), WaylineFileEntity::getName, param.getKey())
                        // There is a risk of SQL injection
                        .last(Objects.nonNull(param.getOrderBy()), " order by " + param.getOrderBy().toString()));

        // Wrap the results of a paging query into a custom paging object.
        List<GetWaylineListResponse> records = page.getRecords()
                .stream()
                .map(this::entityConvertToDTO)
                .collect(Collectors.toList());

        return new PaginationData<>(records, new Pagination(page.getCurrent(), page.getSize(), page.getTotal()));
    }

    @Override
    public Optional<GetWaylineListResponse> getWaylineByWaylineId(String workspaceId, String waylineId) {
        return Optional.ofNullable(
                this.entityConvertToDTO(
                        mapper.selectOne(
                                new LambdaQueryWrapper<WaylineFileEntity>()
                                    .eq(WaylineFileEntity::getWorkspaceId, workspaceId)
                                    .eq(WaylineFileEntity::getWaylineId, waylineId))));
    }

    @Override
    public URL getObjectUrl(String workspaceId, String waylineId) throws SQLException {
        Optional<GetWaylineListResponse> waylineOpt = this.getWaylineByWaylineId(workspaceId, waylineId);
        if (waylineOpt.isEmpty()) {
            throw new SQLException(waylineId + " does not exist.");
        }
        if (!OssConfiguration.enable) {
            try {
                return resolveLocalObjectPath(OssConfiguration.bucket, waylineOpt.get().getObjectKey()).toUri().toURL();
            } catch (IOException e) {
                throw new SQLException("Failed to get local wayline file URL.", e);
            }
        }
        return ossService.getObjectUrl(OssConfiguration.bucket, waylineOpt.get().getObjectKey());
    }

    @Override
    public PublishedWaylineFileDTO createPublishedWayline(String workspaceId, PublishedWaylineCreateDTO param) {
        if (param == null || param.getContent() == null || param.getContent().length == 0) {
            throw new IllegalArgumentException("Published wayline content is required.");
        }

        Optional<WaylineFileDTO> waylineFileOpt = validKmzBytes(param.getFilename(), param.getContent());
        if (waylineFileOpt.isEmpty()) {
            throw new RuntimeException("The file format is incorrect.");
        }

        WaylineFileDTO waylineFile = waylineFileOpt.get();
        waylineFile.setObjectKey(param.getObjectKey());
        waylineFile.setUsername(param.getUsername());

        try {
            if (OssConfiguration.enable) {
                ossService.putObject(OssConfiguration.bucket, param.getObjectKey(), new ByteArrayInputStream(param.getContent()));
            } else {
                putLocalObject(OssConfiguration.bucket, param.getObjectKey(), param.getContent());
            }
        } catch (RuntimeException | IOException e) {
            throw new IllegalStateException("Failed to store published wayline file.", e);
        }

        WaylineFileEntity file = dtoConvertToEntity(waylineFile);
        file.setWaylineId(UUID.randomUUID().toString());
        file.setWorkspaceId(workspaceId);
        int inserted;
        try {
            inserted = mapper.insert(file);
        } catch (RuntimeException e) {
            cleanupUploadedObject(param.getObjectKey());
            throw e;
        }
        if (inserted <= 0) {
            cleanupUploadedObject(param.getObjectKey());
            throw new IllegalStateException("Failed to create published wayline file.");
        }

        return PublishedWaylineFileDTO.builder()
                .waylineId(file.getWaylineId())
                .name(file.getName())
                .objectKey(file.getObjectKey())
                .build();
    }

    public InputStream getObject(String bucket, String objectKey) throws IOException {
        if (OssConfiguration.enable) {
            return ossService.getObject(bucket, objectKey);
        }
        return Files.newInputStream(resolveLocalObjectPath(bucket, objectKey));
    }

    @Override
    public Integer saveWaylineFile(String workspaceId, WaylineFileDTO metadata) {
        WaylineFileEntity file = this.dtoConvertToEntity(metadata);
        file.setWaylineId(UUID.randomUUID().toString());
        file.setWorkspaceId(workspaceId);

        if (!StringUtils.hasText(file.getSign())) {
            try (InputStream object = ossService.getObject(OssConfiguration.bucket, metadata.getObjectKey())) {
                if (object.available() == 0) {
                    throw new RuntimeException("The file " + metadata.getObjectKey() +
                            " does not exist in the bucket[" + OssConfiguration.bucket + "].");
                }
                file.setSign(DigestUtils.md5DigestAsHex(object));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        int insertId = mapper.insert(file);
        return insertId > 0 ? file.getId() : insertId;
    }

    @Override
    public Boolean markFavorite(String workspaceId, List<String> waylineIds, Boolean isFavorite) {
        if (waylineIds.isEmpty()) {
            return false;
        }
        if (isFavorite == null) {
            return true;
        }
        return mapper.update(null, new LambdaUpdateWrapper<WaylineFileEntity>()
                .set(WaylineFileEntity::getFavorited, isFavorite)
                .eq(WaylineFileEntity::getWorkspaceId, workspaceId)
                .in(WaylineFileEntity::getWaylineId, waylineIds)) > 0;
    }

    @Override
    public List<String> getDuplicateNames(String workspaceId, List<String> names) {
        return mapper.selectList(new LambdaQueryWrapper<WaylineFileEntity>()
                .eq(WaylineFileEntity::getWorkspaceId, workspaceId)
                .in(WaylineFileEntity::getName, names))
                .stream()
                .map(WaylineFileEntity::getName)
                .collect(Collectors.toList());
    }

    @Override
    public Boolean deleteByWaylineId(String workspaceId, String waylineId) {
        Optional<GetWaylineListResponse> waylineOpt = this.getWaylineByWaylineId(workspaceId, waylineId);
        if (waylineOpt.isEmpty()) {
            return true;
        }
        GetWaylineListResponse wayline = waylineOpt.get();
        boolean isDel = mapper.delete(new LambdaUpdateWrapper<WaylineFileEntity>()
                    .eq(WaylineFileEntity::getWorkspaceId, workspaceId)
                    .eq(WaylineFileEntity::getWaylineId, waylineId))
                > 0;
        if (!isDel) {
            return false;
        }
        return deleteByObjectKey(OssConfiguration.bucket, wayline.getObjectKey());
    }

    @Override
    public void importKmzFile(MultipartFile file, String workspaceId, String creator) {
        try {
            byte[] content = file.getBytes();
            Optional<WaylineFileDTO> waylineFileOpt = validKmzBytes(file.getOriginalFilename(), content);
            if (waylineFileOpt.isEmpty()) {
                throw new RuntimeException("The file format is incorrect.");
            }
            WaylineFileDTO waylineFile = waylineFileOpt.get();
            waylineFile.setUsername(creator);

            ossService.putObject(OssConfiguration.bucket, waylineFile.getObjectKey(), new ByteArrayInputStream(content));
            this.saveWaylineFile(workspaceId, waylineFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Optional<WaylineFileDTO> validKmzBytes(String filename, byte[] content) {
        if (Objects.nonNull(filename) && !filename.endsWith(WAYLINE_FILE_SUFFIX)) {
            throw new RuntimeException("The file format is incorrect.");
        }
        try {
            Map<String, byte[]> entries = unzipEntries(content);
            String templatePath = KmzFileProperties.FILE_DIR_FIRST + "/" + KmzFileProperties.FILE_DIR_SECOND_TEMPLATE;
            String waylinesPath = KmzFileProperties.FILE_DIR_FIRST + "/" + KmzFileProperties.FILE_DIR_SECOND_WAYLINES;
            if (!entries.containsKey(templatePath) || !entries.containsKey(waylinesPath)) {
                return Optional.empty();
            }

            Document templateDocument = readXml(entries.get(templatePath));
            Document waylinesDocument = readXml(entries.get(waylinesPath));

            Node droneNode = templateDocument.selectSingleNode("//" + KmzFileProperties.TAG_WPML_PREFIX + KmzFileProperties.TAG_DRONE_INFO);
            Node payloadNode = templateDocument.selectSingleNode("//" + KmzFileProperties.TAG_WPML_PREFIX + KmzFileProperties.TAG_PAYLOAD_INFO);
            if (Objects.isNull(droneNode) || Objects.isNull(payloadNode)) {
                throw new RuntimeException("The file format is incorrect.");
            }

            // 注意：waylineCoordinateSysParam 只应该在 template.kml 里出现，不在 waylines.wpml。
            // Pilot 2 真机导出 (m4t_probe.kmz) 实测 waylines.wpml 不含该节点；若写入此节点会让
            // MSDK pushKMZFileToAircraft 报 GENERATE_MISSION_FILE_FAILED。
            // <kml> 设为默认命名空间，Placemark/coordinates 继承该命名空间；dom4j XPath 中无前缀名
            // 只匹配「无命名空间」元素，必须用 local-name() 兜底。
            List<Node> placemarkNodes = waylinesDocument.selectNodes("//*[local-name()='Placemark']");
            if (placemarkNodes.isEmpty()) {
                throw new RuntimeException("The file format is incorrect.");
            }
            boolean hasCoordinates = placemarkNodes.stream()
                    .map(node -> node.selectSingleNode(".//*[local-name()='coordinates']"))
                    .anyMatch(Objects::nonNull);
            if (!hasCoordinates) {
                throw new RuntimeException("The file format is incorrect.");
            }

            // M4 系列 drone 本体在 KMZ 离线导出里 droneEnumValue=100，但 Cloud API runtime 是 99；
            // DeviceTypeEnum 只注册了 99 (M4_SERIES)，碰到 100 要先归一回 99 再 find，否则抛 CloudSDKException
            // 让外层 @Transactional 回滚。详见 memory: m4-series-type-code-split。
            int droneEnumRaw = Integer.parseInt(droneNode.valueOf(KmzFileProperties.TAG_WPML_PREFIX + KmzFileProperties.TAG_DRONE_ENUM_VALUE));
            DeviceTypeEnum type = DeviceTypeEnum.find(droneEnumRaw == 100 ? DeviceTypeEnum.M4_SERIES.getType() : droneEnumRaw);
            DeviceSubTypeEnum subType = DeviceSubTypeEnum.find(Integer.parseInt(droneNode.valueOf(KmzFileProperties.TAG_WPML_PREFIX + KmzFileProperties.TAG_DRONE_SUB_ENUM_VALUE)));
            int payloadEnumRaw = Integer.parseInt(payloadNode.valueOf(KmzFileProperties.TAG_WPML_PREFIX + KmzFileProperties.TAG_PAYLOAD_ENUM_VALUE));
            DeviceSubTypeEnum payloadSubType = DeviceSubTypeEnum.find(Integer.parseInt(payloadNode.valueOf(KmzFileProperties.TAG_WPML_PREFIX + KmzFileProperties.TAG_PAYLOAD_SUB_ENUM_VALUE)));
            String templateType = templateDocument.valueOf("//" + KmzFileProperties.TAG_WPML_PREFIX + KmzFileProperties.TAG_TEMPLATE_TYPE);
            DeviceEnum droneDevice = DeviceEnum.find(DeviceDomainEnum.DRONE, type, subType);
            DeviceEnum payloadDevice = resolveKmzPayloadDevice(droneDevice, payloadEnumRaw, payloadSubType);

            return Optional.of(WaylineFileDTO.builder()
                    .droneModelKey(droneDevice.getDevice())
                    .payloadModelKeys(List.of(payloadDevice.getDevice()))
                    .objectKey(buildObjectKey(filename))
                    .name(filename.substring(0, filename.lastIndexOf(WAYLINE_FILE_SUFFIX)))
                    .sign(DigestUtils.md5DigestAsHex(content))
                    .templateTypes(List.of(resolveKmzTemplateTypeValue(templateType)))
                    .favorited(Boolean.FALSE)
                    .build());
        } catch (IOException | DocumentException e) {
            e.printStackTrace();
        }
        return Optional.empty();
    }

    private DeviceEnum resolveKmzPayloadDevice(DeviceEnum droneDevice, int payloadEnumRaw, DeviceSubTypeEnum payloadSubType) {
        if ((DeviceEnum.M4E == droneDevice || DeviceEnum.M4T == droneDevice)
                && payloadEnumRaw == 89
                && DeviceSubTypeEnum.ZERO == payloadSubType) {
            return DeviceEnum.M4T_CAMERA;
        }
        DeviceTypeEnum payloadType = DeviceTypeEnum.find(payloadEnumRaw);
        return DeviceEnum.find(DeviceDomainEnum.PAYLOAD, payloadType, payloadSubType);
    }

    private int resolveKmzTemplateTypeValue(String templateType) {
        if (!StringUtils.hasText(templateType)) {
            return WaylineTypeEnum.WAYPOINT.getValue();
        }
        try {
            return WaylineTypeEnum.find(templateType).getValue();
        } catch (RuntimeException ignored) {
            return WaylineTypeEnum.WAYPOINT.getValue();
        }
    }

    private Map<String, byte[]> unzipEntries(byte[] content) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream unzipFile = new ZipInputStream(new ByteArrayInputStream(content), StandardCharsets.UTF_8)) {
            ZipEntry nextEntry = unzipFile.getNextEntry();
            while (Objects.nonNull(nextEntry)) {
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                unzipFile.transferTo(outputStream);
                entries.put(nextEntry.getName(), outputStream.toByteArray());
                nextEntry = unzipFile.getNextEntry();
            }
        }
        return entries;
    }

    private Document readXml(byte[] xmlBytes) throws DocumentException {
        SAXReader reader = new SAXReader();
        Document document = reader.read(new ByteArrayInputStream(xmlBytes));
        if (!StandardCharsets.UTF_8.name().equals(document.getXMLEncoding())) {
            throw new RuntimeException("The file encoding format is incorrect.");
        }
        return document;
    }

    private String buildObjectKey(String filename) {
        if (!StringUtils.hasText(OssConfiguration.objectDirPrefix)) {
            return filename;
        }
        String prefix = OssConfiguration.objectDirPrefix.endsWith("/")
                ? OssConfiguration.objectDirPrefix.substring(0, OssConfiguration.objectDirPrefix.length() - 1)
                : OssConfiguration.objectDirPrefix;
        return prefix + "/" + filename;
    }

    public boolean deleteByObjectKey(String bucket, String objectKey) {
        if (OssConfiguration.enable) {
            return ossService.deleteObject(bucket, objectKey);
        }
        try {
            return Files.deleteIfExists(resolveLocalObjectPath(bucket, objectKey));
        } catch (IOException e) {
            return false;
        }
    }

    private void putLocalObject(String bucket, String objectKey, byte[] content) throws IOException {
        Path target = resolveLocalObjectPath(bucket, objectKey);
        Files.createDirectories(target.getParent());
        Files.write(target, content);
    }

    private Path resolveLocalObjectPath(String bucket, String objectKey) throws IOException {
        String safeBucket = StringUtils.hasText(bucket) ? bucket : "local";
        Path root = localObjectStorageRoot.resolve(safeBucket).normalize();
        Path target = root.resolve(objectKey).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("Illegal object key.");
        }
        return target;
    }

    private void cleanupUploadedObject(String objectKey) {
        try {
            boolean deleted = deleteByObjectKey(OssConfiguration.bucket, objectKey);
            if (!deleted) {
                throw new IllegalStateException("Failed to cleanup uploaded published wayline object.");
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to cleanup uploaded published wayline object.", e);
        }
    }
    /**
     * Convert database entity objects into wayline data transfer object.
     * @param entity
     * @return
     */
    private GetWaylineListResponse entityConvertToDTO(WaylineFileEntity entity) {
        if (entity == null) {
            return null;
        }
        return new GetWaylineListResponse()
                .setDroneModelKey(DeviceEnum.find(entity.getDroneModelKey()))
                .setFavorited(entity.getFavorited())
                .setName(entity.getName())
                .setPayloadModelKeys(entity.getPayloadModelKeys() != null ?
                        Arrays.stream(entity.getPayloadModelKeys().split(",")).map(DeviceEnum::find).collect(Collectors.toList()) : null)
                .setTemplateTypes(Arrays.stream(entity.getTemplateTypes().split(","))
                        .map(Integer::parseInt).map(WaylineTypeEnum::find)
                        .collect(Collectors.toList()))
                .setUsername(entity.getUsername())
                .setObjectKey(entity.getObjectKey())
                .setSign(entity.getSign())
                .setUpdateTime(entity.getUpdateTime())
                .setCreateTime(entity.getCreateTime())
                .setId(entity.getWaylineId());

    }

    /**
     * Convert the received wayline object into a database entity object.
     * @param file
     * @return
     */
    private WaylineFileEntity dtoConvertToEntity(WaylineFileDTO file) {
        WaylineFileEntity.WaylineFileEntityBuilder builder = WaylineFileEntity.builder();

        if (file != null) {
            builder.droneModelKey(file.getDroneModelKey())
                    .name(file.getName())
                    .username(file.getUsername())
                    .objectKey(file.getObjectKey())
                    // Separate multiple payload data with ",".
                    .payloadModelKeys(String.join(",", file.getPayloadModelKeys()))
                    .templateTypes(file.getTemplateTypes().stream()
                            .map(String::valueOf)
                            .collect(Collectors.joining(",")))
                    .favorited(file.getFavorited())
                    .sign(file.getSign())
                    .build();
        }

        return builder.build();
    }
}
