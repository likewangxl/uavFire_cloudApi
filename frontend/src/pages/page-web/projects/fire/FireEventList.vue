<template>
  <div class="fire-event-page">
    <div class="fire-event-toolbar">
      <h2 style="margin: 0">火情事件列表</h2>
      <a-button type="primary" @click="showCreateModal = true">创建测试火情</a-button>
    </div>

    <a-table
      class="fire-event-table"
      :columns="columns"
      :data-source="events"
      :loading="loading"
      :row-key="(r: FireEventDTO) => r.eventId"
      :pagination="{ pageSize: 10, showSizeChanger: false, showQuickJumper: true, showTotal: (total: number) => `共 ${total} 条` }"
      :scroll="{ x: tableScrollX }"
      :locale="{ emptyText: '暂无数据' }"
      table-layout="fixed"
    >
      <template #eventIdCell="{ record }">
        <span class="table-text-wrap event-id-cell">
          {{ record.eventId }}
        </span>
      </template>
      <template #deviceSnCell="{ record }">
        <span class="table-text-wrap">
          {{ record.deviceSn || '-' }}
        </span>
      </template>
      <template #imageCell="{ record }">
        <div
          v-if="record.thermalImageUrl || record.visibleImageUrl"
          style="display: flex; gap: 8px; align-items: flex-start; flex-wrap: wrap"
        >
          <div v-if="record.visibleImageUrl" style="display: flex; flex-direction: column; gap: 4px; align-items: flex-start">
            <a-image
              :src="record.visibleImageUrl"
              :width="88"
              :height="50"
              :preview="{ src: record.visibleImageUrl }"
              style="object-fit: cover; border: 1px solid #444; border-radius: 4px;"
            />
            <span style="font-size: 11px; color: #aaa">可见光</span>
            <a
              href="#"
              style="font-size: 11px; color: #69b1ff"
              @click.prevent="openPreview(record.visibleImageUrl)"
            >切换原图/标注</a>
          </div>
          <div v-if="record.thermalImageUrl" style="display: flex; flex-direction: column; gap: 4px; align-items: flex-start">
            <div class="thermal-image-wrap">
              <a-image
                :src="record.thermalImageUrl"
                :width="88"
                :height="50"
                :preview="{ src: record.thermalImageUrl }"
                style="object-fit: cover; border: 1px solid #444; border-radius: 4px;"
              />
            </div>
            <span style="font-size: 11px; color: #ff7875">红外</span>
            <a
              href="#"
              style="font-size: 11px; color: #69b1ff"
              @click.prevent="openPreview(record.thermalImageUrl)"
            >切换原图/标注</a>
          </div>
        </div>
        <span v-else>-</span>
      </template>
      <template #missionStatusCell="{ record }">
        <StatusTag v-if="missionForEvent(record)" :status="missionForEvent(record)!.status" />
        <span v-else>-</span>
      </template>
      <template #actionCell="{ record }">
        <a-space direction="vertical" size="small">
          <a-button type="primary" size="small" @click="openHistory(record)">
            查看历史
          </a-button>
          <a-button
            v-if="missionForEvent(record)?.status === 'WAITING_REVIEW'"
            size="small"
            @click="openApprove(record)"
          >
            审批
          </a-button>
        </a-space>
      </template>
    </a-table>

    <a-modal
      v-model:visible="previewOpen"
      :footer="null"
      :title="`识别图 (${previewMode === 'annotated' ? '带框标注' : '原始帧'})`"
      width="900px"
    >
      <div style="display: flex; gap: 8px; margin-bottom: 12px">
        <a-radio-group v-model:value="previewMode" button-style="solid">
          <a-radio-button value="annotated">带框标注</a-radio-button>
          <a-radio-button value="raw">原始帧</a-radio-button>
        </a-radio-group>
        <a-button v-if="previewCurrentUrl" type="link" :href="previewCurrentUrl" target="_blank">
          在新页签打开
        </a-button>
      </div>
      <img
        v-if="previewCurrentUrl"
        :src="previewCurrentUrl"
        alt="识别图"
        style="display: block; max-width: 100%; max-height: 70vh; margin: 0 auto"
      />
    </a-modal>

    <a-drawer
      v-model:visible="historyOpen"
      :title="historyDrawerTitle"
      width="900"
      placement="right"
    >
      <div class="history-summary">
        热源识别包含首次确认和同一火点复检；可见光确认是同一次热源识别后的补充证据，不单独计入热源识别次数。
      </div>
      <a-table
        :columns="historyColumns"
        :data-source="historyDisplayRows"
        :loading="historyLoading"
        :row-key="(r: FireEventHistoryRow) => r.rowKey"
        :pagination="{ pageSize: 8, showTotal: (total: number) => `共 ${total} 条` }"
        :locale="{ emptyText: '暂无历史记录' }"
        size="small"
      />
    </a-drawer>

    <a-modal
      v-model:visible="showCreateModal"
      title="创建测试火情"
      :confirm-loading="creating"
      @ok="handleCreate"
      @cancel="resetForm"
    >
      <a-form :model="form" layout="vertical" style="margin-top: 12px">
        <a-form-item label="事件编号">
          <a-input v-model:value="form.eventId" placeholder="如: test-001" />
        </a-form-item>
        <a-form-item label="设备序列号">
          <a-input v-model:value="form.deviceSn" placeholder="如: DRONE-SN-001" />
        </a-form-item>
        <a-form-item label="来源">
          <a-input v-model:value="form.source" placeholder="如: DRONE_THERMAL" />
        </a-form-item>
        <a-form-item label="置信度 (0~1)">
          <a-input-number
            v-model:value="form.confidence"
            :min="0"
            :max="1"
            :step="0.05"
            style="width: 100%"
          />
        </a-form-item>
        <a-form-item label="纬度">
          <a-input-number v-model:value="form.lat" :step="0.0001" style="width: 100%" />
        </a-form-item>
        <a-form-item label="经度">
          <a-input-number v-model:value="form.lng" :step="0.0001" style="width: 100%" />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal
      v-model:visible="approveOpen"
      :title="`审批任务 ${approveMissionNo}`"
      :confirm-loading="approveSubmitting"
      ok-text="提交审批"
      cancel-text="取消"
      @ok="submitApprove"
    >
      <a-form :model="approveForm" layout="vertical" style="margin-top: 12px">
        <a-form-item label="操作员 ID">
          <a-input v-model:value="approveForm.operatorId" />
        </a-form-item>
        <a-form-item label="飞机 SN">
          <a-select
            v-model:value="approveForm.aircraftSn"
            placeholder="选择 FC100 飞机（起飞点取该机实时位置）"
            allow-clear
            show-search
            style="width: 100%"
            @change="onAircraftChange"
          >
            <a-select-option
              v-for="d in aircraftOptions"
              :key="d.deviceSn"
              :value="d.deviceSn"
            >
              DJI FlyCart 100 · {{ d.deviceSn }}
            </a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="载水量 (L)">
          <a-input-number v-model:value="approveForm.waterLoadLiters" style="width: 100%" />
        </a-form-item>
        <a-form-item label="起飞纬度">
          <a-input-number v-model:value="approveForm.takeoffLat" :precision="6" style="width: 100%" />
        </a-form-item>
        <a-form-item label="起飞经度">
          <a-input-number v-model:value="approveForm.takeoffLng" :precision="6" style="width: 100%" />
        </a-form-item>
        <a-form-item label="起飞高度 (m)">
          <a-input-number v-model:value="approveForm.takeoffAlt" style="width: 100%" />
        </a-form-item>
        <a-form-item label="风速 (m/s)">
          <a-input-number v-model:value="approveForm.windSpeed" :min="0" style="width: 100%" />
        </a-form-item>
        <a-form-item label="风向 (°)">
          <a-input-number v-model:value="approveForm.windDirectionDeg" :min="0" :max="360" style="width: 100%" />
        </a-form-item>
        <a-form-item label="备注">
          <a-input v-model:value="approveForm.remark" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, h } from 'vue'
