<template>
  <div class="operation-action-bar">
    <a-space wrap>
      <a-button
        v-for="action in visibleActions"
        :key="action.id"
        :type="buttonType(action)"
        :danger="action.danger"
        :disabled="action.disabled || Boolean(submittingAction)"
        :loading="submittingAction === action.id"
        :title="action.disabledReason"
        size="small"
        @click="handleClick(action)"
      >
        {{ action.label }}
      </a-button>
    </a-space>

    <a-modal
      v-model:visible="confirmVisible"
      :title="confirmTitle"
      :ok-text="confirmOkText"
      cancel-text="取消"
      :confirm-loading="Boolean(submittingAction)"
      :ok-button-props="confirmOkButtonProps"
      @ok="submitConfirmedAction"
    >
      <div v-if="currentAction" class="operation-danger-confirm">
        <p class="operation-danger-copy">{{ currentAction.consequence }}</p>
        <a-form v-if="reasonRequired" layout="vertical">
          <a-form-item label="原因">
            <a-textarea
              v-model:value="reason"
              :rows="3"
              placeholder="请填写处置原因"
            />
          </a-form-item>
        </a-form>
      </div>
    </a-modal>

    <a-modal
      v-model:visible="assignVisible"
      :title="assignTitle"
      ok-text="提交分配"
      cancel-text="取消"
      :confirm-loading="Boolean(submittingAction)"
      :ok-button-props="{ disabled: !assignForm.resourceSn.trim() }"
      @ok="submitAssignment"
    >
      <a-form layout="vertical" class="operation-assign-form">
        <a-form-item label="选择设备">
          <a-select
            v-model:value="assignForm.resourceSn"
            show-search
            allow-clear
            :loading="devicesLoading"
            :placeholder="assignSnPlaceholder"
            :filter-option="filterDeviceOption"
          >
            <a-select-option
              v-for="device in currentDeviceOptions"
              :key="device.resourceSn"
              :value="device.resourceSn"
              :label="device.optionLabel"
            >
              <div class="operation-device-option">
                <div class="operation-device-main">
                  <strong>{{ device.displayName || device.resourceSn }}</strong>
                  <a-tag :color="onlineTagColor(device)">{{ device.onlineLabel || onlineLabel(device) }}</a-tag>
                </div>
                <small>{{ device.resourceSn }}{{ device.model ? ` · ${device.model}` : '' }}</small>
                <div class="operation-device-metrics">
                  <span>电量 {{ formatBattery(device.batteryPercent) }}</span>
                  <span>RTK {{ formatRtk(device) }}</span>
                  <span>GPS {{ formatGps(device) }}</span>
                  <span>定位 {{ formatPosition(device) }}</span>
                </div>
              </div>
            </a-select-option>
          </a-select>
          <div v-if="devicesLoading" class="operation-form-hint">
            正在刷新设备状态...
          </div>
          <div v-else-if="!currentDeviceOptions.length" class="operation-form-hint">
            暂无可选{{ assignKind === 'DELIVERY' ? 'FC100 投送设备' : '巡检机' }}
          </div>
          <div v-else-if="recentSn" class="operation-form-hint">
            最近使用：<a-button type="link" size="small" @click="assignForm.resourceSn = recentSn">{{ recentSn }}</a-button>
          </div>
        </a-form-item>
        <a-form-item label="角色">
          <a-select v-model:value="assignForm.role">
            <a-select-option
              v-for="role in assignRoles"
              :key="role.value"
              :value="role.value"
            >
              {{ role.label }}
            </a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="备注">
          <a-textarea
            v-model:value="assignForm.remark"
            :rows="2"
            placeholder="可选"
          />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import type { OperationAssignmentDTO, OperationIncidentDTO } from '/@/types/operation/incident'
import type { AssignableDeviceOption } from '/@/types/operation/resource'
import {
  buildIncidentActions,
  requiresActionReason,
  requiresDangerConfirmation,
} from '/@/pages/page-web/projects/operation-policy.mjs'

interface OperationUiAction {
  id: string;
  label: string;
  visible: boolean;
  type?: string;
  danger?: boolean;
  disabled?: boolean;
  disabledReason?: string;
  consequence?: string;
}

const props = defineProps<{
  incident: OperationIncidentDTO | null;
  assignments: OperationAssignmentDTO[];
  submittingAction?: string;
  deviceOptions?: AssignableDeviceOption[];
  devicesLoading?: boolean;
}>()

const emit = defineEmits(['run'])

const confirmVisible = ref(false)
const currentAction = ref<OperationUiAction | null>(null)
const reason = ref('')
const assignVisible = ref(false)
const assignKind = ref<'DELIVERY' | 'MONITOR'>('DELIVERY')
const assignForm = reactive({
  resourceSn: '',
  role: 'DELIVERY_PRIMARY',
  remark: '',
})

const visibleActions = computed<OperationUiAction[]>(() =>
  buildIncidentActions({
    status: props.incident?.status,
    missionStatus: props.incident?.missionStatus,
    assignments: props.assignments,
  }).filter((item: OperationUiAction) => item.visible),
)

const reasonRequired = computed(() =>
  currentAction.value ? requiresActionReason(currentAction.value.id) : false,
)

const confirmTitle = computed(() =>
  currentAction.value ? `确认${currentAction.value.label}` : '确认操作',
)

const confirmOkText = computed(() =>
  currentAction.value?.id === 'DISPATCH' ? '确认派发' : '确认',
)

