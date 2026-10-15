package com.yx.uavfire.msdk.service;

import com.yx.uavfire.msdk.model.MsdkCommandDTO;

import java.util.Optional;

/** Durable or in-memory queue of MSDK commands. Telemetry state is not stored here. */
public interface MsdkCommandStore {

    void save(MsdkCommandDTO command);

    void enqueue(String aircraftSn, String commandId);

    Optional<String> dequeue(String aircraftSn);

    Optional<MsdkCommandDTO> find(String commandId);
}