import { message } from 'ant-design-vue'
import { useConnectWebSocket } from '/@/hooks/use-connect-websocket'
import { parseFireEventUpdateMessage } from '/@/api/manage'
import { eventApi } from '/@/api/fire/event'
import { missionApi, type MissionApproveBody } from '/@/api/fire/mission'
import { deliveryApi, type DeliveryDeviceDTO } from '/@/api/fire/delivery'
import type { FireEventDTO, FireEventHistoryDTO } from '/@/types/fire/event'
import type { FireEventCreateRequest } from '/@/api/fire/event'
import type { FireMissionDTO } from '/@/types/fire/mission'
import { ELocalStorageKey } from '/@/types'
import StatusTag from '/@/components/fire/StatusTag.vue'
import { formatFireLocation } from './fire-event-location.mjs'
import {
  fireEventNotificationKey,
  formatDetectionKind,
  formatDetectionStatus,
  formatEventSource,
  formatEventStatus,
  formatGeoQuality,
  formatLocationExplanation,
  reconcileFireEventUpdate
} from './fire-event-status.mjs'

const events = ref<FireEventDTO[]>([])
// 火情事件 id -> 其关联的灭火任务（最新一次）。用于在事件列表展示审核状态并就地审批。
const missionsByEventId = ref<Record<number, FireMissionDTO>>({})
const loading = ref(false)

