<template>
  <div class="mission-params-panel">
    <button type="button" class="mp-head" @click="open = !open">
      <span>任务参数</span>
      <span class="mp-summary">高 {{ planningState.defaultHeight }}m · 速 {{ planningState.maxSpeed }}m/s</span>
      <DownOutlined :class="{ 'mp-arrow': true, 'mp-arrow--open': open }" />
    </button>
    <div v-if="open" class="mp-body">
      <div class="planning-row planning-two-col">
        <div>
          <span class="planning-label">默认高度 (m)</span>
          <a-input-number
            size="small"
            style="width: 100%;"
            :min="15"
            :step="1"
            :disabled="planningState.executing"
            :value="planningState.defaultHeight"
            @change="onDefaultHeightChange" />
        </div>
        <div>
          <span class="planning-label">最大速度 (m/s)</span>
          <a-input-number
            size="small"
            style="width: 100%;"
            :min="2"
            :max="15"
            :step="1"
            :disabled="planningState.executing"
            :value="planningState.maxSpeed"
            @change="onMaxSpeedChange" />
        </div>
      </div>
      <div class="planning-row planning-two-col">
        <div>
          <span class="planning-label">完成动作</span>
          <a-select
            size="small"
            dropdown-class-name="planner-dark-dropdown"
            style="width: 100%;"
            :value="planningState.finishAction"
            placeholder="返航 (默认)"
            allow-clear
            :disabled="planningState.executing"
            @change="(v: any) => setMissionConfig({ finishAction: v })">
            <a-select-option value="goHome">返航</a-select-option>
            <a-select-option value="autoLand">原地降落</a-select-option>
            <a-select-option value="noAction">不动作</a-select-option>
            <a-select-option value="gotoFirstWaypoint">回到首点</a-select-option>
          </a-select>
        </div>
        <div>
          <span class="planning-label">RC 失联</span>
          <a-select
            size="small"
            dropdown-class-name="planner-dark-dropdown"
            style="width: 100%;"
            :value="planningState.exitOnRcLost"
            placeholder="继续飞 (默认)"
            allow-clear
            :disabled="planningState.executing"
            @change="(v: any) => setMissionConfig({ exitOnRcLost: v })">
            <a-select-option value="goContinue">继续飞</a-select-option>
            <a-select-option value="executeLostAction">执行失联动作</a-select-option>
          </a-select>
        </div>
      </div>
      <div class="planning-row planning-two-col">
        <div>
          <span class="planning-label">失联动作</span>
          <a-select
            size="small"
            dropdown-class-name="planner-dark-dropdown"
            style="width: 100%;"
            :value="planningState.rcLostAction"
            placeholder="返航 (默认)"
            allow-clear
            :disabled="planningState.executing"
            @change="(v: any) => setMissionConfig({ rcLostAction: v })">
            <a-select-option value="hover">悬停</a-select-option>
            <a-select-option value="goBack">返航</a-select-option>
            <a-select-option value="landing">降落</a-select-option>
          </a-select>
        </div>
        <div>
          <span class="planning-label">起飞安全高度 (m)</span>
          <a-input-number
            size="small"
            style="width: 100%;"
            :min="2"
            :max="1500"
            :step="1"
            :value="planningState.takeoffSecurityHeight"
            placeholder="20 (默认)"
            :disabled="planningState.executing"
            @change="(v: any) => setMissionConfig({ takeoffSecurityHeight: v })" />
        </div>
      </div>
      <div class="planning-row planning-two-col">
        <div>
          <span class="planning-label">转场速度 (m/s)</span>
          <a-input-number
            size="small"
            style="width: 100%;"
            :min="1"
            :max="15"
            :step="1"
            :value="planningState.globalTransitionalSpeed"
            placeholder="5 (默认)"
            :disabled="planningState.executing"
            @change="(v: any) => setMissionConfig({ globalTransitionalSpeed: v })" />
        </div>
        <div>
          <span class="planning-label">RTH 高度 (m, 仅 dock)</span>
          <a-input-number
            size="small"
            style="width: 100%;"
            :min="2"
            :max="1500"
            :step="1"
            :value="planningState.rthAltitude"
            placeholder="飞机默认"
            :disabled="planningState.executing"
            @change="(v: any) => setMissionConfig({ rthAltitude: v })" />
        </div>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { ref } from 'vue'
import { DownOutlined } from '@ant-design/icons-vue'
import { getPlanningStateRaw, setMissionConfig } from '/@/hooks/use-wayline-planning'

const planningState = getPlanningStateRaw()
const open = ref(false)

function onDefaultHeightChange (value: number | string | null) {
  const n = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(n) || n <= 0) return
  const previousDefaultHeight = Number(planningState.defaultHeight)
  planningState.defaultHeight = n
  // 跟随默认高度的航点(未单独改过的)一并更新
  planningState.waypoints.forEach(wp => {
    if (!Number.isFinite(previousDefaultHeight) || Number(wp.height) === previousDefaultHeight) {
      wp.height = n
    }
  })
}

function onMaxSpeedChange (value: number | string | null) {
  const n = typeof value === 'number' ? value : Number(value)
  if (Number.isFinite(n) && n > 0) {
    planningState.maxSpeed = n
  }
}
</script>

<style lang="scss" scoped>
.mission-params-panel {
  background: rgba(13, 17, 23, 0.93);
  border: 1px solid #2c3a4f;
  border-radius: 6px;
  color: #cfd8e3;
  font-size: 12px;
  pointer-events: auto;
}
.mp-head {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  background: transparent;
  border: none;
  color: #e8eef6;
  font-weight: 600;
  font-size: 12px;
  cursor: pointer;
}
.mp-summary {
  flex: 1;
  text-align: right;
  color: #7d8ca0;
  font-weight: 400;
}
.mp-arrow {
  transition: transform 0.2s;
}
.mp-arrow--open {
  transform: rotate(180deg);
}
.mp-body {
  padding: 4px 10px 10px;
}
.planning-row {
  margin-bottom: 8px;
}
.planning-row:last-child {
  margin-bottom: 0;
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
  color: #cfd8e3;
  margin-bottom: 4px;
}
// 深色输入控件（antd2 覆盖）
:deep(.ant-input-number),
:deep(.ant-select:not(.ant-select-customize-input) .ant-select-selector),
:deep(.ant-input) {
  background: #182230;
  border-color: #2c3a4f;
  color: #cfd8e3;
  border-radius: 4px;
}
:deep(.ant-input-number-input) {
  color: #cfd8e3;
}
:deep(.ant-input-number-handler-wrap) {
  background: #1d2a3c;
  border-color: #2c3a4f;
}
:deep(.ant-input-number-handler .anticon),
:deep(.ant-select-arrow),
:deep(.ant-select-clear) {
  color: #7d8ca0;
}
:deep(.ant-select-clear) {
  background: #182230;
}
:deep(.ant-input-number:hover),
:deep(.ant-select:hover .ant-select-selector) {
  border-color: #43d675;
}
:deep(input::placeholder),
:deep(.ant-select-selection-placeholder) {
  color: #5c6c80;
}
</style>
