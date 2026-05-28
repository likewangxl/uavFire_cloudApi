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
      <template #actionCell="{ record }">
        <a-space direction="vertical" size="small">
          <a-button type="primary" size="small" @click="openHistory(record)">
            查看历史
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
        历史快照从历史功能上线后开始逐次记录；早期已经累计到识别次数里的命中，只能保留累计次数，无法还原成逐条快照。
      </div>
      <a-table
        :columns="historyColumns"
        :data-source="historyEvents"
        :loading="historyLoading"
        :row-key="(r: FireEventHistoryDTO) => r.id"
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
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, h } from 'vue'
import { message } from 'ant-design-vue'
import { eventApi } from '/@/api/fire/event'
import type { FireEventDTO, FireEventHistoryDTO } from '/@/types/fire/event'
import type { FireEventCreateRequest } from '/@/api/fire/event'

const events = ref<FireEventDTO[]>([])
const loading = ref(false)
const showCreateModal = ref(false)
const creating = ref(false)
const historyOpen = ref(false)
const historyLoading = ref(false)
const historyEvents = ref<FireEventHistoryDTO[]>([])
const historyEvent = ref<FireEventDTO | null>(null)

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
  const total = historyEvent.value?.reportCount ?? historyEvents.value.length
  return `火情历史信息（已记录 ${historyEvents.value.length} 条 / 识别 ${total} 次）`
})

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
    const res = await eventApi.list()
    events.value = res.data.data ?? []
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
  return level ? labels[level] ?? level : '-'
}

function displayEventStatus (status: string | null | undefined) {
  const labels: Record<string, string> = {
    NEW: '新事件',
    LOW_CONFIDENCE: '低置信度',
    MISSION_CREATED: '已创建任务',
    IGNORED: '已忽略',
  }
  return status ? labels[status] ?? status : '-'
}

function displayHistoryAction (action: string | null | undefined) {
  const labels: Record<string, string> = {
    CREATED: '新建事件',
    MERGED: '合并命中',
  }
  return action ? labels[action] ?? action : '-'
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
const tableScrollX = 1920

const columns = [
  { title: '事件编号', key: 'eventId', width: 230, slots: { customRender: 'eventIdCell' } },
  { title: '来源', dataIndex: 'source', key: 'source', width: 100 },
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
      `${record.lat.toFixed(4)}, ${record.lng.toFixed(4)}`,
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
      `${record.lat.toFixed(4)}, ${record.lng.toFixed(4)}`,
  },
  {
    title: '源事件',
    dataIndex: 'sourceEventId',
    key: 'sourceEventId',
    width: 220,
  },
  {
    title: '图片',
    key: 'images',
    width: 140,
    customRender: ({ record }: { record: FireEventHistoryDTO }) => {
      const links = []
      if (record.visibleImageUrl) {
        links.push(h('a', {
          href: '#',
          style: 'margin-right: 8px',
          onClick: (event: Event) => {
            event.preventDefault()
            openPreview(record.visibleImageUrl!)
          },
        }, '可见光'))
      }
      if (record.thermalImageUrl) {
        links.push(h('a', {
          href: '#',
          onClick: (event: Event) => {
            event.preventDefault()
            openPreview(record.thermalImageUrl!)
          },
        }, '红外'))
      }
      return links.length ? h('span', links) : '-'
    },
  },
]

onMounted(() => {
  loadEvents()
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