useConnectWebSocket((payload: any) => {
  const update = parseFireEventUpdateMessage(payload)
  if (!update) return
  const reconciled = reconcileFireEventUpdate(events.value, update)
  if (!reconciled.accepted) return
  events.value = reconciled.events as FireEventDTO[]
  message.warning({
    key: fireEventNotificationKey(update),
    content: `${formatDetectionKind(update.detectionKind)}：${formatDetectionStatus(update.state)}`,
    duration: 6
  })
  loadEvents()
})

function missionForEvent (record: FireEventDTO): FireMissionDTO | undefined {
  return missionsByEventId.value[record.id]
}

// ---- 就地审批弹窗 ----
const approveOpen = ref(false)
const approveSubmitting = ref(false)
const approveMissionNo = ref('')
const defaultApproveForm = (): MissionApproveBody => ({
  operatorId: 'test-operator',
  aircraftSn: '',
  waterLoadLiters: undefined,
  takeoffLat: 0,
  takeoffLng: 0,
  takeoffAlt: undefined,
  windSpeed: 0,
  windDirectionDeg: 0,
  remark: '',
})
const approveForm = ref<MissionApproveBody>(defaultApproveForm())

// 审批弹窗里「飞机 SN」下拉用的可选 FC100 飞机（排除遥控器 RC）
const aircraftOptions = ref<DeliveryDeviceDTO[]>([])
async function loadAircraftOptions () {
  try {
    const ws = localStorage.getItem(ELocalStorageKey.WorkspaceId) || undefined
    const res = await deliveryApi.listDevices(ws)
    const devices = res.data?.data ?? []
    aircraftOptions.value = devices.filter((d) => {
      const bind = String(d.bindStatus || '').toLowerCase()
      const type = String(d.deviceType || '').toLowerCase()
      return bind !== 'rc' && type !== 'rc'
    })
  } catch (e) {
    // 取不到设备不阻塞审批，飞机 SN 仍可手填/留空
  }
}

function openApprove (record: FireEventDTO) {
  const mission = missionForEvent(record)
  if (!mission) return
  approveMissionNo.value = mission.missionNo
  approveForm.value = defaultApproveForm()
  // 起飞点不预填火点坐标：那样会让起飞点≈火点、距离≤300m，航线退化成 TAKEOFF→DROP
  // 短航线（无接近/驻留剖面）。改为用真实起飞点——所选 FC100 飞机的实时位置。
  if (aircraftOptions.value.length === 1) {
    approveForm.value.aircraftSn = aircraftOptions.value[0].deviceSn
    applyAircraftTakeoff(approveForm.value.aircraftSn)
  }
  approveOpen.value = true
}

// 用所选 FC100 飞机的实时位置回填起飞点（真实起飞点 → 生成完整 接近→驻留→投放 剖面）
async function applyAircraftTakeoff (deviceSn: string) {
  if (!deviceSn) return
  try {
    const res = await deliveryApi.deviceProps(deviceSn)
    const p = res.data?.data
    if (p && p.latitude != null && p.longitude != null) {
      approveForm.value.takeoffLat = p.latitude
      approveForm.value.takeoffLng = p.longitude
      if (p.altitude != null) approveForm.value.takeoffAlt = p.altitude
      if (p.windSpeed != null) approveForm.value.windSpeed = p.windSpeed
    }
  } catch (e) {
    // 取不到飞机位置不阻塞，起飞点留手填
  }
}

