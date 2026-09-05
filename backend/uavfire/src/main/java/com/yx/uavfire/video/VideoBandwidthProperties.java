package com.yx.uavfire.video;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "video-bandwidth")
public class VideoBandwidthProperties {
    private boolean enabled = false;
    private List<String> aircraftSns = new ArrayList<>();
    private List<String> preferredAircraftSns = new ArrayList<>();

    @PostConstruct
    public void validate() {
        if (aircraftSns.size() > 20 || new HashSet<>(aircraftSns).size() != aircraftSns.size()
                || aircraftSns.stream().anyMatch(sn -> sn == null || !sn.matches("[A-Za-z0-9_-]{1,80}"))
                || !aircraftSns.containsAll(preferredAircraftSns)
                || (enabled && aircraftSns.isEmpty())) {
            throw new IllegalArgumentException("video-bandwidth requires 1..20 distinct aircraft SNs and preferred SNs within that fleet");
        }
    }
}
