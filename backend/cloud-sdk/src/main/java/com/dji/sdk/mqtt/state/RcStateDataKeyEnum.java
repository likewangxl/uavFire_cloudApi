package com.dji.sdk.mqtt.state;

import com.dji.sdk.cloudapi.device.*;
import com.dji.sdk.cloudapi.livestream.RcLivestreamAbilityUpdate;
import com.dji.sdk.exception.CloudSDKException;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

/**
 *
 * @author sean.zhou
 * @date 2021/11/18
 * @version 0.1
 */
public enum RcStateDataKeyEnum {

    FIRMWARE_VERSION(Set.of("firmware_version"), FirmwareVersion.class),

    LIVE_CAPACITY(Set.of("live_capacity"), RcLivestreamAbilityUpdate.class),

    CONTROL_SOURCE(Set.of("control_source"), RcDroneControlSource.class),

    LIVE_STATUS(Set.of("live_status"), RcLiveStatus.class),

    PAYLOAD_FIRMWARE(PayloadModelConst.getAllModelWithPosition(), PayloadFirmwareVersion.class),

    COMMANDER_FLIGHT_HEIGHT(Set.of("commander_flight_height"), Object.class),

    COMMANDER_FLIGHT_MODE(Set.of("commander_flight_mode"), Object.class),

    COMMANDER_MODE_LOST_ACTION(Set.of("commander_mode_lost_action"), Object.class),

    RTH_MODE(Set.of("rth_mode"), Object.class),

    CLOUD_CONTROL_AUTH(Set.of("cloud_control_auth"), Object.class),

    CAPABILITY_SET(Set.of("capability_set"), Object.class),

    PSDK_WIDGET_VALUES(Set.of("psdk_widget_values"), Object.class),

    AI_BOXES(Set.of("ai_boxes"), Object.class),

    IS_BEIDOU_VERSION(Set.of("is_beidou_version"), Object.class),

    UNKNOWN(Set.of(), Object.class),
    ;

    private final Set<String> keys;

    private final Class classType;


    RcStateDataKeyEnum(Set<String> keys, Class classType) {
        this.keys = keys;
        this.classType = classType;
    }

    public Class getClassType() {
        return classType;
    }

    public Set<String> getKeys() {
        return keys;
    }

    public static RcStateDataKeyEnum find(Set<String> keys) {
        return Arrays.stream(values()).filter(keyEnum -> !Collections.disjoint(keys, keyEnum.keys)).findAny()
                .orElse(UNKNOWN);
    }

}
