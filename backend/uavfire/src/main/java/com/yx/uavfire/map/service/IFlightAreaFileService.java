package com.yx.uavfire.map.service;

import com.yx.uavfire.map.model.dto.FlightAreaDTO;
import com.yx.uavfire.map.model.dto.FlightAreaFileDTO;

import java.util.List;
import java.util.Optional;

/**
 * @author sean
 * @version 1.9
 * @date 2023/11/22
 */
public interface IFlightAreaFileService {

    Optional<FlightAreaFileDTO> getFlightAreaFileByFileId(String fileId);

    Integer saveFlightAreaFile(FlightAreaFileDTO file);

    Integer setNonLatestByWorkspaceId(String workspaceId);

    Optional<FlightAreaFileDTO> getLatestByWorkspaceId(String workspaceId);

    FlightAreaFileDTO packageFlightAreaFile(String workspaceId, List<FlightAreaDTO> flightAreas);
}
