package com.yx.uavfire.fc100.route.service;

import com.yx.uavfire.fc100.route.model.dto.RouteFileDTO;

public interface RouteExportService {

    /** 导出最新航点版本为 KMZ，落 fc100_route_file，状态机推进 → ROUTE_EXPORTED */
    RouteFileDTO exportKmz(String missionNo, String operatorId, String clientIp, String requestId);

    RouteFileDTO getLatest(String missionNo);

    byte[] downloadById(Long fileId);

    /**
     * local 模式：返回相对下载 URL，expiresInSec=-1（不过期）。
     * minio 模式：返回 presigned URL，expiresInSec=900。
     */
    DownloadUrlResult getDownloadUrl(Long fileId, String contextPath, String missionNo);

    class DownloadUrlResult {
        public final String url;
        public final int expiresInSec;
        public DownloadUrlResult(String url, int expiresInSec) {
            this.url = url;
            this.expiresInSec = expiresInSec;
        }
    }
}
