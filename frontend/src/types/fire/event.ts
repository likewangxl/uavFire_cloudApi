export type FireEventStatus =
  | 'NEW'
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
  thermalTemperature: number | null;
  temperatureUnit: string | null;
  thermalImageUrl: string | null;
  visibleImageUrl: string | null;
  eventTimestamp: number | null;
  status: FireEventStatus;
  createTime: number;
}
