package com.yx.uavfire.fc100.route.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "fc100.route")
public class Fc100RouteProperties {
    /** minio | local. MVP 默认 local（写本地文件，免 MinIO 依赖） */
    private String fileStorage = "local";
    /** local 模式存储根目录 */
    private String localDir = "/tmp/fc100-routes";
    /** WPML 命名空间版本 */
    private String wpmlVersion = "1.0.6";
    /** WPML 默认 droneEnumValue —— 详见 samples/wpml-schema-notes.md §5 */
    private int defaultDroneEnumValue = 100;
    private int defaultDroneSubEnumValue = 1;
    private int defaultPayloadEnumValue = 99;
    private int defaultPayloadSubEnumValue = 0;
    /** 全局起飞安全高度（m） */
    private int takeOffSecurityHeight = 20;
    /** 全局过渡速度 m/s */
    private int globalTransitionalSpeed = 15;
    /**
     * EGM96 height ≈ ellipsoidHeight + offset。
     * Round 1 验证发现符号错：参考样例 ellipsoid 1052 → height 1089，差 **+36 不是 -36**。
     * 中国大陆地理水准面差通常 +20~+40m（山区更大）。MVP 用经验常数；二期接 DEM 精修。
     */
    private double egm96OffsetMeters = 36.0;
}
