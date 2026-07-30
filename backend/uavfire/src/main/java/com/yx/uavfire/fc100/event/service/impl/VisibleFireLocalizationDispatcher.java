package com.yx.uavfire.fc100.event.service.impl;

import com.yx.uavfire.manage.service.IDualStreamService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@Slf4j
public class VisibleFireLocalizationDispatcher {

    private final ObjectProvider<IDualStreamService> dualStreamServiceProvider;

    public VisibleFireLocalizationDispatcher(
            ObjectProvider<IDualStreamService> dualStreamServiceProvider) {
        this.dualStreamServiceProvider = dualStreamServiceProvider;
    }

    public void dispatch(
            String eventId,
            String taskId,
            String droneSn,
            long sourceTs,
            Map<String, Double> visibleRoi) {
        try {
            dualStreamServiceProvider.getObject().startVisibleLaserLocalization(
                    eventId, taskId, droneSn, sourceTs, visibleRoi);
        } catch (Exception ex) {
            // The fire event has already been persisted and alerted. Localization
            // coordination is best-effort and must not roll that transaction back.
            log.warn(
                    "visible fire localization dispatch failed eventId={} task={} drone={} reason={}",
                    eventId,
                    taskId,
                    droneSn,
                    ex.getMessage(),
                    ex);
        }
    }
}
