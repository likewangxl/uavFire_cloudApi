export type RuleCheckStatus = 'PASS' | 'WARN' | 'BLOCK' | string

export interface RuleCheckResult {
  ruleId: string;
  description: string;
  status: RuleCheckStatus;
  message?: string | null;
}

export interface PreflightResult {
  id?: number;
  incidentId: number;
  operatorId?: string | null;
  status: 'PASS' | 'BLOCK' | string;
  items: RuleCheckResult[];
  createTime?: number;
}

export interface PreflightCheckParam {
  incidentId: number;
  operatorId?: string;
}

export interface RecordFlightApplicationParam {
  incidentId: number;
  applicationNo: string;
  approvalNo: string;
  validFrom?: number;
  validTo?: number;
  materialUrl?: string;
  operatorId: string;
}

export interface RecordTakeoffConfirmationParam {
  incidentId: number;
  operatorId: string;
  confirmationNo?: string;
  materialUrl?: string;
  confirmedAt?: number;
}

export interface RecordLandingReportParam {
  incidentId: number;
  reportNo?: string;
  materialUrl?: string;
  operatorId: string;
  landedAt?: number;
}

export type OperationQualificationType =
  | 'CLUSTER_FLIGHT_PERMIT'
  | 'AIRDROP_APPROVAL'
  | 'AIRWORTHINESS'
  | 'JOINT_OPERATION_AGREEMENT'
  | string

export interface QualificationParam {
  qualificationType: OperationQualificationType;
  qualificationNo: string;
  issuer?: string;
  validFrom?: number;
  validTo?: number;
  materialUrl?: string;
  status?: string;
  operatorId: string;
}

export interface OperationQualificationDTO extends QualificationParam {
  id?: number;
  createTime?: number;
  updateTime?: number;
}
