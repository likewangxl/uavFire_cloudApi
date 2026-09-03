<template>
  <div class="wayline-mission-monitor">
    <div class="mm-row mm-status-row">
      <span class="mm-status-badge" :class="statusClass">{{ statusLabel }}</span>
      <span class="mm-status-reason" v-if="taskStatusReason">{{ taskStatusReason }}</span>
    </div>
    <a-progress
      :percent="progressPercent"
      :status="progressStatus"
      size="small"
      :show-info="true" />
    <div class="mm-row mm-meta-row">
      <span v-if="record.currentWaypointIndex != null || record.totalWaypoints != null">
        航点 {{ (record.currentWaypointIndex ?? 0) + 1 }}/{{ record.totalWaypoints ?? '?' }}
      </span>
      <span v-if="record.mediaCount != null">媒体 {{ record.mediaCount }}</span>
      <span v-if="record.lastProgressTime" class="mm-meta-time">
        更新于 {{ lastUpdateText }}
      </span>
    </div>
    <div class="mm-row mm-action-row">
      <a-button
        v-if="canExecute"
        size="small"
        type="primary"
        :loading="busy"
        @click="onExecute">
        {{ executeLabel }}
      </a-button>
      <a-button
        v-if="canPause"
        size="small"
        :loading="busy"
        @click="onPause">
        暂停
      </a-button>
      <a-button
        v-if="canRecovery"
        size="small"
        type="primary"
        :loading="busy"
        @click="onRecovery">
        {{ canResumeBreakpoint ? '从断点续飞' : '恢复' }}
      </a-button>
      <a-button
        v-if="canStop"
        size="small"
        danger
        :loading="busy"
        @click="onStop">
        中止
      </a-button>
      <a-button
        v-if="canCancel"
        size="small"
        danger
        :loading="busy"
        @click="onCancel">
        取消
      </a-button>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { message } from 'ant-design-vue'
import type { PlannedWaylineRecord } from '/@/types/wayline'
import {
  cancelPlannedWaylineTask,
  getPlannedWayline,
  pausePlannedWaylineTask,
  queryPlannedWaylineBreakpoint,
  recoveryPlannedWaylineTask,
  stopPlannedWaylineTask,
} from '/@/api/wayline'

const props = defineProps<{
  workspaceId: string
  record: PlannedWaylineRecord
  pollIntervalMs?: number
}>()

const emit = defineEmits<{
  change: [record: PlannedWaylineRecord]
  execute: [record: PlannedWaylineRecord]
}>()

const busy = ref(false)
let pollTimer: number | null = null

const taskStatus = computed(() => (props.record.taskStatus || props.record.status || '').toLowerCase())

const canExecute = computed(() => ['ready', 'file_generated', 'publishing', 'finished', 'completed'].includes(taskStatus.value))
const executeLabel = computed(() => ['finished', 'completed'].includes(taskStatus.value) ? '再次执行' : '执行')
const canPause = computed(() => taskStatus.value === 'executing')
const canRecovery = computed(() => ['paused', 'broken', 'stopped'].includes(taskStatus.value))
const canStop = computed(() => ['executing', 'paused'].includes(taskStatus.value))
const canCancel = computed(() => ['publishing', 'ready'].includes(taskStatus.value))
const canResumeBreakpoint = computed(() => !!props.record.breakPointJson)

const progressPercent = computed(() => {
  const p = props.record.taskProgress
  if (typeof p !== 'number') return 0
  return Math.max(0, Math.min(100, Math.round(p)))
})

const progressStatus = computed(() => {
  switch (taskStatus.value) {
    case 'failed': case 'broken': return 'exception' as const
    case 'finished': case 'completed': return 'success' as const
    case 'paused': case 'stopped': return 'normal' as const
    default: return 'active' as const
  }
})

