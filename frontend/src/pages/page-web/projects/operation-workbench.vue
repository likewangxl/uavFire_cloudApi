<template>
  <div class="operation-workbench-page">
    <header class="operation-workbench-header">
      <div>
        <span>智能集群巡检灭火</span>
        <h2>事件处置工作台</h2>
      </div>
      <a-space>
        <a-tag v-if="useOperationMock" color="purple">Mock 数据</a-tag>
        <a-button :loading="listLoading" @click="loadIncidents(false)">刷新</a-button>
      </a-space>
    </header>

    <div class="operation-workbench-grid">
      <IncidentListPanel
        v-model:status-filter="statusFilter"
        v-model:level-filter="levelFilter"
        :incidents="incidents"
        :loading="listLoading"
        :selected-id="selectedIncidentId"
        @select="selectIncident"
        @refresh="loadIncidents(false)"
      />

      <OperationMap
        :incidents="incidents"
        :selected-incident="selectedIncident"
        @select="selectIncident"
      />

      <IncidentDetailPanel
        :detail="detail"
        :loading="detailLoading"
        :submitting-action="submittingAction"
        :preflight-result="preflightResult"
        :preflight-loading="preflightLoading"
        :device-options="assignableDevices"
        :devices-loading="assignableDevicesLoading"
        @run-action="runIncidentAction"
      />

      <OperationTimeline
        :items="timeline"
        :loading="timelineLoading"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { useRoute } from 'vue-router'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import IncidentListPanel from '/@/components/operation/IncidentListPanel.vue'
import IncidentDetailPanel from '/@/components/operation/IncidentDetailPanel.vue'
import OperationMap from '/@/components/operation/OperationMap.vue'
import OperationTimeline from '/@/components/operation/OperationTimeline.vue'
import { eventState } from '/@/components/command-center/event-model.mjs'
import { operationIncidentApi, useOperationMock } from '/@/api/operation/incident'
import { operationComplianceApi } from '/@/api/operation/compliance'
import { eventApi } from '/@/api/fire/event'
import { missionApi } from '/@/api/fire/mission'
import { payloadApi } from '/@/api/fire/payload'
import { deliveryApi } from '/@/api/fire/delivery'
import type { DeliveryDeviceDTO, DeliveryDeviceProperties } from '/@/api/fire/delivery'
import { listMsdkDevices } from '/@/api/msdk-device'
import type { MsdkDeviceState } from '/@/api/msdk-device'
import type {
  OperationIncidentDTO,
  OperationIncidentDetailDTO,
  OperationTimelineItem,
} from '/@/types/operation/incident'
import type { PreflightResult } from '/@/types/operation/compliance'
import type { AssignableDeviceOption } from '/@/types/operation/resource'
import type { FireEventDTO } from '/@/types/fire/event'
import { ELocalStorageKey } from '/@/types'

const route = useRoute()
const incidents = ref<OperationIncidentDTO[]>([])
const detail = ref<OperationIncidentDetailDTO | null>(null)
const timeline = ref<OperationTimelineItem[]>([])
const selectedIncidentId = ref<number | undefined>()
const statusFilter = ref('')
const levelFilter = ref('')
const listLoading = ref(false)
const detailLoading = ref(false)
const timelineLoading = ref(false)
const preflightLoading = ref(false)
const submittingAction = ref('')
const preflightResult = ref<PreflightResult | null>(null)
const assignableDevices = ref<AssignableDeviceOption[]>([])
const assignableDevicesLoading = ref(false)
let assignableDevicesTimer: number | undefined

const selectedIncident = computed<OperationIncidentDTO | null>(() => {
  if (!selectedIncidentId.value) return detail.value
  return incidents.value.find(item => item.id === selectedIncidentId.value) || detail.value
})

watch([statusFilter, levelFilter], () => {
  loadIncidents(true)
})

onMounted(() => {
  loadIncidents(true)
  loadAssignableDevices()
  assignableDevicesTimer = window.setInterval(loadAssignableDevices, 10000)
})

