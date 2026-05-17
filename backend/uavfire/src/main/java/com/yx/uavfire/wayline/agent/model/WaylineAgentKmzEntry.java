package com.yx.uavfire.wayline.agent.model;

import java.util.Objects;

/**
 * KMZ bytes registered for a particular (droneSn, missionId) pair so the
 * agent's KMZ-download endpoint can stream them on request. MD5 is
 * pre-computed so the agent can verify after download.
 */
public final class WaylineAgentKmzEntry {

    private final String missionId;
    private final byte[] kmzBytes;
    private final String md5;

    public WaylineAgentKmzEntry(String missionId, byte[] kmzBytes, String md5) {
        this.missionId = Objects.requireNonNull(missionId, "missionId");
        this.kmzBytes = Objects.requireNonNull(kmzBytes, "kmzBytes");
        this.md5 = Objects.requireNonNull(md5, "md5");
    }

    public String getMissionId() {
        return missionId;
    }

    public byte[] getKmzBytes() {
        return kmzBytes;
    }

    public String getMd5() {
        return md5;
    }
}
