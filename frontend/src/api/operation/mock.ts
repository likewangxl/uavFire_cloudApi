import type { ApiResponse } from '/@/types/operation/api'
import type {
  AssignOperationResourceParam,
  CreateOperationIncidentParam,
  ListOperationIncidentsParams,
  OperationActionParam,
  OperationAssignmentDTO,
  OperationIncidentDTO,
  OperationIncidentDetailDTO,
  OperationIncidentStatus,
  OperationTimelineItem,
} from '/@/types/operation/incident'

const now = Date.now()

let nextIncidentId = 104
let nextAssignmentId = 1004

const incidents: OperationIncidentDTO[] = [
  {
    id: 101,
    incidentNo: 'OP-20260702-001',
    fireEventId: 9001,
    level: 'HIGH',
    status: 'CONFIRMED',
    centerLat: 34.66791,
    centerLng: 109.32667,
    riskRadiusM: 180,
    createdBy: 'uavfire-agent',
    confirmedBy: 'commander-01',
    createTime: now - 1000 * 60 * 24,
    updateTime: now - 1000 * 60 * 8,
  },
  {
    id: 102,
    incidentNo: 'OP-20260702-002',
    fireEventId: 9002,
    level: 'MEDIUM',
    status: 'RESPONDING',
    centerLat: 34.6842,
    centerLng: 109.3475,
    riskRadiusM: 120,
    createdBy: 'uavfire-agent',
    confirmedBy: 'commander-02',
    createTime: now - 1000 * 60 * 46,
    updateTime: now - 1000 * 60 * 5,
  },
  {
    id: 103,
    incidentNo: 'OP-20260702-003',
    fireEventId: 9003,
    level: 'LOW',
    status: 'CANDIDATE',
    centerLat: 34.6553,
    centerLng: 109.3017,
    riskRadiusM: 80,
    createdBy: 'm4t-thermal',
    createTime: now - 1000 * 60 * 12,
    updateTime: now - 1000 * 60 * 12,
  },
]

const assignments: OperationAssignmentDTO[] = [
  {
    id: 1001,
    incidentId: 101,
    resourceSn: 'M4T-MONITOR-01',
    role: 'MONITOR_PRIMARY',
    status: 'ACTIVE',
    assignedAt: now - 1000 * 60 * 22,
  },
  {
    id: 1002,
    incidentId: 101,
    resourceSn: 'FC100-DELIVERY-01',
    role: 'DELIVERY_PRIMARY',
    status: 'ACTIVE',
    assignedAt: now - 1000 * 60 * 18,
  },
  {
    id: 1003,
    incidentId: 102,
    resourceSn: 'FC100-DELIVERY-02',
    role: 'DELIVERY_PRIMARY',
    status: 'ACTIVE',
    assignedAt: now - 1000 * 60 * 38,
  },
]

const timelineByIncident: Record<number, OperationTimelineItem[]> = {
  101: [
    statusLog('CREATE', undefined, 'CONFIRMED', 'uavfire-agent', '由已确认火情创建事件', now - 1000 * 60 * 24),
    assignmentLog(assignments[0]),
    assignmentLog(assignments[1]),
  ],
  102: [
    statusLog('CREATE', undefined, 'CONFIRMED', 'uavfire-agent', '由已确认火情创建事件', now - 1000 * 60 * 46),
    assignmentLog(assignments[2]),
    statusLog('DISPATCH', 'CONFIRMED', 'DISPATCHING', 'commander-02', '派发给 FC100-DELIVERY-02', now - 1000 * 60 * 35),
    statusLog('RESPOND', 'DISPATCHING', 'RESPONDING', 'system', '投送资源已响应', now - 1000 * 60 * 34),
  ],
  103: [
    statusLog('DETECT', undefined, 'CANDIDATE', 'm4t-thermal', '热源候选事件进入人工确认', now - 1000 * 60 * 12),
  ],
}

function response<T> (data: T): Promise<ApiResponse<T>> {
  return Promise.resolve({
    data: {
      code: 0,
      message: 'success',
      data,
    },
  })
}

function findIncident (id: number) {
  const incident = incidents.find(item => item.id === Number(id))
  if (!incident) throw new Error(`mock operation incident not found: ${id}`)
  return incident
}

function incidentAssignments (id: number) {
  return assignments.filter(item => item.incidentId === Number(id))
}

function incidentTimeline (id: number) {
  return timelineByIncident[Number(id)] || []
}

