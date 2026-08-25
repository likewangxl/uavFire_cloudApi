<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import { missionApi } from '/@/api/fire/mission'
import type { MissionApproveBody } from '/@/api/fire/mission'
import { waypointApi } from '/@/api/fire/waypoint'
import { routeApi } from '/@/api/fire/route'
import { payloadApi } from '/@/api/fire/payload'
import { reviewApi } from '/@/api/fire/review'
import { deliveryApi } from '/@/api/fire/delivery'

const props = defineProps<{
  missionNo: string;
  actions: string[];
  onlyActions?: string[];
  excludeActions?: string[];
}>()
const emit = defineEmits<{ refresh: [] }>()
const router = useRouter()
const routePreparationActions = new Set(['GEN_WP', 'EXP_KMZ', 'CREATE_DELIVERY_TASK'])

// ---- Approve modal state ----
const approveVisible = ref(false)
const approveForm = ref<MissionApproveBody>({
  operatorId: 'test-operator',
  aircraftSn: '',
  waterLoadLiters: undefined,
  takeoffLat: 0,
  takeoffLng: 0,
  takeoffAlt: undefined,
  windSpeed: 0,
  windDirectionDeg: 0,
  remark: '',
})

// ---- Reject modal state ----
const rejectVisible = ref(false)
const rejectReason = ref('')

// ---- Cancel modal state ----
const cancelVisible = ref(false)
const cancelReason = ref('')

// ---- Takeover / FORCE_FAIL modal state ----
const takeoverVisible = ref(false)
const takeoverAction = ref('')
const takeoverReason = ref('')

// ---- Resolve-takeover modal state ----
const resolveVisible = ref(false)
const resolveResult = ref<'OK' | 'FAILED'>('OK')
const resolveRemark = ref('')

const labelMap: Record<string, { label: string; danger?: boolean; secondary?: boolean }> = {
  APPROVE: { label: '审批' },
  REJECT: { label: '驳回', danger: true },
  CANCEL: { label: '取消', danger: true },
  PREPARE_FC100_DELIVERY: { label: '生成航线并推送FC100' },
  GEN_WP: { label: '生成航点' },
  EXP_KMZ: { label: '导出 KMZ' },
  CREATE_DELIVERY_TASK: { label: '推送 Delivery' },
  START_DELIVERY: { label: '启动任务' },
  MARK_RELEASE_PENDING: { label: '到达投放点' },
  CONFIRM_RELEASE: { label: '确认投放', danger: true },
  MARK_RELEASE_FAILED: { label: '投放失败', danger: true },
  RETRY_RELEASE: { label: '重试投放' },
  MARK_RETURNING: { label: '开始返航' },
  MARK_RETURN_COMPLETED: { label: '返航完成' },
  MARK_RETURN_FAILED: { label: '返航失败', danger: true },
  TAKEOVER: { label: '人工接管', danger: true },
  RESOLVE_TAKEOVER_OK: { label: '接管成功' },
  RESOLVE_TAKEOVER_FAILED: { label: '接管失败', danger: true },
  SUBMIT_REVIEW: { label: '提交复查' },
  ARCHIVE: { label: '归档' },
  FORCE_FAIL: { label: '管理员中止', danger: true, secondary: true },
}

const visibleActions = computed(() => {
  const only = new Set(props.onlyActions ?? [])
  const exclude = new Set(props.excludeActions ?? [])
  const actions = props.actions.filter(a =>
    !routePreparationActions.has(a) &&
    (!only.size || only.has(a)) &&
    !exclude.has(a))
  if (props.actions.some(a => routePreparationActions.has(a)) || props.actions.includes('APPROVE')) {
    if ((!only.size || only.has('PREPARE_FC100_DELIVERY')) && !exclude.has('PREPARE_FC100_DELIVERY')) {
      actions.unshift('PREPARE_FC100_DELIVERY')
    }
  }
  return actions.sort((a, b) => actionSortOrder(a) - actionSortOrder(b))
})

const canPrepareFc100Delivery = computed(() => props.actions.some(a => routePreparationActions.has(a)))
const prepareFc100DeliveryTitle = computed(() =>
  canPrepareFc100Delivery.value
    ? '生成航线并推送FC100，推送后仍需人工启动任务'
    : '先审批任务，填写FC100、起飞点和风场参数',
)

function actionClassName (action: string) {
  return action.toLowerCase().replace(/_/g, '-')
}