onBeforeUnmount(() => {
  if (assignableDevicesTimer) {
    window.clearInterval(assignableDevicesTimer)
  }
})

async function loadIncidents (selectFirst: boolean) {
  listLoading.value = true
  try {
    const [incidentRes, candidateRes] = await Promise.all([
      operationIncidentApi.list({
        status: statusFilter.value || undefined,
        level: levelFilter.value || undefined,
        page: 1,
        size: 50,
      }),
      shouldLoadCandidates()
        ? eventApi.list({ workspaceId: localStorage.getItem(ELocalStorageKey.WorkspaceId) || undefined, limit: 200 })
        : Promise.resolve({ data: { data: [] } } as any),
    ])
    const rows = [
      ...candidateEvents(candidateRes.data.data || []),
      ...(incidentRes.data.data || []),
    ]
    incidents.value = rows

    const current = rows.find(item => item.id === selectedIncidentId.value)
    if (current && !selectFirst) {
      await loadIncidentDetail(current.id)
      return
    }
    const linked = rows.find(item => String(item.id) === route.query.incident)
    const next = linked || (selectFirst ? rows[0] : current || rows[0])
    if (next) {
      await selectIncident(next)
    } else {
      selectedIncidentId.value = undefined
      detail.value = null
      timeline.value = []
    }
  } catch (e) {
    message.error('加载处置事件失败')
  } finally {
    listLoading.value = false
  }
}

async function selectIncident (incident: OperationIncidentDTO) {
  selectedIncidentId.value = incident.id
  preflightResult.value = null
  if (incident.sourceKind === 'FIRE_EVENT_CANDIDATE') {
    detail.value = candidateDetail(incident)
    timeline.value = []
    return
  }
  await loadIncidentDetail(incident.id)
}

async function loadIncidentDetail (id: number) {
  const candidate = incidents.value.find(item => item.id === id && item.sourceKind === 'FIRE_EVENT_CANDIDATE')
  if (candidate) {
    detail.value = candidateDetail(candidate)
    timeline.value = []
    return
  }
  detailLoading.value = true
  timelineLoading.value = true
  try {
    const [detailRes, timelineRes] = await Promise.all([
      operationIncidentApi.detail(id),
      operationIncidentApi.timeline(id),
    ])
    const dto = await enrichIncidentDetail(detailRes.data.data)
    const items = timelineRes.data.data || dto?.timeline || []
    detail.value = dto ? { ...dto, timeline: items } : null
    timeline.value = items
  } catch (e) {
    message.error('加载事件详情失败')
  } finally {
    detailLoading.value = false
    timelineLoading.value = false
  }
}

