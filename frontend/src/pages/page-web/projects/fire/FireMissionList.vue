<template>
  <div class="fire-mission-page">
    <div class="fire-mission-toolbar">
      <div>
        <h2>灭火任务列表</h2>
        <span>按运行状态追踪 FC100 灭火任务和火情处置进度</span>
      </div>
      <a-select
        v-model:value="statusFilter"
        class="fire-mission-filter"
        @change="onStatusChange"
        placeholder="筛选状态"
      >
        <a-select-option value="">全部</a-select-option>
        <a-select-option v-for="s in statusOptions" :key="s.value" :value="s.value">
          {{ s.label }}
        </a-select-option>
      </a-select>
    </div>

    <a-table
      class="fire-mission-table"
      :columns="columns"
      :data-source="missions"
      :loading="loading"
      :row-key="(r: FireMissionDTO) => r.missionNo"
      :pagination="{ pageSize: 20 }"
      :scroll="{ x: 1280 }"
    >
      <template #missionNoCell="{ record }: { record: FireMissionDTO }">
        <div class="mission-number-cell">
          <strong>{{ record.missionNo }}</strong>
        </div>
      </template>
      <template #fireEventCell="{ record }: { record: FireMissionDTO }">
        <div class="mission-fire-summary">
          <div class="mission-title-line">
            <span class="fire-level-pill">{{ displayFireLevel(getMissionFireEvent(record)?.fireLevel) }}</span>
            <strong>{{ getMissionFireEvent(record)?.eventId || `事件 ${record.fireEventId}` }}</strong>
          </div>
          <div class="mission-meta-row">
            <span>置信度 {{ formatConfidence(getMissionFireEvent(record)?.confidence) }}</span>
            <span>温度 {{ formatTemperature(getMissionFireEvent(record)?.thermalTemperature) }}</span>
          </div>
          <div class="mission-muted-line">{{ formatCoordinate(getMissionFireEvent(record)) }}</div>
          <div class="mission-muted-line">设备 {{ getMissionFireEvent(record)?.deviceSn || '-' }}</div>
        </div>
      </template>
      <template #statusCell="{ record }: { record: FireMissionDTO }">
        <div class="mission-runtime">
          <StatusTag :status="record.status" />
          <span>{{ getMissionRuntimeText(record) }}</span>
        </div>
      </template>
      <template #aircraftCell="{ record }: { record: FireMissionDTO }">
        <div class="mission-aircraft">
          <strong>{{ formatAircraftModel(record) }}</strong>
          <span>{{ record.aircraftSn || '-' }}</span>
        </div>
      </template>
      <template #actionCell="{ record }: { record: FireMissionDTO }">
        <div class="mission-action-panel">
          <div class="mission-action-primary">
            <ActionButtons
              v-if="record.availableActions?.length"
              :mission-no="record.missionNo"
              :actions="record.availableActions"
              :only-actions="['PREPARE_FC100_DELIVERY']"
              @refresh="loadMissions(statusFilter || undefined)"
            />
            <a-button class="mission-detail-button" size="small" @click="router.push(`/fire-mission-detail/${record.missionNo}`)">
              详情
            </a-button>
          </div>
          <div class="mission-action-secondary">
            <ActionButtons
              v-if="record.availableActions?.length"
              :mission-no="record.missionNo"
              :actions="record.availableActions"
              :exclude-actions="['PREPARE_FC100_DELIVERY']"
              @refresh="loadMissions(statusFilter || undefined)"
            />
            <span v-else class="mission-no-actions">暂无可执行操作</span>
          </div>
        </div>
      </template>
      <template #createTimeCell="{ record }: { record: FireMissionDTO }">
        {{ new Date(record.createTime).toLocaleString('zh-CN') }}
      </template>
    </a-table>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { missionApi } from '/@/api/fire/mission'
import { eventApi } from '/@/api/fire/event'
import type { FireMissionDTO, FireMissionStatus } from '/@/types/fire/mission'
import type { FireEventDTO } from '/@/types/fire/event'
import StatusTag from '/@/components/fire/StatusTag.vue'
import ActionButtons from '/@/components/fire/ActionButtons.vue'

const route = useRoute()
const router = useRouter()