function onAircraftChange (deviceSn: string) {
  applyAircraftTakeoff(deviceSn)
}

async function submitApprove () {
  if (!approveMissionNo.value) return
  approveSubmitting.value = true
  try {
    await missionApi.approve(approveMissionNo.value, approveForm.value)
    message.success('审批成功，任务已进入灭火任务列表')
    approveOpen.value = false
    await loadEvents()
  } catch (e: any) {
    message.error(e?.response?.data?.message ?? '审批失败')
  } finally {
    approveSubmitting.value = false
  }
}
const showCreateModal = ref(false)
const creating = ref(false)
const historyOpen = ref(false)
const historyLoading = ref(false)
const historyEvents = ref<FireEventHistoryDTO[]>([])
const historyEvent = ref<FireEventDTO | null>(null)

type FireEventHistoryRow = FireEventHistoryDTO & {
  rowKey: string;
  visibleConfirmation?: FireEventHistoryDTO;
}

const defaultForm = (): FireEventCreateRequest => ({
  eventId: '',
  deviceSn: '',
  source: 'DRONE_THERMAL',
  confidence: 0.85,
  lat: 39.9042,
  lng: 116.4074,
})

const form = ref<FireEventCreateRequest>(defaultForm())

// 识别图预览：annotated 是带 YOLO 框的标注图，raw 是原始帧。
// ai-service 文件命名约定 <eventId>-annotated.jpg / <eventId>-raw.jpg，
// 数据库图片 URL 存的是 annotated，靠后缀替换得到 raw。
const previewOpen = ref(false)
const previewBaseUrl = ref<string | null>(null)
const previewMode = ref<'annotated' | 'raw'>('annotated')
const previewCurrentUrl = computed(() => {
  if (!previewBaseUrl.value) return ''
  return recognitionImageUrlForMode(previewBaseUrl.value, previewMode.value)
})
const historyDrawerTitle = computed(() => {
  const total = historyEvent.value?.reportCount ?? thermalHistoryCount.value
  return `火情历史信息（热源识别 ${total} 次 / 历史记录 ${historyEvents.value.length} 条）`
})

const thermalHistoryCount = computed(() =>
  historyEvents.value.filter(item => !isVisibleHistoryAction(item.action)).length,
)

const historyDisplayRows = computed(() => groupHistoryEvents(historyEvents.value))

function openPreview (url: string) {
  previewBaseUrl.value = url
  previewMode.value = 'annotated'
  previewOpen.value = true
}

function recognitionImageUrlForMode (url: string, mode: 'annotated' | 'raw') {
  const hashIndex = url.indexOf('#')
  const withoutHash = hashIndex >= 0 ? url.slice(0, hashIndex) : url
  const hash = hashIndex >= 0 ? url.slice(hashIndex) : ''
  const queryIndex = withoutHash.indexOf('?')
  const path = queryIndex >= 0 ? withoutHash.slice(0, queryIndex) : withoutHash
  const query = queryIndex >= 0 ? withoutHash.slice(queryIndex) : ''
  const annotatedPath = path.replace(/-raw\.jpg$/, '-annotated.jpg')
  const nextPath = mode === 'raw'
    ? annotatedPath.replace(/-annotated\.jpg$/, '-raw.jpg')
    : annotatedPath
  return nextPath + query + hash
}

function formatThermalTemperature (value: number | null | undefined) {
  const n = Number(value)
  return Number.isFinite(n) ? n.toFixed(1) : '-'
}

function resetForm () {
  form.value = defaultForm()
  showCreateModal.value = false
}

async function loadEvents () {
  loading.value = true
  try {
    const [eventRes, missionRes] = await Promise.all([
      eventApi.list(),
      missionApi.list({ size: 500 }),
    ])
    events.value = eventRes.data.data ?? []
    // 后端任务列表按 create_time 倒序，同一事件多次尝试时保留首个(=最新)
    const map: Record<number, FireMissionDTO> = {}
    for (const m of missionRes.data.data ?? []) {
      if (m.fireEventId != null && !(m.fireEventId in map)) {
        map[m.fireEventId] = m
      }
    }
    missionsByEventId.value = map
  } catch (e) {
    message.error('加载火情列表失败')
  } finally {
    loading.value = false
  }
}

