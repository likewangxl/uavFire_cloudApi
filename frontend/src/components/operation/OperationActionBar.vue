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
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
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

const visibleActions = computed<OperationUiAction[]>(() =>
  buildIncidentActions({
    status: props.incident?.status,
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

function buttonType (action: OperationUiAction) {
  return action.type === 'primary' ? 'primary' : 'default'
}

function handleClick (action: OperationUiAction) {
  if (action.disabled) return
  if (requiresDangerConfirmation(action.id)) {
    currentAction.value = action
    reason.value = ''
    confirmVisible.value = true
    return
  }
  emit('run', { actionId: action.id })
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
</style>