const missions = ref<FireMissionDTO[]>([])
const fireEventsById = ref<Record<number, FireEventDTO>>({})
const loading = ref(false)
const approvedMissionStatuses = new Set<FireMissionStatus>([
  'APPROVED',
  'ROUTE_GENERATED',
  'ROUTE_EXPORTED',
  'SENT_TO_DELIVERY',
  'ACCEPTED_BY_PILOT',
  'IN_PROGRESS',
  'PAYLOAD_RELEASE_PENDING',
  'PAYLOAD_RELEASED',
  'RETURNING',
  'REVIEWING',
  'COMPLETED',
  'FAILED',
  'MANUAL_TAKEOVER',
  'PAYLOAD_RELEASE_FAILED',
  'RETURN_FAILED',
  'ARCHIVED',
])
const initialStatus = route.query.status as string
const statusFilter = ref<string>(approvedMissionStatuses.has(initialStatus as FireMissionStatus) ? initialStatus : '')

const statusOptions: { value: FireMissionStatus; label: string }[] = [
  { value: 'APPROVED', label: '已审批' },
  { value: 'ROUTE_GENERATED', label: '航线已生成' },
  { value: 'ROUTE_EXPORTED', label: '航线已导出' },
  { value: 'SENT_TO_DELIVERY', label: '已发送投递' },
  { value: 'ACCEPTED_BY_PILOT', label: '飞手已接收' },
  { value: 'IN_PROGRESS', label: '执行中' },
  { value: 'PAYLOAD_RELEASE_PENDING', label: '待投放' },
  { value: 'PAYLOAD_RELEASED', label: '已投放' },
  { value: 'RETURNING', label: '返航中' },
  { value: 'REVIEWING', label: '复盘中' },
  { value: 'COMPLETED', label: '已完成' },
  { value: 'FAILED', label: '已失败' },
  { value: 'MANUAL_TAKEOVER', label: '人工接管' },
  { value: 'PAYLOAD_RELEASE_FAILED', label: '投放失败' },
  { value: 'RETURN_FAILED', label: '返航失败' },
  { value: 'ARCHIVED', label: '已归档' },
]

async function loadMissions (status?: string) {
  loading.value = true
  try {
    const approvedStatus = status && approvedMissionStatuses.has(status as FireMissionStatus) ? status : ''
    const params = approvedStatus ? { status: approvedStatus } : {}
    const [res, fireEventRes] = await Promise.all([
      missionApi.list(params),
      eventApi.list({ size: 500 }),
    ])
    missions.value = (res.data.data ?? []).filter(mission =>
      approvedMissionStatuses.has(mission.status))
    fireEventsById.value = Object.fromEntries(
      (fireEventRes.data.data ?? []).map(event => [event.id, event]),
    )
  } catch (e) {
    message.error('加载任务列表失败')
  } finally {
    loading.value = false
  }
}

function onStatusChange (val: string) {
  router.replace({ query: val ? { status: val } : {} })
}

watch(
  () => route.query.status,
  (newStatus) => {
    const nextStatus = (newStatus as string) ?? ''
    statusFilter.value = approvedMissionStatuses.has(nextStatus as FireMissionStatus) ? nextStatus : ''
    loadMissions(statusFilter.value || undefined)
  },
)

function getMissionFireEvent (mission: FireMissionDTO) {
  return fireEventsById.value[mission.fireEventId] || null
}

function displayFireLevel (level: string | null | undefined) {
  const labels: Record<string, string> = {
    HIGH: '高危火情',
    MEDIUM: '中危火情',
    LOW: '低危火情',
  }
  return level ? labels[level] ?? level : '火情等级未知'
}

function formatConfidence (value: number | string | null | undefined) {
  const n = Number(value)
  return Number.isFinite(n) ? n.toFixed(2) : '-'
}

function formatTemperature (value: number | null | undefined) {
  const n = Number(value)
  return Number.isFinite(n) ? `${n.toFixed(1)}°C` : '-'
}

function formatCoordinate (event: FireEventDTO | null) {
  if (!event) return '坐标 -'
  const lat = Number(event.lat)
  const lng = Number(event.lng)
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) return '坐标 -'
  return `坐标 ${lat.toFixed(6)}, ${lng.toFixed(6)}`
}

function formatTime (value: number | null | undefined) {
  return value ? new Date(value).toLocaleString('zh-CN') : ''
}

function getMissionRuntimeText (mission: FireMissionDTO) {
  if (mission.completedAt) return `完成于 ${formatTime(mission.completedAt)}`
  if (mission.payloadReleasedAt) return `已投放 ${formatTime(mission.payloadReleasedAt)}`
  if (mission.startedAt) return `开始于 ${formatTime(mission.startedAt)}`
  if (mission.approvedAt) return `审批于 ${formatTime(mission.approvedAt)}`
  return '等待执行'
}