async function openHistory (record: FireEventDTO) {
  historyEvent.value = record
  historyOpen.value = true
  historyLoading.value = true
  historyEvents.value = []
  try {
    const res = await eventApi.history(record.eventId)
    historyEvents.value = res.data.data ?? []
  } catch (e) {
    message.error('加载火情历史失败')
  } finally {
    historyLoading.value = false
  }
}

function displayFireLevel (level: string | null | undefined) {
  const labels: Record<string, string> = {
    HIGH: '高',
    MEDIUM: '中',
    LOW: '低',
  }
  return level ? labels[level] ?? '未知状态' : '未知状态'
}

function displayEventStatus (status: string | null | undefined) {
  return formatEventStatus(status)
}

function displayGeoQuality (record: FireEventDTO | FireEventHistoryDTO) {
  const locationExplanation = formatLocationExplanation(record)
  const label = locationExplanation !== '未知状态'
    ? locationExplanation
    : formatGeoQuality(record.geoQuality)
  const radius = Number(record.geoErrorRadiusM)
  return Number.isFinite(radius) ? `${label} / ${radius.toFixed(1)}m` : label
}

function displayThermalRoi (record: FireEventDTO | FireEventHistoryDTO) {
  if (!record.thermalRoi) return '-'
  try {
    const roi = JSON.parse(record.thermalRoi)
    const x = Number(roi.x)
    const y = Number(roi.y)
    const width = Number(roi.width)
    const height = Number(roi.height)
    if ([x, y, width, height].every(Number.isFinite)) {
      return `x=${x.toFixed(3)}, y=${y.toFixed(3)}, w=${width.toFixed(3)}, h=${height.toFixed(3)}`
    }
  } catch (e) {
    return record.thermalRoi
  }
  return record.thermalRoi
}

function displayHistoryAction (action: string | null | undefined) {
  const labels: Record<string, string> = {
    CREATED: '首次热源确认',
    MERGED: '热源复检',
    VISIBLE_CONFIRM: '可见光确认',
    VISIBLE_PENDING: '可见光确认中',
    VISIBLE_REJECTED: '可见光未确认',
    VISIBLE_CAPTURE_FAILED: '可见光抓图失败',
    VISIBLE_CONFIRM_FAILED: '可见光确认失败',
    VISIBLE_VALIDATION_FAILED: '可见光图无效',
  }
  return action ? labels[action] ?? '未知状态' : '未知状态'
}

function isVisibleHistoryAction (action: string | null | undefined) {
  return Boolean(action?.startsWith('VISIBLE_'))
}

function groupHistoryEvents (events: FireEventHistoryDTO[]): FireEventHistoryRow[] {
  const ordered = [...events].sort((a, b) => {
    const timeDiff = Number(a.eventTimestamp ?? 0) - Number(b.eventTimestamp ?? 0)
    if (timeDiff !== 0) return timeDiff
    return Number(a.id ?? 0) - Number(b.id ?? 0)
  })
  const groups: FireEventHistoryRow[] = []
  let latestThermal: FireEventHistoryRow | null = null

  ordered.forEach(item => {
    const row = toHistoryRow(item)
    if (isVisibleHistoryAction(item.action) && latestThermal) {
      groups[groups.length - 1] = withVisibleConfirmation(latestThermal, row)
      latestThermal = groups[groups.length - 1]
      return
    }
    groups.push(row)
    latestThermal = isVisibleHistoryAction(item.action) ? null : row
  })

  return groups.reverse()
}

function toHistoryRow (item: FireEventHistoryDTO): FireEventHistoryRow {
  return {
    ...item,
    rowKey: `${item.action || 'history'}-${item.id || item.sourceEventId}`,
  }
}

function withVisibleConfirmation (
  thermal: FireEventHistoryRow,
  visible: FireEventHistoryRow,
): FireEventHistoryRow {
  return {
    ...thermal,
    visibleConfirmation: visible,
  }
}

