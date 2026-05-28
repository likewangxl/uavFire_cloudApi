<template>
  <div style="padding: 24px">
    <div style="margin-bottom: 16px; display: flex; align-items: center; gap: 12px">
      <h2 style="margin: 0">灭火任务列表</h2>
      <a-select
        v-model:value="statusFilter"
        style="width: 180px"
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
      :columns="columns"
      :data-source="missions"
      :loading="loading"
      :row-key="(r: FireMissionDTO) => r.missionNo"
      :pagination="{ pageSize: 20 }"
      :scroll="{ x: 1680 }"
    >
      <template #fireEventCell="{ record }: { record: FireMissionDTO }">
        <div class="mission-fire-event">
          <strong>{{ getMissionFireEvent(record)?.eventId || `事件 ${record.fireEventId}` }}</strong>
          <span>
            {{ displayFireLevel(getMissionFireEvent(record)?.fireLevel) }}
            · 置信度 {{ formatConfidence(getMissionFireEvent(record)?.confidence) }}
            · 温度 {{ formatTemperature(getMissionFireEvent(record)?.thermalTemperature) }}
          </span>
          <span>
            {{ formatCoordinate(getMissionFireEvent(record)) }}
          </span>
          <span>
            设备 {{ getMissionFireEvent(record)?.deviceSn || '-' }}
          </span>
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
        <a-space direction="vertical" size="small" class="mission-action-stack">
          <a-button size="small" @click="router.push(`/fire-mission-detail/${record.missionNo}`)">
            详情
          </a-button>
          <ActionButtons
            v-if="record.availableActions?.length"
            :mission-no="record.missionNo"
            :actions="record.availableActions"
            @refresh="loadMissions(statusFilter || undefined)"
          />
          <span v-else class="mission-no-actions">暂无可执行操作</span>
        </a-space>
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
  { title: '任务编号', dataIndex: 'missionNo', key: 'missionNo', width: 200 },
  { title: '火情信息', key: 'fireEvent', width: 320, slots: { customRender: 'fireEventCell' } },
  { title: '无人机信息', key: 'aircraft', width: 180, slots: { customRender: 'aircraftCell' } },
  { title: '运行状态', key: 'status', width: 190, slots: { customRender: 'statusCell' } },
  { title: '载水量(L)', dataIndex: 'waterLoadLiters', key: 'waterLoadLiters', width: 100 },
  { title: '创建时间', key: 'createTime', width: 170, slots: { customRender: 'createTimeCell' } },
  { title: '操作', key: 'action', width: 360, fixed: 'right', slots: { customRender: 'actionCell' } },
]

onMounted(() => {
  loadMissions(statusFilter.value || undefined)
})
</script>

<style lang="scss" scoped>
.mission-action-stack {
  max-width: 320px;
}

.mission-fire-event,
.mission-aircraft,
.mission-runtime {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.mission-fire-event strong,
.mission-aircraft strong {
  color: #262626;
  font-weight: 600;
}

.mission-fire-event span,
.mission-aircraft span,
.mission-runtime span {
  color: #8c8c8c;
  font-size: 12px;
  line-height: 1.35;
}

.mission-no-actions {
  color: #8c8c8c;
  font-size: 12px;
}

.mission-action-stack :deep(.ant-space) {
  row-gap: 6px;
}

.mission-action-stack :deep(.ant-btn) {
  font-size: 14px;
  line-height: 1.5715;
}
</style>
