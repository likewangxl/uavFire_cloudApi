package com.yx.uavfire.fc100.operation.preflight;

import com.yx.uavfire.fc100.deliverysync.DeliverySyncAdapter;
import com.yx.uavfire.fc100.mission.model.entity.FireMissionEntity;
import org.springframework.stereotype.Service;

@Service
public class DeliveryHubHealthProbe {
    private final DeliverySyncAdapter adapter;

    public DeliveryHubHealthProbe(DeliverySyncAdapter adapter) {
        this.adapter = adapter;
    }

    public boolean reachable(FireMissionEntity mission) {
        try {
            String workspaceId = mission == null || mission.getWorkspaceId() == null ? "DEFAULT" : mission.getWorkspaceId();
            adapter.listDevices(workspaceId);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }
}