function historyImageLinks (record: FireEventHistoryRow) {
  const links = []
  if (record.thermalImageUrl) {
    links.push(historyImageLink(record.thermalImageUrl, '红外证据图', '#ff7875'))
  }
  if (record.visibleConfirmation?.visibleImageUrl) {
    links.push(historyImageLink(record.visibleConfirmation.visibleImageUrl, '可见光确认图', '#1677ff'))
  } else if (record.visibleImageUrl) {
    links.push(historyImageLink(record.visibleImageUrl, '可见光确认图', '#1677ff'))
  }
  const visibleStatus = visibleStatusText(record)
  if (visibleStatus) {
    links.push(h('span', { style: 'color: #8c8c8c' }, visibleStatus))
  }
  return links.length ? h('span', { class: 'history-evidence-links' }, links) : '-'
}

function visibleStatusText (record: FireEventHistoryRow) {
  const action = record.visibleConfirmation?.action
  if (!action || action === 'VISIBLE_CONFIRM') return ''
  return displayHistoryAction(action)
}

function historyImageLink (url: string, label: string, color: string) {
  return h('a', {
    href: '#',
    style: `margin-right: 8px; color: ${color}`,
    onClick: (event: Event) => {
      event.preventDefault()
      openPreview(url)
    },
  }, label)
}

async function handleCreate () {
  if (!form.value.eventId.trim()) {
    message.warning('请填写事件ID')
    return
  }
  creating.value = true
  try {
    const res = await eventApi.createMock(form.value)
    if (res.data.code === 0 && res.data.data?.missionNo) {
      message.success('火情创建成功，可在灭火任务列表查看对应任务')
      showCreateModal.value = false
      resetForm()
      await loadEvents()
    } else if (res.data.code === 0 && res.data.data?.merged) {
      message.success('同坐标火情已合并到已有事件')
      showCreateModal.value = false
      resetForm()
      await loadEvents()
    } else {
      message.error(res.data.message ?? '创建失败')
    }
  } catch (e) {
    message.error('创建火情失败，请检查后端服务')
  } finally {
    creating.value = false
  }
}

const ACTION_COLUMN_WIDTH = 110
const tableScrollX = 2180

const columns = [
  { title: '事件编号', key: 'eventId', width: 230, slots: { customRender: 'eventIdCell' } },
  {
    title: '来源',
    key: 'source',
    width: 150,
    customRender: ({ record }: { record: FireEventDTO }) => formatEventSource(record.source),
  },
  { title: '设备SN', key: 'deviceSn', width: 190, slots: { customRender: 'deviceSnCell' } },
  {
    title: '置信度',
    key: 'confidence',
    width: 90,
    customRender: ({ record }: { record: FireEventDTO }) =>
      Number(record.confidence).toFixed(2),
  },
  {
    title: '火情等级',
    key: 'fireLevel',
    width: 100,
    customRender: ({ record }: { record: FireEventDTO }) => displayFireLevel(record.fireLevel),
  },
  {
    title: '识别次数',
    key: 'reportCount',
    width: 90,
    customRender: ({ record }: { record: FireEventDTO }) => record.reportCount ?? 1,
  },
  {
    title: '识别图',
    key: 'recognitionImages',
    width: 210,
    slots: { customRender: 'imageCell' },
  },
  {
    title: '坐标 (lat, lng)',
    key: 'coord',
    width: 160,
    customRender: ({ record }: { record: FireEventDTO }) =>
      formatFireLocation(record, 4),
  },
  {
    title: '测绘质量',
    key: 'geoQuality',
    width: 140,
    customRender: ({ record }: { record: FireEventDTO }) => displayGeoQuality(record),
  },
  {
    title: '热成像温度(°C)',
    key: 'thermalTemperature',
    width: 130,
    customRender: ({ record }: { record: FireEventDTO }) =>
      formatThermalTemperature(record.thermalTemperature),
  },
  {
    title: '状态',
    key: 'status',
    width: 110,
    customRender: ({ record }: { record: FireEventDTO }) => displayEventStatus(record.status),
  },
  {
    title: '审核状态',
    key: 'missionReview',
    width: 120,
    slots: { customRender: 'missionStatusCell' },
  },
  {
    title: '事件时间',
    key: 'eventTimestamp',
    width: 170,
    customRender: ({ record }: { record: FireEventDTO }) =>
      record.eventTimestamp
        ? new Date(record.eventTimestamp).toLocaleString('zh-CN')
        : '-',
  },
  {
    title: '最近命中',
    key: 'lastSeenTime',
    width: 170,
    customRender: ({ record }: { record: FireEventDTO }) =>
      record.lastSeenTime
        ? new Date(record.lastSeenTime).toLocaleString('zh-CN')
        : '-',
  },
  {
    title: '操作',
    key: 'action',
    width: ACTION_COLUMN_WIDTH,
    fixed: 'right',
    slots: { customRender: 'actionCell' },
  },
]

