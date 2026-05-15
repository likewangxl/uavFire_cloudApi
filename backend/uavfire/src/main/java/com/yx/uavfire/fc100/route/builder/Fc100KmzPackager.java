package com.yx.uavfire.fc100.route.builder;

import com.yx.uavfire.fc100.common.Sha256Util;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 把 template.kml + waylines.wpml 打包成 KMZ（zip 容器）。
 * 标准结构：wpmz/template.kml + wpmz/waylines.wpml
 */
@Component
public class Fc100KmzPackager {

    public PackagedKmz pack(byte[] templateKml, byte[] waylinesWpml) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(bos)) {
                zos.putNextEntry(new ZipEntry("wpmz/template.kml"));
                zos.write(templateKml);
                zos.closeEntry();
                zos.putNextEntry(new ZipEntry("wpmz/waylines.wpml"));
                zos.write(waylinesWpml);
                zos.closeEntry();
            }
            byte[] kmz = bos.toByteArray();
            return new PackagedKmz(kmz, Sha256Util.hex(kmz));
        } catch (Exception e) {
            throw new RuntimeException("kmz packing failed", e);
        }
    }

    @Data
    @AllArgsConstructor
    public static class PackagedKmz {
        private final byte[] bytes;
        private final String sha256;
    }
}
