package com.yx.uavfire.manage.service.impl;

import com.dji.sdk.cloudapi.livestream.LivestreamRtmpUrl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LiveStreamServiceImplPlaybackUrlTest {

    @Test
    void buildRtmpPlaybackUrl_usesConfiguredHostAndPort() {
        LiveStreamServiceImpl service = new LiveStreamServiceImpl();
        ReflectionTestUtils.setField(service, "webrtcPlaybackHost", "192.168.50.254");
        ReflectionTestUtils.setField(service, "webrtcPlaybackPort", 58925);

        String playbackUrl = (String) ReflectionTestUtils.invokeMethod(
                service,
                "buildRtmpPlaybackUrl",
                new LivestreamRtmpUrl().setUrl("rtmp://192.168.50.254/live/RC_PLUS_LOCAL-0"));

        assertEquals(
                "webrtc://192.168.50.254:58925/live/RC_PLUS_LOCAL-0",
                playbackUrl);
    }
}
