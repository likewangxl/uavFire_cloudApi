<template>
  <div class="area-params-panel">
    <div class="ap-head">
      <BorderOutlined />
      <span>面状测区</span>
      <span class="ap-summary">{{ planningState.areaPolygon.length }} 顶点</span>
    </div>
    <div class="ap-body">
      <div class="planning-row planning-two-col">
        <div>
          <span class="planning-label">相机/载荷</span>
          <a-select
            size="small"
            dropdown-class-name="planner-dark-dropdown"
            style="width: 100%;"
            :value="planningState.areaParams.cameraKey"
            :disabled="planningState.executing"
            @change="(v: any) => (planningState.areaParams.cameraKey = v)">
            <a-select-option v-for="c in cameraPresets" :key="c.key" :value="c.key">{{ c.label }}</a-select-option>
          </a-select>
        </div>
        <div>
          <span class="planning-label">航向角 (°)</span>
          <a-input-number
            size="small"
            style="width: 100%;"
            :min="0"
            :max="359"
            :step="5"
            :disabled="planningState.executing"
            :value="planningState.areaParams.headingDeg"
            @change="(v: any) => (planningState.areaParams.headingDeg = Number(v) || 0)" />
        </div>
      </div>
      <div class="planning-row planning-two-col">
        <div>
          <span class="planning-label">航向重叠 (%)</span>
          <a-input-number
            size="small"
            style="width: 100%;"
            :min="0"
            :max="90"
            :step="5"
            :disabled="planningState.executing"
            :value="planningState.areaParams.frontOverlap"
            @change="(v: any) => (planningState.areaParams.frontOverlap = clampPct(v))" />
        </div>
        <div>
          <span class="planning-label">旁向重叠 (%)</span>
          <a-input-number
            size="small"
            style="width: 100%;"
            :min="0"
            :max="90"
            :step="5"
            :disabled="planningState.executing"
            :value="planningState.areaParams.sideOverlap"
            @change="(v: any) => (planningState.areaParams.sideOverlap = clampPct(v))" />
        </div>
      </div>
      <div class="ap-estimate">
        行间距 <b>{{ estimate.lineSpacingM }}m</b>
        · 拍照间隔 <b>{{ estimate.shotIntervalM }}m</b>
        · 地面足迹 <b>{{ estimate.footprintW }}×{{ estimate.footprintH }}m</b>
      </div>
      <div class="ap-actions">
        <a-button size="small" type="primary" :disabled="planningState.executing || planningState.areaPolygon.length < 3" @click="onGenerate">
          生成航点
        </a-button>
        <a-button size="small" :disabled="planningState.executing || planningState.areaPolygon.length === 0" @click="removeLastAreaVertex">撤销顶点</a-button>
        <a-button size="small" :disabled="planningState.executing || planningState.areaPolygon.length === 0" @click="clearAreaPolygon">清空测区</a-button>
      </div>
      <div class="ap-hint">在地图上点击落测区顶点（右键删点）；≥3 个顶点后点「生成航点」。</div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import { message } from 'ant-design-vue'
import { BorderOutlined } from '@ant-design/icons-vue'
import {
  getPlanningStateRaw,
  generateAreaWaypoints,
  removeLastAreaVertex,
  clearAreaPolygon,
} from '/@/hooks/use-wayline-planning'
// @ts-ignore .mjs 纯计算模块
import { CAMERA_PRESETS, getCameraPreset, cameraFootprint, lineSpacingFromOverlap, shotIntervalFromOverlap } from './area-utils.mjs'

const planningState = getPlanningStateRaw()
const cameraPresets = CAMERA_PRESETS

function clampPct (v: any): number {
  const n = Number(v)
  if (!Number.isFinite(n)) return 0
  return Math.min(90, Math.max(0, n))
}

const estimate = computed(() => {
  const cam = getCameraPreset(planningState.areaParams.cameraKey)
  const h = Number(planningState.defaultHeight) || 0
  const fp = cameraFootprint(cam, h)
  return {
    lineSpacingM: lineSpacingFromOverlap(cam, h, planningState.areaParams.sideOverlap).toFixed(0),
    shotIntervalM: shotIntervalFromOverlap(cam, h, planningState.areaParams.frontOverlap).toFixed(0),
    footprintW: fp.widthM.toFixed(0),
    footprintH: fp.heightM.toFixed(0),
  }
})

function onGenerate () {
  const n = generateAreaWaypoints()
  if (n > 0) message.success(`已生成 ${n} 个航点`)
}
</script>

<style lang="scss" scoped>
.area-params-panel {
  background: rgba(13, 17, 23, 0.93);
  border: 1px solid #2c3a4f;
  border-radius: 6px;
  color: #cfd8e3;
  font-size: 12px;
  pointer-events: auto;
}
.ap-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  color: #e8eef6;
  font-weight: 600;
  border-bottom: 1px solid #2c3a4f;
}
.ap-summary {
  flex: 1;
  text-align: right;
  color: #7d8ca0;
  font-weight: 400;
}
.ap-body {
  padding: 8px 10px 10px;
}
.planning-row {
  margin-bottom: 8px;
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
.ap-estimate {
  margin: 2px 0 8px;
  padding: 5px 8px;
  background: #11202f;
  border: 1px solid #21405c;
  border-radius: 4px;
  color: #9fb4cc;
  b {
    color: #4d9bff;
  }
}
.ap-actions {
  display: flex;
  gap: 6px;
}
.ap-hint {
  margin-top: 8px;
  color: #7d8ca0;
  line-height: 1.5;
}
// 深色输入控件（antd2 覆盖）
:deep(.ant-input-number),
:deep(.ant-select:not(.ant-select-customize-input) .ant-select-selector) {
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
:deep(.ant-select-arrow) {
  color: #7d8ca0;
}
:deep(.ant-input-number:hover),
:deep(.ant-select:hover .ant-select-selector) {
  border-color: #43d675;
}
</style>
