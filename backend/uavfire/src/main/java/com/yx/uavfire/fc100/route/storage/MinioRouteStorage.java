package com.yx.uavfire.fc100.route.storage;

import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.fc100.route.config.MinioConfig;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "fc100.route.file-storage", havingValue = "minio")
@Slf4j
public class MinioRouteStorage {

    private final MinioClient minioClient;
    private final String bucket;

    public MinioRouteStorage(MinioClient minioClient, MinioConfig minioConfig) {
        this.minioClient = minioClient;
        this.bucket = minioConfig.getBucket();
    }

    public void upload(String objectKey, byte[] data, String contentType) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(data), data.length, -1)
                    .contentType(contentType)
                    .build());
            log.info("MinIO upload objectKey={} size={} bucket={}", objectKey, data.length, bucket);
        } catch (Exception e) {
            throw new Fc100BusinessException(Fc100ErrorCode.STORAGE_ERROR, e.getMessage());
        }
    }

    public String presignedDownloadUrl(String objectKey, int expirySec) {
        try {
            String url = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(expirySec, TimeUnit.SECONDS)
                    .build());
            log.info("MinIO presigned URL generated objectKey={} expirySec={}", objectKey, expirySec);
            return url;
        } catch (Exception e) {
            throw new Fc100BusinessException(Fc100ErrorCode.STORAGE_ERROR, e.getMessage());
        }
    }

    public InputStream downloadStream(String objectKey) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            throw new Fc100BusinessException(Fc100ErrorCode.STORAGE_ERROR, e.getMessage());
        }
    }
}