function statusLog (
  action: string,
  fromStatus: string | undefined,
  toStatus: string,
  operatorId: string,
  description: string,
  createTime: number,
): OperationTimelineItem {
  return {
    type: 'STATUS',
    action,
    fromStatus,
    toStatus,
    operatorId,
    description,
    createTime,
  }
}

function assignmentLog (assignment: OperationAssignmentDTO): OperationTimelineItem {
  return {
    type: 'ASSIGNMENT',
    action: `ASSIGN_${assignment.role}`,
    resourceSn: assignment.resourceSn,
    role: assignment.role,
    status: assignment.status,
    description: `${assignment.role} assigned to ${assignment.resourceSn}`,
    createTime: assignment.assignedAt,
  }
}

function appendStatusLog (
  incident: OperationIncidentDTO,
  action: string,
  fromStatus: string,
  toStatus: OperationIncidentStatus,
  param: OperationActionParam,
) {
  incident.status = toStatus
  incident.updateTime = Date.now()
  if (toStatus === 'ARCHIVED') incident.closedAt = Date.now()
  if (!timelineByIncident[incident.id]) timelineByIncident[incident.id] = []
  timelineByIncident[incident.id].push(statusLog(
    action,
    fromStatus,
    toStatus,
    param.operatorId,
    param.reason || action,
    Date.now(),
  ))
}

function transition (id: number, action: string, toStatus: OperationIncidentStatus, param: OperationActionParam) {
  const incident = findIncident(id)
  appendStatusLog(incident, action, String(incident.status), toStatus, param)
  return response(incident)
}

export const operationIncidentMockApi = {
  create: (body: CreateOperationIncidentParam) => {
    const incident: OperationIncidentDTO = {
      id: nextIncidentId++,
      incidentNo: `OP-20260702-${String(nextIncidentId).padStart(3, '0')}`,
      fireEventId: body.fireEventId,
      level: body.level || 'UNKNOWN',
      status: 'CONFIRMED',
      centerLat: body.centerLat,
      centerLng: body.centerLng,
      riskRadiusM: body.riskRadiusM,
      createdBy: body.createdBy,
      confirmedBy: body.confirmedBy,
      createTime: Date.now(),
      updateTime: Date.now(),
    }
    incidents.unshift(incident)
    timelineByIncident[incident.id] = [
      statusLog('CREATE', undefined, 'CONFIRMED', body.createdBy || 'mock-user', 'mock incident created', Date.now()),
    ]
    return response(incident)
  },

  list: (params?: ListOperationIncidentsParams) => {
    const page = Math.max(1, Number(params?.page || 1))
    const size = Math.max(1, Number(params?.size || 20))
    const filtered = incidents
      .filter(item => !params?.status || item.status === params.status)
      .filter(item => !params?.level || item.level === params.level)
      .sort((a, b) => Number(b.createTime || 0) - Number(a.createTime || 0))
    return response(filtered.slice((page - 1) * size, page * size))
  },

  detail: (id: number) => {
    const incident = findIncident(id)
    return response<OperationIncidentDetailDTO>({
      ...incident,
      assignments: incidentAssignments(id),
      timeline: incidentTimeline(id),
    })
  },

  timeline: (id: number) => response(incidentTimeline(id)),

  assignMonitor: (id: number, body: AssignOperationResourceParam) => assign(id, body),

  assignDelivery: (id: number, body: AssignOperationResourceParam) => assign(id, body),

  dispatch: (id: number, body: OperationActionParam) => {
    const incident = findIncident(id)
    appendStatusLog(incident, 'DISPATCH', String(incident.status), 'DISPATCHING', body)
    appendStatusLog(incident, 'RESPOND', 'DISPATCHING', 'RESPONDING', body)
    return response(incident)
  },

  abort: (id: number, body: OperationActionParam) => transition(id, 'ABORT', 'ABORTED', body),

  close: (id: number, body: OperationActionParam) => transition(id, 'ARCHIVE', 'ARCHIVED', body),

  markFalseAlarm: (id: number, body: OperationActionParam) => transition(id, 'MARK_FALSE_ALARM', 'FALSE_ALARM', body),
}

function assign (id: number, body: AssignOperationResourceParam) {
  const incident = findIncident(id)
  const assignment: OperationAssignmentDTO = {
    id: nextAssignmentId++,
    incidentId: incident.id,
    resourceSn: body.resourceSn,
    role: body.role,
    status: 'ACTIVE',
    assignedAt: Date.now(),
  }
  assignments.push(assignment)
  if (!timelineByIncident[id]) timelineByIncident[id] = []
  timelineByIncident[id].push(assignmentLog(assignment))
  return response(assignment)
}