function actionSortOrder (action: string) {
  const order: Record<string, number> = {
    PREPARE_FC100_DELIVERY: 0,
    START_DELIVERY: 10,
    TAKEOVER: 20,
    FORCE_FAIL: 30,
    CANCEL: 40,
  }
  return order[action] ?? 25
}

async function doSimple (call: () => Promise<unknown>, actionLabel: string) {
  try {
    await call()
    message.success(`${actionLabel} 成功`)
    emit('refresh')
  } catch (e: unknown) {
    const err = e as { response?: { data?: { message?: string } } }
    Modal.error({ title: err.response?.data?.message ?? '操作失败' })
  }
}

async function handle (action: string) {
  const no = props.missionNo
  const op = { operatorId: 'test-operator' }

  switch (action) {
    case 'APPROVE':
      approveVisible.value = true
      break

    case 'REJECT':
      rejectReason.value = ''
      rejectVisible.value = true
      break

    case 'CANCEL':
      cancelReason.value = ''
      cancelVisible.value = true
      break

    case 'CONFIRM_RELEASE':
      router.push(`/fire-payload-release/${no}`)
      break

    case 'TAKEOVER':
    case 'FORCE_FAIL':
      takeoverAction.value = action
      takeoverReason.value = ''
      takeoverVisible.value = true
      break

    case 'RESOLVE_TAKEOVER_OK':
      resolveResult.value = 'OK'
      resolveRemark.value = ''
      resolveVisible.value = true
      break

    case 'RESOLVE_TAKEOVER_FAILED':
      resolveResult.value = 'FAILED'
      resolveRemark.value = ''
      resolveVisible.value = true
      break

    case 'GEN_WP':
      await doSimple(() => waypointApi.generate(no, op), '生成航点')
      break

    case 'PREPARE_FC100_DELIVERY':
      await doSimple(() => deliveryApi.prepareFireMissionDeliveryTask(no, op), '生成航线并推送FC100')
      break

    case 'EXP_KMZ':
      await doSimple(() => routeApi.exportKmz(no, op), '导出 KMZ')
      break

    case 'CREATE_DELIVERY_TASK':
      await doSimple(() => deliveryApi.createTask(no, op), '推送 Delivery')
      break

    case 'START_DELIVERY':
      await doSimple(() => deliveryApi.startTask(no, op), '启动任务')
      break

    case 'MARK_RELEASE_PENDING':
      await doSimple(() => payloadApi.markPending(no, op), '到达投放点')
      break

    case 'MARK_RELEASE_FAILED':
      await doSimple(() => payloadApi.markFailed(no, op), '投放失败标记')
      break

    case 'RETRY_RELEASE':
      await doSimple(() => payloadApi.retryRelease(no, op), '重试投放')
      break

    case 'MARK_RETURNING':
      await doSimple(() => missionApi.markReturning(no, op), '开始返航')
      break

    case 'MARK_RETURN_COMPLETED':
      await doSimple(() => missionApi.markReturnCompleted(no, op), '返航完成')
      break

    case 'MARK_RETURN_FAILED':
      await doSimple(() => missionApi.markReturnFailed(no, op), '返航失败标记')
      break

    case 'SUBMIT_REVIEW':
      await doSimple(
        () => reviewApi.submit(no, { reviewerId: 'test-operator' }),
        '提交复查',
      )
      break

    case 'ARCHIVE':
      await doSimple(() => missionApi.archive(no, op), '归档')
      break

    default:
      message.warning(`未知操作: ${action}`)
  }
}

async function submitApprove () {
  await doSimple(() => missionApi.approve(props.missionNo, approveForm.value), '审批')
  approveVisible.value = false
}

async function submitReject () {
  await doSimple(
    () => missionApi.reject(props.missionNo, { operatorId: 'test-operator', reason: rejectReason.value }),
    '驳回',
  )
  rejectVisible.value = false
}

async function submitCancel () {
  await doSimple(
    () => missionApi.cancel(props.missionNo, { operatorId: 'test-operator', reason: cancelReason.value }),
    '取消',
  )
  cancelVisible.value = false
}

async function submitTakeover () {
  const no = props.missionNo
  const body = { operatorId: 'test-operator', reason: takeoverReason.value }
  if (takeoverAction.value === 'FORCE_FAIL') {
    await doSimple(() => missionApi.forceFail(no, body), '管理员中止')
  } else {
    await doSimple(() => missionApi.takeover(no, body), '人工接管')
  }
  takeoverVisible.value = false
}

