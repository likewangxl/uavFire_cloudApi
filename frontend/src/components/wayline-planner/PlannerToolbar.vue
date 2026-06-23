<template>
  <div class="planner-toolbar-wrap">
    <div class="planning-compliance-chip" v-if="compliance.hasViolation">
      ⚠ 合规告警：{{ compliance.messages.join('，') }}
    </div>
    <div class="planning-status-chip" v-if="planningState.statusText">{{ planningState.statusText }}</div>
    <div class="planner-toolbar">
      <a-button
        v-if="!planningState.active"
        size="small"
        type="primary"
        :disabled="planningState.executing"
        @click="onStartPlacing">
        ＋航点
      </a-button>
      <a-button v-else size="small" @click="onStopPlacing">停止布点</a-button>
      <a-button
        size="small"
        :disabled="planningState.executing || planningState.waypoints.length === 0"
        @click="onUndo">
        撤销
      </a-button>
      <a-popconfirm
        title="清空全部航点？"
        ok-text="清空"
        cancel-text="取消"
        :disabled="planningState.executing || planningState.waypoints.length === 0"
        @confirm="clearWaypoints">
        <a-button size="small" :disabled="planningState.executing || planningState.waypoints.length === 0">清空</a-button>
      </a-popconfirm>
      <a-button
        size="small"
        :disabled="planningState.executing || planningState.waypoints.length === 0"
        @click="onSave(false)">
        保存
      </a-button>
      <a-button
        size="small"
        :disabled="planningState.executing || planningState.waypoints.length === 0"
        @click="onSave(true)">
        另存为
      </a-button>
      <a-button
        v-if="!planningState.executing"
        size="small"
        type="primary"
        :disabled="planningState.waypoints.length === 0 || !canExecute"
        @click="onStartExecution">
        下发执行
      </a-button>
      <a-button v-else size="small" danger @click="onStopExecution">停止执行</a-button>
      <a-button
        size="small"
        :disabled="planningState.executing || planningState.waypoints.length < 2"
        @click="startSimulation">
        模拟预演
      </a-button>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { clearWaypoints, getPlanningStateRaw, removeWaypoint } from '/@/hooks/use-wayline-planning'
import { startSimulation } from '/@/hooks/use-planner-ui'
import { flightAreaCompliance } from '/@/hooks/use-flight-area-compliance'

const planningState = getPlanningStateRaw()
const compliance = flightAreaCompliance

defineProps<{
  canExecute: boolean
  onStartPlacing:() => void
  onStopPlacing: () => void
  onStartExecution: () => void
  onStopExecution: () => void
  onSave: (saveAs: boolean) => void
}>()

// 撤销 = 删除最后一个航点（与设计文档约定一致）
function onUndo () {
  const last = planningState.waypoints[planningState.waypoints.length - 1]
  if (last) removeWaypoint(last.id)
}
</script>

<style lang="scss" scoped>
.planner-toolbar-wrap {
  position: absolute;
  bottom: 14px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  pointer-events: auto;
  z-index: 30;
}
.planning-status-chip {
  padding: 3px 12px;
  background: rgba(13, 17, 23, 0.9);
  border: 1px solid #2c3a4f;
  border-radius: 12px;
  color: #faad14;
  font-size: 11px;
  max-width: 60vw;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.planning-compliance-chip {
  padding: 3px 12px;
  background: rgba(74, 14, 14, 0.92);
  border: 1px solid #cf1322;
  border-radius: 12px;
  color: #ff7875;
  font-size: 11px;
  font-weight: 600;
  max-width: 60vw;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.planner-toolbar {
  display: flex;
  gap: 8px;
  padding: 6px 12px;
  background: rgba(13, 17, 23, 0.93);
  border: 1px solid #2c3a4f;
  border-radius: 6px;
}
</style>