async function runIncidentAction (payload: { actionId: string; reason?: string; [key: string]: any }) {
  if (!detail.value) return
  const id = detail.value.id
  const body = {
    operatorId: currentOperatorId(),
    reason: payload.reason,
  }

  if (payload.actionId === 'CONFIRM_FIRE' && detail.value.sourceKind === 'FIRE_EVENT_CANDIDATE') {
    submittingAction.value = payload.actionId
    try {
      const res = await eventApi.confirm(detail.value.fireEventEventId || detail.value.fireEventId, body)
      const incident = res.data.data?.incident
      message.success(res.data.data?.draftMissionCreated ? '火情已确认，已生成任务草稿' : '火情已确认，需复测或人工标注坐标')
      await loadIncidents(false)
      if (incident?.id) {
        selectedIncidentId.value = incident.id
        await loadIncidentDetail(incident.id)
      }
    } catch (e: any) {
      Modal.error({
        title: '确认火情失败',
        content: e?.response?.data?.message || e?.message || '请检查后端服务状态',
      })
    } finally {
      submittingAction.value = ''
    }
    return
  }

  if (payload.actionId === 'MARK_FALSE_ALARM' && detail.value.sourceKind === 'FIRE_EVENT_CANDIDATE') {
    submittingAction.value = payload.actionId
    try {
      await eventApi.reject(detail.value.fireEventEventId || detail.value.fireEventId, body)
      message.success('已标记为误报')
      await loadIncidents(true)
    } catch (e: any) {
      Modal.error({
        title: '标记误报失败',
        content: e?.response?.data?.message || e?.message || '请检查后端服务状态',
      })
    } finally {
      submittingAction.value = ''
    }
    return
  }

  if (payload.actionId === 'SUBMIT_RECHECK') {
    submittingAction.value = payload.actionId
    try {
      await eventApi.recheckResult(detail.value.fireEventEventId || detail.value.fireEventId, {
        operatorId: currentOperatorId(),
        maxTemp: Number((payload as any).maxTemp),
        hotAreaM2: Number((payload as any).hotAreaM2),
        flameVisible: Boolean((payload as any).flameVisible),
        suggestion: String((payload as any).suggestion || 'CONTINUE_RESPONSE'),
        remark: payload.reason,
      })
      message.success('复测结果已提交')
      await loadIncidents(false)
      await loadIncidentDetail(id)
    } catch (e: any) {
      Modal.error({
        title: '提交复测失败',
        content: e?.response?.data?.message || e?.message || '请检查后端服务状态',
      })
    } finally {
      submittingAction.value = ''
    }
    return
  }

  if (payload.actionId === 'ASSIGN_DELIVERY' || payload.actionId === 'ASSIGN_MONITOR') {
    submittingAction.value = payload.actionId
    try {
      const assignBody = {
        resourceSn: String(payload.resourceSn || '').trim(),
        role: String(payload.role || ''),
        operatorId: currentOperatorId(),
        remark: payload.remark,
      }
      if (payload.actionId === 'ASSIGN_DELIVERY') {
        await operationIncidentApi.assignDelivery(id, assignBody)
      } else {
        await operationIncidentApi.assignMonitor(id, assignBody)
      }
      message.success('资源已分配')
      await loadIncidents(false)
      await loadIncidentDetail(id)
    } catch (e: any) {
      message.error(errorMessage(e) || '资源分配失败')
    } finally {
      submittingAction.value = ''
    }
    return
  }

  if (payload.actionId === 'RUN_PREFLIGHT') {
    preflightLoading.value = true
    submittingAction.value = payload.actionId
    try {
      const res = await operationComplianceApi.runPreflight({
        incidentId: id,
        operatorId: currentOperatorId(),
      })
      preflightResult.value = res.data.data || null
      message.success(preflightResult.value?.status === 'PASS' ? '预检通过' : '预检已完成，存在阻断项')
    } catch (e: any) {
      const blocked = extractPreflightResult(e)
      if (blocked) preflightResult.value = blocked
      Modal.error({
        title: '运行预检失败',
        content: errorMessage(e) || '请检查后端服务状态',
      })
    } finally {
      submittingAction.value = ''
      preflightLoading.value = false
    }
    return
  }

  if ([
    'RECORD_FLIGHT_APPLICATION',
    'RECORD_TAKEOFF_CONFIRMATION',
    'RECORD_LANDING_REPORT',
    'RECORD_QUALIFICATION',
  ].includes(payload.actionId)) {
    submittingAction.value = payload.actionId
    try {
      await submitComplianceRecord(id, payload)
      message.success('合规记录已提交')
    } catch (e: any) {
      Modal.error({
        title: '提交合规记录失败',
        content: errorMessage(e) || '请检查后端服务状态',
      })
    } finally {
      submittingAction.value = ''
    }
    return
  }

  if (payload.actionId === 'CONFIRM_RELEASE') {
    if (!detail.value.missionNo) {
      message.error('未找到待释放任务编号')
      return
    }
    submittingAction.value = payload.actionId
    try {
      const missionRes = await missionApi.detail(detail.value.missionNo)
      const mission = missionRes.data.data
      if (!mission?.releaseConfirmationToken || mission.status !== 'PAYLOAD_RELEASE_PENDING') {
        message.error('任务未处于待释放状态或令牌不可用')
        return
      }
      const now = Date.now()
      await payloadApi.confirmRelease(detail.value.missionNo, {
        operatorId: currentOperatorId(),
        confirmedArrival: true,
        confirmedNoPeopleRisk: true,
        confirmedWindOk: true,
        confirmedPayloadReady: true,
        confirmedRelease: true,
        confirmationToken: mission.releaseConfirmationToken,
        remoteHookRemark: payload.reason || '飞手已使用官方遥控器完成 FC100 开钩',
        checklistTimestamps: {
          arrival: now,
          noPeopleRisk: now,
          windOk: now,
          payloadReady: now,
          release: now,
        },
      })
      message.success('释放留证已提交')
      await loadIncidents(false)
      await loadIncidentDetail(id)
    } catch (e: any) {
      Modal.error({
        title: '释放确认失败',
        content: e?.response?.data?.message || e?.message || '请检查任务状态和确认令牌',
      })
    } finally {
      submittingAction.value = ''
    }
    return
  }

  if (payload.actionId === 'GENERATE_MISSION') {
    message.info(detail.value.missionNo ? '任务草稿已存在' : '当前后端为确认火情时自动生成任务草稿，无独立生成接口')
    return
  }

  submittingAction.value = payload.actionId
  try {
    switch (payload.actionId) {
      case 'DISPATCH':
        await operationIncidentApi.dispatch(id, body)
        break
      case 'ABORT':
        await operationIncidentApi.abort(id, body)
        break
      case 'MARK_FALSE_ALARM':
        await operationIncidentApi.markFalseAlarm(id, body)
        break
      case 'ARCHIVE':
        await operationIncidentApi.close(id, body)
        break
      default:
        message.warning(`未知操作: ${payload.actionId}`)
        return
    }
    message.success('操作已提交')
    await loadIncidents(false)
    await loadIncidentDetail(id)
  } catch (e: any) {
    const blocked = extractPreflightResult(e)
    if (blocked) preflightResult.value = blocked
    Modal.error({
      title: '操作失败',
      content: errorMessage(e) || '请检查后端服务状态',
    })
  } finally {
    submittingAction.value = ''
  }
}

