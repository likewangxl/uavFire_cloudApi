package com.dji.sdk.cloudapi.livestream;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class LiveStartPushResponse {

    private String url;

    @JsonProperty("origin_video_id")
    private List<String> originVideoId;

    public String getUrl() {
        return url;
    }

    public LiveStartPushResponse setUrl(String url) {
        this.url = url;
        return this;
    }

    public List<String> getOriginVideoId() {
        return originVideoId;
    }

    public LiveStartPushResponse setOriginVideoId(List<String> originVideoId) {
        this.originVideoId = originVideoId;
        return this;
    }

    @Override
    public String toString() {
        return "LiveStartPushResponse{url='" + url + "', originVideoId=" + originVideoId + '}';
    }
}
