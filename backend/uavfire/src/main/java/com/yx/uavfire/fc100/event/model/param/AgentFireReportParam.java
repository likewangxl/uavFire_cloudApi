package com.yx.uavfire.fc100.event.model.param;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = false)
public class AgentFireReportParam {
    private String agentId;
    private String droneSn;
    private String taskId;
    private String eventId;
    private String sessionId;
    private Long sequence;
    private Long eventTimestamp;
    private String state;
    private String detectionKind;
    private Double confidence;
    private Roi visibleRoi;
    private String locationStatus;
    private String flightStatus;
    private String geoMethod;
    private String modelVersion;
    private String modelHash;
    private String policyVersion;
    private Integer inputSize;
    private String runtime;
    private Long sourceGeneration;
    private Long coordinatorGeneration;
    private Point aircraft;
    private Double fireLat;
    private Double fireLng;
    private Double fireAlt;
    private Double errorRadiusMeters;
    private String degradedReason;
    private String reason;
    private List<LaserSample> laserSamples;
    private final Map<String, Object> unknownFields = new LinkedHashMap<>();
    @JsonAnySetter public void unknown(String name, Object value) { unknownFields.put(name, value); }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = false)
    public static class Roi {
        private Double x; private Double y; private Double width; private Double height;
        private final Map<String, Object> unknownFields = new LinkedHashMap<>();
        @JsonAnySetter public void unknown(String name, Object value) { unknownFields.put(name, value); }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = false)
    public static class Point {
        private Double lat; private Double lng; private Double alt;
        private final Map<String, Object> unknownFields = new LinkedHashMap<>();
        @JsonAnySetter public void unknown(String name, Object value) { unknownFields.put(name, value); }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = false)
    public static class LaserSample {
        private String status;
        private Double rangeMeters;
        private Double lat;
        private Double lng;
        private Double alt;
        private Long eventTimestamp;
        private final Map<String, Object> unknownFields = new LinkedHashMap<>();
        @JsonAnySetter public void unknown(String name, Object value) { unknownFields.put(name, value); }
    }
}