async function submitComplianceRecord (incidentId: number, payload: Record<string, any>) {
  const operatorId = currentOperatorId()
  switch (payload.actionId) {
    case 'RECORD_FLIGHT_APPLICATION':
      return operationComplianceApi.recordFlightApplication({
        incidentId,
        applicationNo: payload.applicationNo,
        approvalNo: payload.approvalNo,
        validFrom: payload.validFrom,
        validTo: payload.validTo,
        operatorId,
      })
    case 'RECORD_TAKEOFF_CONFIRMATION':
      return operationComplianceApi.recordTakeoffConfirmation({
        incidentId,
        operatorId,
        confirmationNo: payload.confirmationNo,
        confirmedAt: payload.confirmedAt,
      })
    case 'RECORD_LANDING_REPORT':
      return operationComplianceApi.recordLandingReport({
        incidentId,
        operatorId,
        reportNo: payload.reportNo,
        landedAt: payload.landedAt,
      })
    case 'RECORD_QUALIFICATION':
      return operationComplianceApi.createQualification({
        qualificationType: payload.qualificationType,
        qualificationNo: payload.qualificationNo,
        issuer: payload.issuer,
        validFrom: payload.validFrom,
        validTo: payload.validTo,
        status: payload.status || 'VALID',
        operatorId,
      })
    default:
      throw new Error(`unknown compliance action: ${payload.actionId}`)
  }
}

