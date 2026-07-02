export type OperationIncidentStatus =
  | 'CANDIDATE'
  | 'CONFIRMED'
  | 'DISPATCHING'
  | 'RESPONDING'
  | 'RECHECKING'
  | 'RESOLVED'
  | 'ARCHIVED'
  | 'FALSE_ALARM'
  | 'ABORTED'

export type OperationAssignmentRole =
  | 'MONITOR_PRIMARY'
  | 'MONITOR_RECHECK'
  | 'DELIVERY_PRIMARY'
  | 'DELIVERY_BACKUP'
  | 'COMMANDER'

export type OperationAssignmentStatus = 'ACTIVE' | 'RELEASED' | 'ABORTED'

export interface OperationIncidentDTO {
  id: number;
  incidentNo: string;
  fireEventId: number;
  level: string;
  status: OperationIncidentStatus | string;
  centerLat?: number;
  centerLng?: number;
  riskRadiusM?: number;
  createdBy?: string;
  confirmedBy?: string;
  closedAt?: number;
  createTime?: number;
  updateTime?: number;
  recommendedRecheck?: number;
  recheckReason?: string;
  sourceKind?: 'INCIDENT' | 'FIRE_EVENT_CANDIDATE';
  fireEventEventId?: string;
  confidence?: number | string;
  locationQuality?: string | null;
  thermalTemperature?: number | null;
  missionNo?: string | null;
  missionStatus?: string | null;
}

export interface OperationAssignmentDTO {
  id: number;
  incidentId: number;
  resourceSn: string;
  role: OperationAssignmentRole | string;
  status: OperationAssignmentStatus | string;
  leaseId?: number;
  assignedAt?: number;
  releasedAt?: number;
}

export interface OperationTimelineItem {
  type: string;
  action: string;
  fromStatus?: string;
  toStatus?: string;
  resourceSn?: string;
  role?: string;
  status?: string;
  operatorId?: string;
  description?: string;
  createTime?: number;
}

export interface OperationIncidentDetailDTO extends OperationIncidentDTO {
  assignments: OperationAssignmentDTO[];
  timeline?: OperationTimelineItem[];
}

export interface CreateOperationIncidentParam {
  fireEventId: number;
  level?: string;
  centerLat?: number;
  centerLng?: number;
  riskRadiusM?: number;
  createdBy?: string;
  confirmedBy?: string;
}

export interface AssignOperationResourceParam {
  resourceSn: string;
  role: OperationAssignmentRole | string;
  operatorId: string;
  remark?: string;
}

export interface OperationActionParam {
  operatorId: string;
  reason?: string;
}

export interface ListOperationIncidentsParams {
  status?: string;
  level?: string;
  page?: number;
  size?: number;
}
