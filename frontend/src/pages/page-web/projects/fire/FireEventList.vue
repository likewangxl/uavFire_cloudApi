<template>
  <div style="padding: 24px">
    <div style="margin-bottom: 16px; display: flex; align-items: center; gap: 12px">
      <h2 style="margin: 0">火情事件列表</h2>
      <a-button type="primary" @click="showCreateModal = true">创建测试火情</a-button>
    </div>

    <a-table
      :columns="columns"
      :data-source="events"
      :loading="loading"
      :row-key="(r: FireEventDTO) => r.eventId"
      :pagination="{ pageSize: 20 }"
    />

    <a-modal
      v-model:open="showCreateModal"
      title="创建测试火情"
      :confirm-loading="creating"
      @ok="handleCreate"
      @cancel="resetForm"
    >
      <a-form :model="form" layout="vertical" style="margin-top: 12px">
        <a-form-item label="事件ID (eventId)">
          <a-input v-model:value="form.eventId" placeholder="如: test-001" />
        </a-form-item>
        <a-form-item label="设备SN (deviceSn)">
          <a-input v-model:value="form.deviceSn" placeholder="如: DRONE-SN-001" />
        </a-form-item>
        <a-form-item label="来源 (source)">
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
        <a-form-item label="纬度 (lat)">
          <a-input-number v-model:value="form.lat" :step="0.0001" style="width: 100%" />
        </a-form-item>
        <a-form-item label="经度 (lng)">
          <a-input-number v-model:value="form.lng" :step="0.0001" style="width: 100%" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { eventApi } from '/@/api/fire/event'
import type { FireEventDTO } from '/@/types/fire/event'
import type { FireEventCreateRequest } from '/@/api/fire/event'

const router = useRouter()

const events = ref<FireEventDTO[]>([])
const loading = ref(false)
const showCreateModal = ref(false)
const creating = ref(false)

const defaultForm = (): FireEventCreateRequest => ({
  eventId: '',
  deviceSn: '',
  source: 'DRONE_THERMAL',
  confidence: 0.85,
  lat: 39.9042,
  lng: 116.4074,
})

const form = ref<FireEventCreateRequest>(defaultForm())

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

async function handleCreate () {
  if (!form.value.eventId.trim()) {
    message.warning('请填写事件ID')
    return
  }
  creating.value = true
  try {
    const res = await eventApi.createMock(form.value)
    if (res.data.code === 0 && res.data.data?.missionNo) {
      message.success('火情创建成功，跳转任务详情')
      showCreateModal.value = false
      resetForm()
      await loadEvents()
      router.push(`/missions/${res.data.data.missionNo}`)
    } else {
      message.error(res.data.message ?? '创建失败')
    }
  } catch (e) {
    message.error('创建火情失败，请检查后端服务')
  } finally {
    creating.value = false
  }
}

const columns = [
  { title: '事件编号', dataIndex: 'eventId', key: 'eventId', width: 160 },
  { title: '来源', dataIndex: 'source', key: 'source', width: 140 },
  { title: '设备SN', dataIndex: 'deviceSn', key: 'deviceSn', width: 160 },
  {
    title: '置信度',
    key: 'confidence',
    width: 90,
    customRender: ({ record }: { record: FireEventDTO }) =>
      Number(record.confidence).toFixed(2),
  },
  { title: '火情等级', dataIndex: 'fireLevel', key: 'fireLevel', width: 100 },
  {
    title: '坐标 (lat, lng)',
    key: 'coord',
    width: 160,
    customRender: ({ record }: { record: FireEventDTO }) =>
      `${record.lat.toFixed(4)}, ${record.lng.toFixed(4)}`,
  },
  {
    title: '热成像温度(°C)',
    dataIndex: 'thermalTemperature',
    key: 'thermalTemperature',
    width: 130,
  },
  {
    title: '状态',
    key: 'status',
    width: 110,
    customRender: ({ record }: { record: FireEventDTO }) => record.status,
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
]

onMounted(() => {
  loadEvents()
})
</script>