const confirmOkButtonProps = computed(() => ({
  danger: true,
  disabled: reasonRequired.value && !reason.value.trim(),
}))

const assignTitle = computed(() =>
  assignKind.value === 'DELIVERY' ? '分配 FC100' : '分配巡检机',
)

const assignSnPlaceholder = computed(() =>
  assignKind.value === 'DELIVERY' ? '选择 FC100 投送设备' : '选择 M4T 巡检设备',
)

const currentDeviceOptions = computed(() =>
  (props.deviceOptions || [])
    .filter(device => device.kind === assignKind.value)
    .sort((a, b) => Number(Boolean(b.online)) - Number(Boolean(a.online)) ||
      String(a.displayName || a.resourceSn).localeCompare(String(b.displayName || b.resourceSn))),
)

const assignRoles = computed(() =>
  assignKind.value === 'DELIVERY'
    ? [
        { value: 'DELIVERY_PRIMARY', label: '主投送' },
        { value: 'DELIVERY_BACKUP', label: '备份投送' },
      ]
    : [
        { value: 'MONITOR_PRIMARY', label: '主巡检' },
        { value: 'MONITOR_RECHECK', label: '复测巡检' },
      ],
)

const recentSn = computed(() => readRecentSn(assignKind.value))

function buttonType (action: OperationUiAction) {
  return action.type === 'primary' ? 'primary' : 'default'
}

function handleClick (action: OperationUiAction) {
  if (action.disabled) return
  if (action.id === 'ASSIGN_DELIVERY' || action.id === 'ASSIGN_MONITOR') {
    openAssignment(action.id)
    return
  }
  if (requiresDangerConfirmation(action.id)) {
    currentAction.value = action
    reason.value = ''
    confirmVisible.value = true
    return
  }
  emit('run', { actionId: action.id })
}

function openAssignment (actionId: string) {
  assignKind.value = actionId === 'ASSIGN_DELIVERY' ? 'DELIVERY' : 'MONITOR'
  assignForm.role = assignKind.value === 'DELIVERY' ? 'DELIVERY_PRIMARY' : 'MONITOR_PRIMARY'
  assignForm.resourceSn = readRecentSn(assignKind.value)
  assignForm.remark = ''
  assignVisible.value = true
}

function readRecentSn (kind: string) {
  if (typeof localStorage === 'undefined') return ''
  return localStorage.getItem(`uavfire_operation_recent_${kind.toLowerCase()}_sn`) || ''
}

function filterDeviceOption (input: string, option: any) {
  const label = String(option?.label || '').toLowerCase()
  return label.includes(String(input || '').toLowerCase())
}

function onlineLabel (device: AssignableDeviceOption) {
  if (device.online === true) return '在线'
  if (device.online === false) return '离线'
  return '未知'
}

function onlineTagColor (device: AssignableDeviceOption) {
  if (device.online === true) return 'green'
  if (device.online === false) return 'red'
  return 'default'
}

function formatBattery (value?: number | null) {
  const n = Number(value)
  return Number.isFinite(n) ? `${Math.round(n)}%` : '-'
}

function formatRtk (device: AssignableDeviceOption) {
  if (device.rtkStatus) return device.rtkStatus
  const count = Number(device.rtkCount)
  return Number.isFinite(count) ? `${count}` : '-'
}

function formatGps (device: AssignableDeviceOption) {
  const count = Number(device.gpsCount)
  if (Number.isFinite(count)) return `${count}`
  if (device.positionFixed === true) return '已固定'
  if (device.positionFixed === false) return '未固定'
  return '-'
}

function formatPosition (device: AssignableDeviceOption) {
  const lat = Number(device.latitude)
  const lng = Number(device.longitude)
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) return '-'
  return `${lat.toFixed(5)}, ${lng.toFixed(5)}`
}

function saveRecentSn (kind: string, sn: string) {
  if (typeof localStorage === 'undefined') return
  localStorage.setItem(`uavfire_operation_recent_${kind.toLowerCase()}_sn`, sn)
}

function submitAssignment () {
  const resourceSn = assignForm.resourceSn.trim()
  if (!resourceSn) return
  saveRecentSn(assignKind.value, resourceSn)
  emit('run', {
    actionId: assignKind.value === 'DELIVERY' ? 'ASSIGN_DELIVERY' : 'ASSIGN_MONITOR',
    resourceSn,
    role: assignForm.role,
    remark: assignForm.remark.trim() || undefined,
  })
  assignVisible.value = false
}

function submitConfirmedAction () {
  if (!currentAction.value) return
  if (reasonRequired.value && !reason.value.trim()) return
  emit('run', {
    actionId: currentAction.value.id,
    reason: reason.value.trim() || undefined,
  })
  confirmVisible.value = false
}
</script>

<style lang="scss" scoped>
.operation-action-bar {
  min-width: 0;
}

.operation-danger-copy {
  margin: 0 0 12px;
  line-height: 1.7;
  color: #262626;
}

.operation-form-hint {
  margin-top: 4px;
  color: #6b7280;
  font-size: 12px;
}

.operation-device-option {
  display: grid;
  gap: 4px;
  padding: 2px 0;
}

.operation-device-main {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 8px;

  strong {
    min-width: 0;
    overflow: hidden;
    color: #1f2933;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.operation-device-option small {
  color: #697586;
  overflow-wrap: anywhere;
}

.operation-device-metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
  color: #4b5d72;
  font-size: 12px;
}
</style>
