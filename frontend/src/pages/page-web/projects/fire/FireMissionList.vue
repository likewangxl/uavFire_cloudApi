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
    >
      <template #bodyCell="{ column, record }: { column: { key: string }, record: FireMissionDTO }">
        <template v-if="column.key === 'status'">
          <StatusTag :status="record.status" />
        </template>
        <template v-else-if="column.key === 'action'">
          <router-link :to="`/missions/${record.missionNo}`">详情</router-link>
        </template>
        <template v-else-if="column.key === 'createTime'">
          {{ new Date(record.createTime).toLocaleString('zh-CN') }}
        </template>
      </template>
    </a-table>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { missionApi } from '/@/api/fire/mission'
import type { FireMissionDTO, FireMissionStatus } from '/@/types/fire/mission'
import StatusTag from '/@/components/fire/StatusTag.vue'

const route = useRoute()
const router = useRouter()

const missions = ref<FireMissionDTO[]>([])
const loading = ref(false)
const statusFilter = ref<string>((route.query.status as string) ?? '')

const statusOptions: { value: FireMissionStatus; label: string }[] = [
  { value: 'CREATED', label: '已创建' },
  { value: 'WAITING_REVIEW', label: '待审批' },
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
  { value: 'REJECTED', label: '已拒绝' },
  { value: 'CANCELLED', label: '已取消' },
  { value: 'FAILED', label: '已失败' },
  { value: 'MANUAL_TAKEOVER', label: '人工接管' },
  { value: 'PAYLOAD_RELEASE_FAILED', label: '投放失败' },
  { value: 'RETURN_FAILED', label: '返航失败' },
  { value: 'ARCHIVED', label: '已归档' },
]

async function loadMissions (status?: string) {
  loading.value = true
  try {
    const params = status ? { status } : {}
    const res = await missionApi.list(params)
    missions.value = res.data.data ?? []
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
    statusFilter.value = (newStatus as string) ?? ''
    loadMissions(statusFilter.value || undefined)
  },
)

const columns = [
  { title: '任务编号', dataIndex: 'missionNo', key: 'missionNo', width: 200 },
  { title: '关联事件ID', dataIndex: 'fireEventId', key: 'fireEventId', width: 120 },
  { title: '飞机SN', dataIndex: 'aircraftSn', key: 'aircraftSn', width: 160 },
  { title: '状态', key: 'status', width: 130 },
  { title: '载水量(L)', dataIndex: 'waterLoadLiters', key: 'waterLoadLiters', width: 100 },
  { title: '创建时间', key: 'createTime', width: 170 },
  { title: '操作', key: 'action', width: 80 },
]

onMounted(() => {
  loadMissions(statusFilter.value || undefined)
})
</script>
