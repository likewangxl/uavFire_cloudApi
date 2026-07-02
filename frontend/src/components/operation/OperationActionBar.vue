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
        <a-form-item label="设备 SN">
          <a-input
            v-model:value="assignForm.resourceSn"
            :placeholder="assignSnPlaceholder"
            allow-clear
          />
          <div v-if="recentSn" class="operation-form-hint">
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
  assignKind.value === 'DELIVERY' ? '例如 FC100-DELIVERY-01' : '例如 M4T-MONITOR-01',
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
</style>