async function enrichIncidentDetail (dto?: OperationIncidentDetailDTO): Promise<OperationIncidentDetailDTO | null> {
  if (!dto) return null
  const needsFireEvent = dto.fireEventId && (
    dto.locationQuality == null ||
    dto.confidence == null ||
    dto.thermalTemperature == null
  )
  if (!needsFireEvent) return dto
  try {
    const res = await eventApi.get(dto.fireEventEventId || dto.fireEventId)
    const event = res.data.data
    if (!event) return dto
    return {
      ...dto,
      centerLat: dto.centerLat ?? event.lat,
      centerLng: dto.centerLng ?? event.lng,
      riskRadiusM: dto.riskRadiusM ?? event.geoErrorRadiusM ?? undefined,
      confidence: dto.confidence ?? event.confidence,
      locationQuality: dto.locationQuality || event.locationQuality || event.geoQuality || 'UNKNOWN',
      thermalTemperature: dto.thermalTemperature ?? event.thermalTemperature,
      missionNo: dto.missionNo ?? event.missionNo,
      missionStatus: dto.missionStatus ?? event.missionStatus,
    }
  } catch (e) {
    return dto
  }
}

function extractPreflightResult (e: any): PreflightResult | null {
  const data = e?.response?.data?.data || e?.data?.data || e?.data
  if (data && Array.isArray(data.items) && data.status) {
    return data as PreflightResult
  }
  return null
}

function errorMessage (e: any) {
  return e?.response?.data?.message || e?.data?.message || e?.message || ''
}

function currentOperatorId () {
  if (typeof localStorage === 'undefined') return 'test-operator'
  return localStorage.getItem(ELocalStorageKey.UserId) || 'test-operator'
}

function shouldLoadCandidates () {
  return !statusFilter.value || statusFilter.value === 'CANDIDATE'
}

function candidateEvents (events: FireEventDTO[]): OperationIncidentDTO[] {
  return events
    .filter(event => !event.linkedIncidentId && eventState(event).key === 'pending')
    .map(event => ({
      id: -Number(event.id),
      incidentNo: event.eventId,
      fireEventId: event.id,
      fireEventEventId: event.eventId,
      level: event.fireLevel || 'UNKNOWN',
      status: 'CANDIDATE',
      centerLat: event.lat,
      centerLng: event.lng,
      riskRadiusM: event.geoErrorRadiusM || undefined,
      createdBy: event.source || undefined,
      createTime: event.createTime,
      updateTime: event.lastSeenTime || event.createTime,
      sourceKind: 'FIRE_EVENT_CANDIDATE',
      confidence: event.confidence,
      locationQuality: event.locationQuality || event.geoQuality || 'UNKNOWN',
      thermalTemperature: event.thermalTemperature,
      missionNo: event.missionNo,
      missionStatus: event.missionStatus,
    }))
}

function candidateDetail (incident: OperationIncidentDTO): OperationIncidentDetailDTO {
  return {
    ...incident,
    assignments: [],
    timeline: [],
  }
}

async function loadAssignableDevices () {
  assignableDevicesLoading.value = true
  try {
    const [deliveryResult, monitorResult] = await Promise.allSettled([
      loadDeliveryDevices(),
      loadMonitorDevices(),
    ])
    assignableDevices.value = [
      ...(deliveryResult.status === 'fulfilled' ? deliveryResult.value : []),
      ...(monitorResult.status === 'fulfilled' ? monitorResult.value : []),
    ]
  } finally {
    assignableDevicesLoading.value = false
  }
}

async function loadDeliveryDevices (): Promise<AssignableDeviceOption[]> {
  const response = await deliveryApi.listDevices()
  const devices = (response.data?.data || []).filter(isFc100DeliveryAircraftDevice)
  const enriched = await Promise.all(devices.map(async device => {
    let props: DeliveryDeviceProperties | undefined
    try {
      const propsRes = await deliveryApi.deviceProps(device.deviceSn)
      props = propsRes.data?.data || undefined
    } catch {
      props = undefined
    }
    return toDeliveryDeviceOption(device, props)
  }))
  return enriched
}