const historyColumns = [
  {
    title: '命中时间',
    key: 'eventTimestamp',
    width: 170,
    customRender: ({ record }: { record: FireEventHistoryDTO }) =>
      record.eventTimestamp ? new Date(record.eventTimestamp).toLocaleString('zh-CN') : '-',
  },
  {
    title: '动作',
    key: 'action',
    width: 90,
    customRender: ({ record }: { record: FireEventHistoryDTO }) => displayHistoryAction(record.action),
  },
  {
    title: '置信度',
    key: 'confidence',
    width: 90,
    customRender: ({ record }: { record: FireEventHistoryDTO }) =>
      Number(record.confidence).toFixed(2),
  },
  {
    title: '火情等级',
    key: 'fireLevel',
    width: 100,
    customRender: ({ record }: { record: FireEventHistoryDTO }) => displayFireLevel(record.fireLevel),
  },
  {
    title: '温度(°C)',
    key: 'thermalTemperature',
    width: 90,
    customRender: ({ record }: { record: FireEventHistoryDTO }) =>
      formatThermalTemperature(record.thermalTemperature),
  },
  {
    title: '坐标',
    key: 'coord',
    width: 150,
    customRender: ({ record }: { record: FireEventHistoryDTO }) =>
      formatFireLocation(record, 4),
  },
  {
    title: '测绘质量',
    key: 'geoQuality',
    width: 140,
    customRender: ({ record }: { record: FireEventHistoryDTO }) => displayGeoQuality(record),
  },
  {
    title: '测温ROI',
    key: 'thermalRoi',
    width: 220,
    customRender: ({ record }: { record: FireEventHistoryDTO }) => displayThermalRoi(record),
  },
  {
    title: '源事件',
    dataIndex: 'sourceEventId',
    key: 'sourceEventId',
    width: 220,
  },
  {
    title: '证据图片',
    key: 'images',
    width: 180,
    customRender: ({ record }: { record: FireEventHistoryRow }) => historyImageLinks(record),
  },
]

onMounted(() => {
  loadEvents()
  loadAircraftOptions()
})
</script>

<style lang="scss" scoped>
.fire-event-page {
  box-sizing: border-box;
  height: 100%;
  padding: 24px;
  overflow: auto;
  background: #f6f8fa;
}

.fire-event-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.table-text-wrap {
  display: block;
  width: 100%;
  white-space: normal;
  overflow-wrap: anywhere;
  word-break: break-all;
  line-height: 1.45;
}

.event-id-cell {
  max-width: 210px;
}

.history-summary {
  margin-bottom: 12px;
  padding: 8px 12px;
  line-height: 1.6;
  color: #595959;
  background: #f6f8fa;
  border: 1px solid #e8e8e8;
  border-radius: 4px;
}

.thermal-image-wrap {
  position: relative;
  display: inline-block;
}

.fire-event-table {
  :deep(.ant-table-container),
  :deep(.ant-table-content) {
    min-width: 0;
  }

  :deep(.ant-table-body) {
    overflow-x: auto !important;
  }

  :deep(.ant-table-cell) {
    vertical-align: middle;
  }

  :deep(.ant-table-cell-fix-right),
  :deep(.ant-table-cell-fix-right-first),
  :deep(.ant-table-cell-fix-right-last) {
    box-sizing: border-box;
    width: 120px !important;
    min-width: 120px !important;
    max-width: 120px !important;
    position: sticky !important;
    right: 0 !important;
    z-index: 8;
    background: #fff;
    box-shadow: -8px 0 10px -10px rgba(0, 0, 0, 0.35);
  }

  :deep(.ant-table-thead .ant-table-cell-fix-right) {
    z-index: 12;
    background: #fafafa;
  }
}
</style>
