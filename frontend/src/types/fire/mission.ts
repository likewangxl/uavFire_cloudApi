export type FireMissionStatus =
  | 'CREATED'
  | 'WAITING_REVIEW'
  | 'APPROVED'
  | 'ROUTE_GENERATED'
  | 'ROUTE_EXPORTED'
  | 'SENT_TO_DELIVERY'
  | 'ACCEPTED_BY_PILOT'
  | 'IN_PROGRESS'
  | 'PAYLOAD_RELEASE_PENDING'
  | 'PAYLOAD_RELEASED'
  | 'RETURNING'
  | 'REVIEWING'
  | 'COMPLETED'
  | 'REJECTED'
  | 'CANCELLED'
  | 'FAILED'
  | 'MANUAL_TAKEOVER'
  | 'PAYLOAD_RELEASE_FAILED'
  | 'RETURN_FAILED'
  | 'ARCHIVED';

export interface MissionLogDTO {
  id: number;
  missionId: number;
  action: string;
  fromStatus: string;
  toStatus: string;
  operatorId: string;
  operatorRole: string | null;
  clientIp: string | null;
  requestId: string | null;
  idempotencyKey: string | null;
  remark: string | null;
  createTime: number;
}

export interface FireMissionDTO {
  id: number;
  missionNo: string;
  workspaceId: string;
  status: FireMissionStatus;
  version: number;
  fireEventId: number;
  parentMissionId: number | null;
  attemptIndex: number;
  aircraftSn: string | null;
  payloadId: string | null;
  payloadType: string | null;
  waterLoadLiters: number | null;
  takeoffLat: number | null;
  takeoffLng: number | null;
  takeoffAlt: number | null;
  windSpeedAtApproval: number | null;
  windDirectionDeg: number | null;
  djiTaskId: string | null;
  latestRouteFileId: number | null;
  isHighConfidence: number | null;
  createdBy: string | null;
  approverId: string | null;
  releaseOperatorId: string | null;
  reviewerId: string | null;
  approvedAt: number | null;
  startedAt: number | null;
  payloadReleasedAt: number | null;
  completedAt: number | null;
  archivedAt: number | null;
  failedReason: string | null;
  createTime: number;
  updateTime: number;
  availableActions: string[];
}
