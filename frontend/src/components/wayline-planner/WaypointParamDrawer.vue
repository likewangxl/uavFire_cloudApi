<template>
  <a-drawer
    :visible="plannerUi.paramDrawerOpen && !!selectedWaypoint"
    placement="right"
    :width="320"
    :mask="false"
    :closable="true"
    class="wp-param-drawer"
    @close="setParamDrawerOpen(false)">
    <template #title>
      <span class="wp-drawer-title">航点 {{ selectedIndex + 1 }} 参数</span>
    </template>
    <div v-if="selectedWaypoint" class="wp-drawer-body">
      <div class="planning-row">
        <span class="planning-label">坐标 (WGS84)</span>
        <div class="wp-coord">{{ selectedWaypoint.wgsLat.toFixed(6) }}, {{ selectedWaypoint.wgsLng.toFixed(6) }}</div>
      </div>
      <div class="planning-row planning-two-col">
        <div>
          <span class="planning-label">高度 (m)</span>
          <a-input-number
            size="small"
            style="width: 100%;"
            :min="15"
            :step="1"
            :disabled="planningState.executing"
            :value="selectedWaypoint.height"
            @change="onHeightChange" />
        </div>
        <div>
          <span class="planning-label">速度 (m/s)</span>
          <a-input-number
            size="small"
            style="width: 100%;"
            :min="1"
            :max="15"
            :step="0.5"
            :value="selectedWaypoint.speed"
            placeholder="走全局"
            :disabled="planningState.executing"
            @change="(v: any) => updateWaypointField(selectedWaypoint!.id, 'speed', v ?? undefined)" />
        </div>
      </div>
      <div class="planning-row planning-two-col">
        <div>
          <span class="planning-label">云台俯仰°</span>
          <a-input-number
            size="small"
            style="width: 100%;"
            :min="-90"
            :max="30"
            :step="5"
            :value="selectedWaypoint.gimbalPitch"
            placeholder="0"
            :disabled="planningState.executing"
            @change="(v: any) => updateWaypointField(selectedWaypoint!.id, 'gimbalPitch', v ?? undefined)" />
        </div>
        <div>
          <span class="planning-label">云台偏航°</span>
          <a-input-number
            size="small"
            style="width: 100%;"
            :min="-180"
            :max="180"
            :step="5"
            :value="selectedWaypoint.gimbalYaw"
            placeholder="跟随机头"
            :disabled="planningState.executing"
            @change="(v: any) => updateWaypointField(selectedWaypoint!.id, 'gimbalYaw', v ?? undefined)" />
        </div>
      </div>
      <div class="planning-row planning-two-col">
        <div>
          <span class="planning-label">朝向模式</span>
          <a-select
            size="small"
            dropdown-class-name="planner-dark-dropdown"
            style="width: 100%;"
            :value="selectedWaypoint.headingMode"
            placeholder="followWayline"
            allow-clear
            :disabled="planningState.executing"
            @change="(v: any) => updateWaypointField(selectedWaypoint!.id, 'headingMode', v ?? undefined)">
            <a-select-option value="followWayline">followWayline</a-select-option>
            <a-select-option value="smoothTransition">smoothTransition</a-select-option>
            <a-select-option value="fixed">fixed</a-select-option>
            <a-select-option value="towardPOI">towardPOI</a-select-option>
          </a-select>
        </div>
        <div>
          <span class="planning-label">朝向角°</span>
          <a-input-number
            size="small"
            style="width: 100%;"
            :min="-180"
            :max="180"
            :step="5"
            :value="selectedWaypoint.headingAngle"
            placeholder="0 (fixed 用)"
            :disabled="planningState.executing || selectedWaypoint.headingMode !== 'fixed'"
            @change="(v: any) => updateWaypointField(selectedWaypoint!.id, 'headingAngle', v ?? undefined)" />
        </div>
      </div>
      <div class="planning-row planning-two-col">
        <div>
          <span class="planning-label">转弯模式</span>
          <a-select
            size="small"
            dropdown-class-name="planner-dark-dropdown"
            style="width: 100%;"
            :value="selectedWaypoint.turnMode"
            placeholder="默认平滑过弯"
            allow-clear
            :disabled="planningState.executing"
            @change="(v: any) => updateWaypointField(selectedWaypoint!.id, 'turnMode', v ?? undefined)">
            <a-select-option value="coordinateTurn">协调转弯</a-select-option>
            <a-select-option value="toPointAndStopWithDiscontinuityCurvature">停止转弯</a-select-option>
            <a-select-option value="toPointAndStopWithContinuityCurvature">平滑停止</a-select-option>
            <a-select-option value="toPointAndPassWithContinuityCurvature">平滑通过</a-select-option>
          </a-select>
        </div>
        <div>
          <span class="planning-label">转弯阻尼 (m)</span>
          <a-input-number
            size="small"
            style="width: 100%;"
            :min="0"
            :max="500"
            :step="1"
            :value="selectedWaypoint.turnDamping"
            placeholder="10"
            :disabled="planningState.executing"
            @change="(v: any) => updateWaypointField(selectedWaypoint!.id, 'turnDamping', v ?? undefined)" />
        </div>
      </div>
      <div class="planning-row">
        <span class="planning-label">动作 (执行到该航点时)</span>
        <WaypointActionEditor
          :actions="selectedWaypoint.actions"
          @add="(fn: any) => addWaypointAction(selectedWaypoint!.id, fn)"
          @remove="(i: number) => removeWaypointAction(selectedWaypoint!.id, i)"
          @updateParam="(i: number, k: string, v: any) => updateWaypointActionParam(selectedWaypoint!.id, i, k, v)" />
      </div>
      <a-button danger block :disabled="planningState.executing" style="margin-top: 12px;" @click="onRemove">
        删除航点
      </a-button>
    </div>
  </a-drawer>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import {
  addWaypointAction,
  getPlanningStateRaw,
  removeWaypoint,
  removeWaypointAction,
  updateWaypointActionParam,
  updateWaypointField,
  updateWaypointHeight,
} from '/@/hooks/use-wayline-planning'
import { setParamDrawerOpen, usePlannerUi } from '/@/hooks/use-planner-ui'
import WaypointActionEditor from '/@/components/WaypointActionEditor.vue'

