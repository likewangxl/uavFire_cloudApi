<template>
  <div class="wp-action-editor">
    <div class="wp-action-list" v-if="actions && actions.length > 0">
      <div v-for="(action, idx) in actions" :key="idx" class="wp-action-item">
        <div class="wp-action-head">
          <span class="wp-action-tag">{{ funcLabel(action.actuatorFunc) }}</span>
          <span class="wp-action-summary">{{ summarizeParams(action) }}</span>
          <a-button size="small" type="text" danger @click="$emit('remove', idx)">✕</a-button>
        </div>
        <div class="wp-action-params" v-if="expandedIdx === idx">
          <div v-for="key in editableParamKeys(action.actuatorFunc)" :key="key" class="wp-action-param-row">
            <span class="wp-action-param-label">{{ key }}</span>
            <a-input
              v-if="isStringParam(key)"
              size="small"
              :value="String(action.params?.[key] ?? '')"
              @change="(e: any) => $emit('updateParam', idx, key, e.target.value)" />
            <a-select
              v-else-if="key === 'gimbalRotateMode'"
              size="small"
              :value="action.params?.[key] ?? 'absoluteAngle'"
              @change="(v: any) => $emit('updateParam', idx, key, v)">
              <a-select-option value="absoluteAngle">absoluteAngle</a-select-option>
              <a-select-option value="relativeAngle">relativeAngle</a-select-option>
            </a-select>
            <a-select
              v-else-if="key === 'aircraftPathMode'"
              size="small"
              :value="action.params?.[key] ?? 'clockwise'"
              @change="(v: any) => $emit('updateParam', idx, key, v)">
              <a-select-option value="clockwise">clockwise</a-select-option>
              <a-select-option value="counterClockwise">counterClockwise</a-select-option>
            </a-select>
            <a-input-number
              v-else
              size="small"
              :value="Number(action.params?.[key] ?? 0)"
              @change="(v: any) => $emit('updateParam', idx, key, v)" />
          </div>
        </div>
        <a-button size="small" type="link" @click="toggle(idx)">
          {{ expandedIdx === idx ? '收起' : '展开参数' }}
        </a-button>
      </div>
    </div>
    <div class="wp-action-empty" v-else>无动作。</div>
    <a-dropdown trigger="click">
      <a-button size="small" type="dashed">+ 添加动作</a-button>
      <template #overlay>
        <a-menu @click="(e: any) => $emit('add', e.key)">
          <a-menu-item key="takePhoto">拍照</a-menu-item>
          <a-menu-item key="startRecord">开始录像</a-menu-item>
          <a-menu-item key="stopRecord">结束录像</a-menu-item>
          <a-menu-item key="gimbalRotate">云台旋转</a-menu-item>
          <a-menu-item key="hover">悬停</a-menu-item>
          <a-menu-item key="focus">对焦</a-menu-item>
          <a-menu-item key="rotateYaw">飞机偏航</a-menu-item>
        </a-menu>
      </template>
    </a-dropdown>
  </div>
</template>

<script lang="ts" setup>
import { ref } from 'vue'
import type { WaypointAction, WaypointActuatorFunc } from '/@/types/wayline'

defineProps<{
  actions?: WaypointAction[]
}>()

defineEmits<{
  (e: 'add', actuatorFunc: WaypointActuatorFunc): void
  (e: 'remove', actionIdx: number): void
  (e: 'updateParam', actionIdx: number, paramKey: string, value: any): void
}>()

const expandedIdx = ref<number | null>(null)

function toggle (idx: number) {
  expandedIdx.value = expandedIdx.value === idx ? null : idx
}

const FUNC_LABEL: Record<string, string> = {
  takePhoto: '拍照',
  startRecord: '开始录像',
  stopRecord: '结束录像',
  gimbalRotate: '云台',
  hover: '悬停',
  focus: '对焦',
  rotateYaw: '偏航',
}

function funcLabel (fn?: string): string {
  return FUNC_LABEL[fn || ''] || fn || '?'
}

const PARAM_KEYS: Record<string, string[]> = {
  takePhoto: ['fileSuffix', 'payloadPositionIndex'],
  startRecord: ['fileSuffix', 'payloadPositionIndex'],
  stopRecord: ['payloadPositionIndex'],
  gimbalRotate: [
    'gimbalRotateMode',
    'gimbalPitchRotateEnable', 'gimbalPitchRotateAngle',
    'gimbalYawRotateEnable', 'gimbalYawRotateAngle',
    'gimbalRotateTimeEnable', 'gimbalRotateTime',
    'payloadPositionIndex',
  ],
  hover: ['hoverTime'],
  focus: ['payloadPositionIndex', 'isPointFocus', 'focusX', 'focusY'],
  rotateYaw: ['aircraftHeading', 'aircraftPathMode'],
}

function editableParamKeys (fn?: string): string[] {
  return PARAM_KEYS[fn || ''] || []
}

function isStringParam (key: string): boolean {
  return key === 'fileSuffix'
}

function summarizeParams (action: WaypointAction): string {
  const p = action.params || {}
  switch (action.actuatorFunc) {
    case 'takePhoto':
    case 'startRecord':
      return p.fileSuffix ? `后缀=${p.fileSuffix}` : ''
    case 'gimbalRotate':
      return `pitch=${p.gimbalPitchRotateAngle ?? 0}° yaw=${p.gimbalYawRotateAngle ?? 0}°`
    case 'hover':
      return `${p.hoverTime ?? 0}s`
    case 'focus':
      return `(${p.focusX ?? 0}, ${p.focusY ?? 0})`
    case 'rotateYaw':
      return `${p.aircraftHeading ?? 0}°`
    default:
      return ''
  }
}
</script>

<style scoped>
.wp-action-editor {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 6px 0;
}
.wp-action-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.wp-action-item {
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid #444;
  border-radius: 3px;
  padding: 4px 6px;
}
.wp-action-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.wp-action-tag {
  background: #1890ff;
  color: white;
  padding: 1px 6px;
  border-radius: 2px;
  font-size: 11px;
  flex-shrink: 0;
}
.wp-action-summary {
  flex: 1;
  font-size: 12px;
  color: #aaa;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.wp-action-params {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 6px 4px;
  border-top: 1px dashed #444;
  margin-top: 4px;
}
.wp-action-param-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px;
  align-items: center;
}
.wp-action-param-label {
  font-size: 11px;
  color: #888;
  text-align: right;
}
.wp-action-empty {
  font-size: 12px;
  color: #666;
  padding: 4px 0;
}
</style>