const STATUS_LABEL: Record<string, { label: string; cls: string }> = {
  draft: { label: '草稿', cls: 'mm-status-draft' },
  file_generated: { label: '已生成 KMZ', cls: 'mm-status-ready' },
  publishing: { label: '准备中', cls: 'mm-status-publishing' },
  ready: { label: '就绪', cls: 'mm-status-ready' },
  executing: { label: '执行中', cls: 'mm-status-executing' },
  paused: { label: '已暂停', cls: 'mm-status-paused' },
  broken: { label: '中断', cls: 'mm-status-broken' },
  stopped: { label: '已停止', cls: 'mm-status-stopped' },
  canceled: { label: '已取消', cls: 'mm-status-stopped' },
  finished: { label: '完成', cls: 'mm-status-finished' },
  completed: { label: '完成', cls: 'mm-status-finished' },
  failed: { label: '失败', cls: 'mm-status-failed' },
}
const statusLabel = computed(() => STATUS_LABEL[taskStatus.value]?.label || taskStatus.value || '未知')
const statusClass = computed(() => STATUS_LABEL[taskStatus.value]?.cls || 'mm-status-draft')
const taskStatusReason = computed(() => taskStatus.value === 'failed' ? (props.record.taskStatusReason || '') : '')

const lastUpdateText = computed(() => {
  const t = props.record.lastProgressTime
  if (!t) return ''
  const elapsed = Date.now() - t
  if (elapsed < 60_000) return `${Math.floor(elapsed / 1000)}s 前`
  return `${Math.floor(elapsed / 60_000)}min 前`
})

async function refresh () {
  try {
    const res = await getPlannedWayline(props.workspaceId, props.record.plannedWaylineId)
    if (res?.data) emit('change', res.data as PlannedWaylineRecord)
  } catch (e: any) {
    // silent during poll
  }
}

function startPolling () {
  if (pollTimer != null) return
  const interval = props.pollIntervalMs ?? 2000
  pollTimer = window.setInterval(refresh, interval)
}
function stopPolling () {
  if (pollTimer != null) { window.clearInterval(pollTimer); pollTimer = null }
}

async function wrap (fn: () => Promise<any>, label: string) {
  if (busy.value) return
  busy.value = true
  try {
    const res = await fn()
    if (res?.data) emit('change', res.data as PlannedWaylineRecord)
    message.success(label + ' 已发出')
  } catch (e: any) {
    message.error(label + ' 失败: ' + (e?.response?.data?.message || e?.message || e))
  } finally {
    busy.value = false
  }
}
function onExecute () {
  emit('execute', props.record)
}
function onPause () { wrap(() => pausePlannedWaylineTask(props.workspaceId, props.record.plannedWaylineId), '暂停') }
function onRecovery () {
  wrap(async () => {
    if (canResumeBreakpoint.value) {
      try { await queryPlannedWaylineBreakpoint(props.workspaceId, props.record.plannedWaylineId) } catch {}
    }
    return recoveryPlannedWaylineTask(props.workspaceId, props.record.plannedWaylineId)
  }, '恢复')
}
function onStop () { wrap(() => stopPlannedWaylineTask(props.workspaceId, props.record.plannedWaylineId), '中止') }
function onCancel () { wrap(() => cancelPlannedWaylineTask(props.workspaceId, props.record.plannedWaylineId), '取消') }

onMounted(startPolling)
onBeforeUnmount(stopPolling)
</script>

<style scoped>
.wayline-mission-monitor {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px;
  background: #1f1f1f;
  border-radius: 4px;
}
.mm-row {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.mm-status-row {
  font-size: 12px;
}
.mm-status-badge {
  padding: 1px 8px;
  border-radius: 2px;
  font-size: 11px;
  font-weight: 600;
}
.mm-status-draft     { background: #595959; color: #fff; }
.mm-status-ready     { background: #389e0d; color: #fff; }
.mm-status-publishing{ background: #1890ff; color: #fff; }
.mm-status-executing { background: #fa8c16; color: #fff; }
.mm-status-paused    { background: #faad14; color: #000; }
.mm-status-broken    { background: #cf1322; color: #fff; }
.mm-status-stopped   { background: #595959; color: #fff; }
.mm-status-finished  { background: #135200; color: #fff; }
.mm-status-failed    { background: #cf1322; color: #fff; }
.mm-status-reason {
  font-size: 11px;
  color: #ff7875;
}
/* a-progress 默认百分比文字是深色，在深色卡片上看不见，改白色 */
.wayline-mission-monitor :deep(.ant-progress-text) {
  color: #fff;
}
.mm-meta-row {
  font-size: 11px;
  color: #aaa;
}
.mm-meta-time {
  margin-left: auto;
  font-style: italic;
}
.mm-action-row {
  margin-top: 4px;
  gap: 6px;
}
</style>
