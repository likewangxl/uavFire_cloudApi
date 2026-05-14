package com.yx.uavfire;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ComponentScan;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudApiSampleApplicationTest {

    @Test
    void scansApplicationAndCloudSdkPackages() {
        ComponentScan componentScan = CloudApiSampleApplication.class.getAnnotation(ComponentScan.class);

        assertTrue(
                Arrays.asList(componentScan.value()).containsAll(Arrays.asList("com.yx.uavfire", "com.dji")),
                "Component scan must include the renamed application package and the DJI cloud SDK package");
    }
}
