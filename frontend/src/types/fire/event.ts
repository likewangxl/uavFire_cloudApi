export type FireEventStatus =
  | 'NEW'
  | 'CANDIDATE'
  | 'LOW_CONFIDENCE'
  | 'MISSION_CREATED'
  | 'IGNORED';

export interface FireEventDTO {
  id: number;
  eventId: string;
  workspaceId: string;
  source: string;
  deviceSn: string | null;
  confidence: number | string;
  fireLevel: string | null;
  lat: number;
  lng: number;
  alt: number | null;
  altitudeReference: string | null;
  geoMethod: string | null;
  geoErrorRadiusM: number | null;
  geoQuality: string | null;
  geoSourceTs: number | null;
  aircraftLat: number | null;
  aircraftLng: number | null;
  aircraftAlt: number | null;
  gimbalPitch: number | null;
  gimbalYaw: number | null;
  gimbalRoll: number | null;
  thermalRoi: string | null;
  thermalTemperature: number | null;
  temperatureUnit: string | null;
  thermalImageUrl: string | null;
  visibleImageUrl: string | null;
  eventTimestamp: number | null;
  lastSeenTime: number | null;
  reportCount: number | null;
  lastSourceEventId: string | null;
  notificationVersion: number | null;
  lastAgentSequence?: number | null;
  agentSequence?: number | null;
  updateTime?: number | null;
  detectionKind?: string | null;
  detectionStatus?: string | null;
  state?: string | null;
  locationStatus?: string | null;
  flightStatus?: string | null;
  agentId?: string | null;
  agentSessionId?: string | null;
  agentTaskId?: string | null;
  status: FireEventStatus;
  confirmedStatus?: 'PENDING' | 'CONFIRMED' | 'REJECTED' | string;
  linkedIncidentId?: number | null;
  locationQuality?: string | null;
  missionNo: string | null;
  missionStatus: string | null;
  createTime: number;
}

export interface FireEventHistoryDTO {
  id: number;
  fireEventId: number;
  eventId: string;
  sourceEventId: string;
  workspaceId: string;
  source: string;
  deviceSn: string | null;
  confidence: number | string;
  fireLevel: string | null;
  lat: number;
  lng: number;
  alt: number | null;
  altitudeReference: string | null;
  geoMethod: string | null;
  geoErrorRadiusM: number | null;
  geoQuality: string | null;
  geoSourceTs: number | null;
  aircraftLat: number | null;
  aircraftLng: number | null;
  aircraftAlt: number | null;
  gimbalPitch: number | null;
  gimbalYaw: number | null;
  gimbalRoll: number | null;
  thermalRoi: string | null;
  thermalTemperature: number | null;
  temperatureUnit: string | null;
  thermalImageUrl: string | null;
  visibleImageUrl: string | null;
  eventTimestamp: number | null;
  agentSequence?: number | null;
  action: 'CREATED' | 'MERGED' | 'VISIBLE_CONFIRM' | string;
  detectionKind?: string | null;
  detectionStatus?: string | null;
  locationStatus?: string | null;
  flightStatus?: string | null;
  createTime: number;
}
