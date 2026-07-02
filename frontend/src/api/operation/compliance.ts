import client from './client'
import type { ApiResponse, ApiResult } from '/@/types/operation/api'
import type {
  OperationQualificationDTO,
  PreflightCheckParam,
  PreflightResult,
  QualificationParam,
  RecordFlightApplicationParam,
  RecordLandingReportParam,
  RecordTakeoffConfirmationParam,
  RuleCheckResult,
} from '/@/types/operation/compliance'

function mockSwitchValue () {
  const envValue = String(import.meta.env.VITE_OPERATION_MOCK || '').toLowerCase()
  if (['1', 'true', 'yes', 'on'].includes(envValue)) return true
  if (typeof localStorage === 'undefined') return false
  return ['1', 'true', 'yes', 'on'].includes(String(localStorage.getItem('uavfire_operation_mock') || '').toLowerCase())
}

const useMock = mockSwitchValue()

function response<T> (data: T): Promise<ApiResponse<T>> {
  return Promise.resolve({
    data: {
      code: 0,
      message: 'success',
      data,
    },
  })
}

const flightApplications: RecordFlightApplicationParam[] = []
const takeoffConfirmations: RecordTakeoffConfirmationParam[] = []
const landingReports: RecordLandingReportParam[] = []
const qualifications: OperationQualificationDTO[] = []
let nextPreflightId = 1
let nextQualificationId = 1

const ruleDescriptions: Record<string, string> = {
  R01: '火情已人工确认',
  R02: '空域申请批复记录有效',
  R03: '投放审批记录有效',
  R04: 'FC100 在线并已接入 Delivery Sync',
  R05: '电量满足最低阈值',
  R06: '风速不超过派发阈值',
  R07: '载荷不超过 FC100 双电 85kg 含吊具口径',
  R08: '火点定位质量为 PRECISE',
  R09: '起降点、投放点和航线不越界',
  R10: '操作员已记录起飞确认',
  R11: '资源锁无冲突',
  R12: '指令队列无未完成危险指令',
  R13: '任务时间和能量预算充足',
  R14: 'DeliveryHub 连通性正常',
  R15: '运行资质档案有效',
}

const requiredQualificationTypes = [
  'CLUSTER_FLIGHT_PERMIT',
  'AIRDROP_APPROVAL',
  'AIRWORTHINESS',
  'JOINT_OPERATION_AGREEMENT',
]

function isValidWindow (from: number | undefined, to: number | undefined, now: number) {
  return (!from || from <= now) && (!to || to >= now)
}

function mockPreflight (param: PreflightCheckParam): PreflightResult {
  const now = Date.now()
  const hasApplication = flightApplications.some(item =>
    item.incidentId === param.incidentId &&
    item.applicationNo &&
    item.approvalNo &&
    isValidWindow(item.validFrom, item.validTo, now))
  const hasTakeoff = takeoffConfirmations.some(item =>
    item.incidentId === param.incidentId && item.operatorId === param.operatorId)
  const hasQualifications = requiredQualificationTypes.every(type =>
    qualifications.some(item =>
      item.qualificationType === type &&
      (item.status || 'VALID') === 'VALID' &&
      isValidWindow(item.validFrom, item.validTo, now)))

  const items: RuleCheckResult[] = Object.keys(ruleDescriptions).map(ruleId => ({
    ruleId,
    description: ruleDescriptions[ruleId],
    status: 'PASS',
    message: undefined,
  }))

  function block (ruleId: string, message: string) {
    const item = items.find(row => row.ruleId === ruleId)
    if (item) {
      item.status = 'BLOCK'
      item.message = message
    }
  }

  if (!hasApplication) block('R02', 'no valid flight-application approval record')
  if (!hasTakeoff) block('R10', 'operator takeoff confirmation record missing')
  if (!hasQualifications) block('R15', 'qualification missing or expired')
  block('R04', 'mock device properties unavailable')
  block('R05', 'mock battery percent missing')
  block('R13', 'mock time budget inputs missing')

  return {
    id: nextPreflightId++,
    incidentId: param.incidentId,
    operatorId: param.operatorId,
    status: items.some(item => item.status === 'BLOCK') ? 'BLOCK' : 'PASS',
    items,
    createTime: now,
  }
}

const mockComplianceApi = {
  runPreflight: (body: PreflightCheckParam) => response(mockPreflight(body)),
  recordFlightApplication: (body: RecordFlightApplicationParam) => {
    flightApplications.push(body)
    return response(body)
  },
  recordTakeoffConfirmation: (body: RecordTakeoffConfirmationParam) => {
    takeoffConfirmations.push(body)
    return response(body)
  },
  recordLandingReport: (body: RecordLandingReportParam) => {
    landingReports.push(body)
    return response(body)
  },
  createQualification: (body: QualificationParam) => {
    const item = { ...body, status: body.status || 'VALID', id: nextQualificationId++, createTime: Date.now() }
    qualifications.push(item)
    return response(item)
  },
  listQualifications: (type?: string) =>
    response(type ? qualifications.filter(item => item.qualificationType === type) : qualifications),
}

const realComplianceApi = {
  runPreflight: (body: PreflightCheckParam) =>
    client.post<ApiResult<PreflightResult>>('/api/compliance/preflight-checks', body),

  recordFlightApplication: (body: RecordFlightApplicationParam) =>
    client.post<ApiResult<any>>('/api/compliance/record-flight-application', body),

  recordTakeoffConfirmation: (body: RecordTakeoffConfirmationParam) =>
    client.post<ApiResult<any>>('/api/compliance/record-takeoff-confirmation', body),

  recordLandingReport: (body: RecordLandingReportParam) =>
    client.post<ApiResult<any>>('/api/compliance/record-landing-report', body),

  createQualification: (body: QualificationParam) =>
    client.post<ApiResult<OperationQualificationDTO>>('/api/compliance/qualifications', body),

  listQualifications: (type?: string) =>
    client.get<ApiResult<OperationQualificationDTO[]>>('/api/compliance/qualifications', { params: { type } }),
}

export const operationComplianceApi = useMock
  ? mockComplianceApi
  : realComplianceApi