function formatAircraftModel (mission: FireMissionDTO) {
  return mission.aircraftSn ? 'DJI FlyCart 100' : '无人机未绑定'
}

const columns = [
  { title: '任务编号', key: 'missionNo', width: 240, slots: { customRender: 'missionNoCell' } },
  { title: '火情信息', key: 'fireEvent', width: 360, slots: { customRender: 'fireEventCell' } },
  { title: '无人机信息', key: 'aircraft', width: 200, slots: { customRender: 'aircraftCell' } },
  { title: '运行状态', key: 'status', width: 210, slots: { customRender: 'statusCell' } },
  { title: '载水量(L)', dataIndex: 'waterLoadLiters', key: 'waterLoadLiters', width: 110 },
  { title: '创建时间', key: 'createTime', width: 180, slots: { customRender: 'createTimeCell' } },
  { title: '操作', key: 'action', width: 300, slots: { customRender: 'actionCell' } },
]

onMounted(() => {
  loadMissions(statusFilter.value || undefined)
})
</script>

<style lang="scss" scoped>
.fire-mission-page {
  padding: 24px;
  background: #0f1b2a;
  min-height: 100%;
}

.fire-mission-toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;

  h2 {
    margin: 0;
    color: #e2eaf5;
    font-size: 22px;
    font-weight: 650;
    line-height: 1.3;
  }

  span {
    display: block;
    margin-top: 4px;
    color: #91a4bd;
    font-size: 13px;
  }
}

.fire-mission-filter {
  width: 180px;
  flex: 0 0 auto;
}

.fire-mission-table {
  background: #121f2f;
  border: 1px solid #2a3b50;
  border-radius: 6px;
  overflow: hidden;
}

.fire-mission-table :deep(.ant-table) {
  color: #c7d6e9;
}

.fire-mission-table :deep(.ant-table-thead > tr > th) {
  background: #1a2b40;
  border-bottom: 1px solid #2a3b50;
  color: #b8cbe2;
  font-weight: 600;
  padding: 14px 18px;
}

.fire-mission-table :deep(.ant-table-tbody > tr > td) {
  padding: 18px;
  vertical-align: middle;
  border-bottom: 1px solid #2a3b50;
}

.fire-mission-table :deep(.ant-table-tbody > tr:hover > td) {
  background: #20334c;
}

.mission-number-cell,
.mission-aircraft,
.mission-runtime {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.mission-number-cell strong,
.mission-aircraft strong {
  color: #e2eaf5;
  font-weight: 600;
  line-height: 1.35;
  word-break: break-word;
}

.mission-number-cell span,
.mission-aircraft span,
.mission-runtime span:not(.ant-tag) {
  color: #91a4bd;
  font-size: 12px;
  line-height: 1.35;
}

.mission-fire-summary {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.mission-title-line {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;

  strong {
    display: inline;
    min-width: 0;
    color: #e2eaf5;
    font-weight: 600;
    line-height: 1.35;
    word-break: break-word;
  }
}

.fire-level-pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  padding: 1px 7px;
  border: 1px solid #ffd6d6;
  border-radius: 999px;
  background: #3b2430;
  color: #cf3f3f;
  font-size: 12px;
  line-height: 18px;
}

.mission-meta-row {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  color: #91a4bd;
  font-size: 12px;
  line-height: 1.35;
}

.mission-muted-line {
  color: #9aa3ad;
  font-size: 12px;
  line-height: 1.35;
  word-break: break-all;
}

.mission-runtime :deep(.ant-tag) {
  align-self: flex-start;
  margin-right: 0;
  min-width: 88px;
  text-align: center;
}

.mission-action-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-width: 280px;
}

.mission-action-primary,
.mission-action-secondary {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.mission-action-secondary :deep(.ant-space) {
  gap: 8px 8px !important;
}

.mission-action-primary :deep(.ant-btn) {
  height: 34px;
  line-height: 1;
}

.mission-action-secondary :deep(.fire-action-button--force-fail) {
  order: 20;
}

.mission-action-secondary :deep(.fire-action-button--cancel) {
  order: 30;
}

.mission-action-secondary :deep(.ant-btn),
.mission-detail-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 34px;
  padding: 0 12px;
  border-radius: 4px;
  font-size: 13px;
  line-height: 1;
  vertical-align: middle;
}

.mission-detail-button {
  color: #2563eb;
  border-color: #bfdbfe;
  background: #1a2b40;
}

.mission-no-actions {
  color: #91a4bd;
  font-size: 12px;
}
</style>