async function submitResolve () {
  await doSimple(
    () =>
      missionApi.resolveTakeover(props.missionNo, {
        operatorId: 'test-operator',
        result: resolveResult.value,
        remark: resolveRemark.value,
      }),
    resolveResult.value === 'OK' ? '接管成功' : '接管失败',
  )
  resolveVisible.value = false
}
</script>

<template>
  <a-space wrap>
    <a-button
      v-for="a in visibleActions"
      :key="a"
      :class="['fire-action-button', `fire-action-button--${actionClassName(a)}`]"
      :danger="labelMap[a]?.danger"
      :type="labelMap[a]?.secondary ? 'dashed' : 'primary'"
      :disabled="a === 'PREPARE_FC100_DELIVERY' && !canPrepareFc100Delivery"
      :title="a === 'PREPARE_FC100_DELIVERY' ? prepareFc100DeliveryTitle : undefined"
      @click="handle(a)"
    >
      {{ labelMap[a]?.label ?? a }}
    </a-button>
  </a-space>

  <!-- Approve Modal -->
  <a-modal v-model:open="approveVisible" title="审批任务" @ok="submitApprove" ok-text="提交审批" cancel-text="取消">
    <a-form layout="vertical">
      <a-form-item label="操作员 ID">
        <a-input v-model:value="approveForm.operatorId" />
      </a-form-item>
      <a-form-item label="飞机 SN">
        <a-input v-model:value="approveForm.aircraftSn" />
      </a-form-item>
      <a-form-item label="载水量 (L)">
        <a-input-number v-model:value="approveForm.waterLoadLiters" style="width: 100%" />
      </a-form-item>
      <a-form-item label="起飞纬度">
        <a-input-number v-model:value="approveForm.takeoffLat" style="width: 100%" :precision="6" />
      </a-form-item>
      <a-form-item label="起飞经度">
        <a-input-number v-model:value="approveForm.takeoffLng" style="width: 100%" :precision="6" />
      </a-form-item>
      <a-form-item label="起飞高度 (m)">
        <a-input-number v-model:value="approveForm.takeoffAlt" style="width: 100%" />
      </a-form-item>
      <a-form-item label="风速 (m/s)">
        <a-input-number v-model:value="approveForm.windSpeed" style="width: 100%" />
      </a-form-item>
      <a-form-item label="风向 (°)">
        <a-input-number v-model:value="approveForm.windDirectionDeg" style="width: 100%" :min="0" :max="360" />
      </a-form-item>
      <a-form-item label="备注">
        <a-input v-model:value="approveForm.remark" />
      </a-form-item>
    </a-form>
  </a-modal>

  <!-- Reject Modal -->
  <a-modal v-model:open="rejectVisible" title="驳回任务" @ok="submitReject" ok-text="确认驳回" cancel-text="取消">
    <a-form layout="vertical">
      <a-form-item label="驳回原因">
        <a-textarea v-model:value="rejectReason" :rows="3" />
      </a-form-item>
    </a-form>
  </a-modal>

  <!-- Cancel Modal -->
  <a-modal v-model:open="cancelVisible" title="取消任务" @ok="submitCancel" ok-text="确认取消" cancel-text="关闭">
    <p>确定要取消此任务吗？</p>
    <a-form layout="vertical">
      <a-form-item label="取消原因">
        <a-textarea v-model:value="cancelReason" :rows="3" />
      </a-form-item>
    </a-form>
  </a-modal>

  <!-- Takeover / FORCE_FAIL Modal -->
  <a-modal v-model:open="takeoverVisible" :title="takeoverAction === 'FORCE_FAIL' ? '管理员中止' : '人工接管'" @ok="submitTakeover" ok-text="确认" cancel-text="取消">
    <a-form layout="vertical">
      <a-form-item label="操作员 ID">
        <a-input value="test-operator" disabled />
      </a-form-item>
      <a-form-item label="原因">
        <a-textarea v-model:value="takeoverReason" :rows="3" />
      </a-form-item>
    </a-form>
  </a-modal>

  <!-- Resolve Takeover Modal -->
  <a-modal v-model:open="resolveVisible" title="处理接管结果" @ok="submitResolve" ok-text="提交" cancel-text="取消">
    <a-form layout="vertical">
      <a-form-item label="结果">
        <a-radio-group v-model:value="resolveResult">
          <a-radio value="OK">接管成功</a-radio>
          <a-radio value="FAILED">接管失败</a-radio>
        </a-radio-group>
      </a-form-item>
      <a-form-item label="备注">
        <a-textarea v-model:value="resolveRemark" :rows="3" />
      </a-form-item>
    </a-form>
  </a-modal>
</template>
