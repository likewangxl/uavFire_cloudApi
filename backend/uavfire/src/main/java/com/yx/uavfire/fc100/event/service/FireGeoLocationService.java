package com.yx.uavfire.fc100.event.service;

import com.yx.uavfire.fc100.event.model.dto.FireGeoSnapshotDTO;

public interface FireGeoLocationService {
    FireGeoLocationResult resolve(FireGeoSnapshotDTO snapshot);
}
