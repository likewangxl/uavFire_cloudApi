<template>
  <div class="wp-list-panel" v-if="planningState.active || planningState.waypoints.length > 0">
    <div class="wp-list-head">
      <span>航点列表 <b>{{ planningState.waypoints.length }}</b></span>
      <span class="wp-list-hint" v-if="planningState.active">地图点击添加航点中</span>
    </div>
    <div class="wp-list-body">
      <div
        v-for="(wp, idx) in planningState.waypoints"
        :key="wp.id"
        class="wp-card"
        :class="{
          selected: planningState.selectedWaypointId === wp.id,
          active: planningState.executing && planningState.currentIndex === idx,
        }"
        @click="onSelect(wp.id)">
        <span class="wp-index">{{ idx + 1 }}</span>
        <span class="wp-meta">
          {{ formatNumber(wp.height) }}m · {{ formatNumber(wp.speed || planningState.maxSpeed) }}m/s
          <small v-if="actionsSummary(wp)"> · {{ actionsSummary(wp) }}</small>
        </span>
        <span class="wp-card-tools" @click.stop>
          <a-button size="small" type="text" :disabled="planningState.executing || idx === 0" @click="moveWaypoint(wp.id, 'up')">↑</a-button>
          <a-button size="small" type="text" :disabled="planningState.executing || idx === planningState.waypoints.length - 1" @click="moveWaypoint(wp.id, 'down')">↓</a-button>
          <a-button size="small" type="text" danger :disabled="planningState.executing" @click="removeWaypoint(wp.id)">✕</a-button>
        </span>
      </div>
      <div v-if="planningState.waypoints.length === 0" class="wp-empty">暂无航点，开始布点后在地图上点击添加</div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { getPlanningStateRaw, moveWaypoint, removeWaypoint, selectWaypoint } from '/@/hooks/use-wayline-planning'
import type { PlannedWaypoint } from '/@/hooks/use-wayline-planning'
import { setParamDrawerOpen } from '/@/hooks/use-planner-ui'
import { formatNumber } from './wayline-format'

const planningState = getPlanningStateRaw()

const ACTION_LABELS: Record<string, string> = {
  takePhoto: '拍照',
  startRecord: '录像',
  stopRecord: '停录',
  gimbalRotate: '云台',
  hover: '悬停',
  focus: '对焦',
  rotateYaw: '转向',
}

function actionsSummary (wp: PlannedWaypoint) {
  if (!wp.actions || wp.actions.length === 0) return ''
  return wp.actions.map(a => ACTION_LABELS[a.actuatorFunc] || a.actuatorFunc).join('·')
}

function onSelect (id: string) {
  selectWaypoint(id)
  setParamDrawerOpen(true)
}
</script>

<style lang="scss" scoped>
.wp-list-panel {
  background: rgba(13, 17, 23, 0.93);
  border: 1px solid #2c3a4f;
  border-radius: 6px;
  padding: 8px;
  color: #cfd8e3;
  font-size: 12px;
  pointer-events: auto;
  max-height: 46vh;
  display: flex;
  flex-direction: column;
}
.wp-list-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  color: #e8eef6;
  font-weight: 600;
  margin-bottom: 6px;
  b {
    color: #4da3ff;
  }
}
.wp-list-hint {
  color: #43d675;
  font-weight: 400;
  font-size: 11px;
}
.wp-list-body {
  overflow-y: auto;
}
.wp-card {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 6px;
  margin-bottom: 5px;
  background: #182230;
  border: 1px solid #28364a;
  border-radius: 4px;
  cursor: pointer;
  &.selected {
    border-color: #43d675;
  }
  &.active {
    border-color: #faad14;
    background: #3a2f1a;
  }
}
.wp-index {
  flex: 0 0 auto;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: #10243a;
  border: 1.5px solid #43d675;
  color: #d7ffe8;
  font-size: 11px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}
.wp-meta {
  flex: 1;
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  small {
    color: #7d8ca0;
  }
}
.wp-card-tools {
  flex: 0 0 auto;
  display: flex;
  .ant-btn {
    min-width: 22px;
    padding: 0 4px;
    color: #8ca0b8;
  }
}
.wp-empty {
  padding: 10px;
  text-align: center;
  color: #7d8ca0;
}
</style>
