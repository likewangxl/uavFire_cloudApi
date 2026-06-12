package com.yx.uavfire.fc100.event.service.impl;

import com.yx.uavfire.fc100.event.service.TerrainElevationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class TerrainElevationWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(HgtTerrainElevationService.class, MissingTerrainElevationService.class);

    @Test
    void demDirConfiguredUsesHgtServiceAsPrimary() {
        runner.withPropertyValues("uavfire.terrain.dem-dir=/tmp/nonexistent-dem")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(HgtTerrainElevationService.class);
                    assertThat(ctx.getBean(TerrainElevationService.class))
                            .isInstanceOf(HgtTerrainElevationService.class);
                });
    }

    @Test
    void demDirAbsentFallsBackToMissingService() {
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(HgtTerrainElevationService.class);
            assertThat(ctx.getBean(TerrainElevationService.class))
                    .isInstanceOf(MissingTerrainElevationService.class);
        });
    }
}