const planningState = getPlanningStateRaw()
const plannerUi = usePlannerUi()

const selectedWaypoint = computed(() =>
  planningState.waypoints.find(w => w.id === planningState.selectedWaypointId) || null)
const selectedIndex = computed(() =>
  planningState.waypoints.findIndex(w => w.id === planningState.selectedWaypointId))

function onHeightChange (value: number | string | null) {
  const n = typeof value === 'number' ? value : Number(value)
  if (Number.isFinite(n) && selectedWaypoint.value) {
    updateWaypointHeight(selectedWaypoint.value.id, n)
  }
}

function onRemove () {
  if (selectedWaypoint.value) {
    removeWaypoint(selectedWaypoint.value.id)
    setParamDrawerOpen(false)
  }
}
</script>

<style lang="scss">
/* drawer teleport 到 body，样式不能 scoped */
.wp-param-drawer {
  .ant-drawer-content {
    background: rgba(13, 17, 23, 0.96);
    color: #cfd8e3;
  }
  .ant-drawer-header {
    background: transparent;
    border-bottom: 1px solid #2c3a4f;
  }
  .wp-drawer-title {
    color: #e8eef6;
    font-weight: 600;
  }
  .ant-drawer-close {
    color: #8ca0b8;
  }
  .planning-row {
    margin-bottom: 10px;
  }
  .planning-two-col {
    display: flex;
    gap: 6px;
    > div {
      flex: 1;
    }
  }
  .planning-label {
    display: block;
    color: #7d8ca0;
    margin-bottom: 4px;
    font-size: 12px;
  }
  .wp-coord {
    padding: 4px 8px;
    background: #182230;
    border-radius: 3px;
    color: #bcd;
    font-size: 12px;
  }

  // 深色输入控件（antd2 覆盖）
  .ant-input-number,
  .ant-select:not(.ant-select-customize-input) .ant-select-selector,
  .ant-input {
    background: #182230;
    border-color: #2c3a4f;
    color: #cfd8e3;
    border-radius: 4px;
  }
  .ant-input-number-input {
    color: #cfd8e3;
  }
  .ant-input-number-handler-wrap {
    background: #1d2a3c;
    border-color: #2c3a4f;
  }
  .ant-input-number-handler .anticon,
  .ant-select-arrow,
  .ant-select-clear {
    color: #7d8ca0;
  }
  .ant-select-clear {
    background: #182230;
  }
  .ant-input-number:hover,
  .ant-select:hover .ant-select-selector {
    border-color: #43d675;
  }
  input::placeholder,
  .ant-select-selection-placeholder {
    color: #5c6c80;
  }

  // 按钮统一深色风格
  .ant-btn:not(.ant-btn-dangerous):not(.ant-btn-primary) {
    background: #182230;
    border-color: #2c3a4f;
    color: #cfd8e3;
    border-radius: 4px;
    &:hover {
      border-color: #43d675;
      color: #d7ffe8;
    }
  }
  .ant-btn-dashed {
    border-style: dashed;
    border-color: #3a4d68;
    color: #8ca0b8;
  }
  .ant-btn-dangerous {
    background: rgba(255, 77, 79, 0.1);
    border-color: rgba(255, 77, 79, 0.55);
    color: #ff7875;
    border-radius: 4px;
    &:hover {
      background: rgba(255, 77, 79, 0.2);
      border-color: #ff4d4f;
    }
  }
}

/* select 弹层渲染在 body 上，深色风格全局类（任务参数面板与航点抽屉共用） */
.planner-dark-dropdown {
  background: #10161f;
  border: 1px solid #2c3a4f;
  .ant-select-item {
    color: #cfd8e3;
  }
  .ant-select-item-option-active:not(.ant-select-item-option-disabled) {
    background: #1d2a3c;
  }
  .ant-select-item-option-selected:not(.ant-select-item-option-disabled) {
    background: rgba(67, 214, 117, 0.15);
    color: #d7ffe8;
  }
}
</style>
