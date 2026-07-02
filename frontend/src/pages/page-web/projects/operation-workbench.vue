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
import { computed, onMounted, ref, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import IncidentListPanel from '/@/components/operation/IncidentListPanel.vue'
import IncidentDetailPanel from '/@/components/operation/IncidentDetailPanel.vue'
import OperationMap from '/@/components/operation/OperationMap.vue'
import OperationTimeline from '/@/components/operation/OperationTimeline.vue'
import { operationIncidentApi, useOperationMock } from '/@/api/operation/incident'
import type {
  OperationIncidentDTO,
  OperationIncidentDetailDTO,
  OperationTimelineItem,
} from '/@/types/operation/incident'
import { ELocalStorageKey } from '/@/types'

const incidents = ref<OperationIncidentDTO[]>([])
const detail = ref<OperationIncidentDetailDTO | null>(null)
const timeline = ref<OperationTimelineItem[]>([])
const selectedIncidentId = ref<number | undefined>()
const statusFilter = ref('')
const levelFilter = ref('')
const listLoading = ref(false)
const detailLoading = ref(false)
const timelineLoading = ref(false)
const submittingAction = ref('')

const selectedIncident = computed<OperationIncidentDTO | null>(() => {
  if (!selectedIncidentId.value) return detail.value
  return incidents.value.find(item => item.id === selectedIncidentId.value) || detail.value
})

watch([statusFilter, levelFilter], () => {
  loadIncidents(true)
})

onMounted(() => {
  loadIncidents(true)
})

async function loadIncidents (selectFirst: boolean) {
  listLoading.value = true
  try {
    const res = await operationIncidentApi.list({
      status: statusFilter.value || undefined,
      level: levelFilter.value || undefined,
      page: 1,
      size: 50,
    })
    const rows = res.data.data || []
    incidents.value = rows

    const current = rows.find(item => item.id === selectedIncidentId.value)
    if (current && !selectFirst) {
      await loadIncidentDetail(current.id)
      return
    }
    const next = selectFirst ? rows[0] : current || rows[0]
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
  await loadIncidentDetail(incident.id)
}

async function loadIncidentDetail (id: number) {
  detailLoading.value = true
  timelineLoading.value = true
  try {
    const [detailRes, timelineRes] = await Promise.all([
      operationIncidentApi.detail(id),
      operationIncidentApi.timeline(id),
    ])
    const dto = detailRes.data.data
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

async function runIncidentAction (payload: { actionId: string; reason?: string }) {
  if (!detail.value) return
  const id = detail.value.id
  const body = {
    operatorId: currentOperatorId(),
    reason: payload.reason,
  }

  if (['CONFIRM_FIRE', 'GENERATE_MISSION', 'RUN_PREFLIGHT', 'CONFIRM_RELEASE'].includes(payload.actionId)) {
    message.info('该操作为一期骨架占位，后续阶段接入真实联动')
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
    Modal.error({
      title: '操作失败',
      content: e?.response?.data?.message || e?.message || '请检查后端服务状态',
    })
  } finally {
    submittingAction.value = ''
  }
}

function currentOperatorId () {
  if (typeof localStorage === 'undefined') return 'test-operator'
  return localStorage.getItem(ELocalStorageKey.UserId) || 'test-operator'
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
  background: #f3f6fa;
  color: #1f2933;
}

.operation-workbench-header {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: space-between;
  gap: 12px;

  span {
    display: block;
    color: #627386;
    font-size: 12px;
  }

  h2 {
    margin: 0;
    color: #162231;
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