async function loadMonitorDevices (): Promise<AssignableDeviceOption[]> {
  const response = await listMsdkDevices()
  return (response.data || [])
    .filter(device => Boolean(device.aircraftSn))
    .map(toMonitorDeviceOption)
}

function isFc100DeliveryAircraftDevice (device: DeliveryDeviceDTO) {
  const deviceType = String(device.deviceType || '').trim().toLowerCase()
  const bindStatus = String(device.bindStatus || '').trim().toLowerCase()
  return Boolean(device.deviceSn) && deviceType !== 'rc' && bindStatus !== 'rc'
}

function toDeliveryDeviceOption (
  device: DeliveryDeviceDTO,
  props?: DeliveryDeviceProperties,
): AssignableDeviceOption {
  const online = props?.onlineStatus ?? String(device.online || '').toUpperCase() === 'ONLINE'
  const displayName = device.displayName || device.model || 'FC100 投送机'
  return withOptionLabel({
    kind: 'DELIVERY',
    resourceSn: device.deviceSn,
    displayName,
    model: device.model || device.deviceModelClass || device.deviceModelKey || undefined,
    online,
    onlineLabel: online ? '在线' : '离线',
    batteryPercent: props?.batteryPercent,
    rtkStatus: props?.rtkStatus,
    latitude: props?.latitude,
    longitude: props?.longitude,
    altitude: props?.altitude,
    updatedAt: props?.osdTimestamp,
  })
}

function toMonitorDeviceOption (device: MsdkDeviceState): AssignableDeviceOption {
  return withOptionLabel({
    kind: 'MONITOR',
    resourceSn: device.aircraftSn,
    displayName: device.deviceName || device.model || 'DJI 巡检机',
    model: device.model,
    online: device.online,
    onlineLabel: device.online ? '在线' : (device.connectionState || '离线'),
    batteryPercent: device.batteryPercent,
    rtkCount: device.rtkCount,
    gpsCount: device.gpsCount,
    positionFixed: device.positionFixed,
    latitude: device.latitude,
    longitude: device.longitude,
    altitude: device.height ?? device.elevation,
    updatedAt: device.updatedAt,
  })
}

function withOptionLabel (device: AssignableDeviceOption): AssignableDeviceOption {
  const parts = [
    device.displayName,
    device.resourceSn,
    device.model,
    device.onlineLabel,
    device.batteryPercent == null ? null : `${device.batteryPercent}%`,
    device.rtkStatus,
    device.rtkCount == null ? null : `RTK${device.rtkCount}`,
    device.gpsCount == null ? null : `GPS${device.gpsCount}`,
  ].filter(Boolean)
  return {
    ...device,
    optionLabel: parts.join(' '),
  }
}
</script>

<style lang="scss" scoped>
.operation-workbench-page {
  box-sizing: border-box;
  display: flex;
  min-width: 0;
  min-height: 100%;
  height: 100%;
  flex-direction: column;
  gap: 14px;
  padding: 16px;
  overflow: hidden;
  background: #0f1b2a;
  color: #e2eaf5;
}

.operation-workbench-header {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: space-between;
  gap: 12px;

  span {
    display: block;
    color: #91a4bd;
    font-size: 12px;
  }

  h2 {
    margin: 0;
    color: #e2eaf5;
    font-size: 22px;
    line-height: 1.3;
  }
}

.operation-workbench-grid {
  display: grid;
  min-width: 0;
  min-height: 0;
  flex: 1 1 auto;
  grid-template-columns: 300px minmax(420px, 1fr) 360px;
  grid-template-rows: minmax(0, 1fr) 220px;
  gap: 12px;
}

.operation-workbench-grid > :last-child {
  grid-column: 1 / 4;
}

@media (max-width: 1280px) {
  .operation-workbench-page {
    overflow: auto;
  }

  .operation-workbench-grid {
    grid-template-columns: 1fr;
    grid-template-rows: auto 520px auto 240px;
  }

  .operation-workbench-grid > :last-child {
    grid-column: auto;
  }
}
</style>
