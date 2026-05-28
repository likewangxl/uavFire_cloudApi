<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import { missionApi } from '/@/api/fire/mission'
import { payloadApi } from '/@/api/fire/payload'
import type { FireMissionDTO } from '/@/types/fire/mission'
import StatusTag from '/@/components/fire/StatusTag.vue'

const props = defineProps<{ no: string }>()
const router = useRouter()

const dto = ref<FireMissionDTO | null>(null)
const loading = ref(false)
const submitting = ref(false)

const checklist = reactive({
  arrival: false,
  noRisk: false,
  wind: false,
  payload: false,
  release: false,
})

const timestamps = reactive<Record<string, number>>({})

function check (key: string) {
  if (!timestamps[key]) {
    timestamps[key] = Date.now()
  }
}

const allChecked = computed(() =>
  Object.values(checklist).every((v) => v === true)
)

async function refresh () {
  loading.value = true
  try {
    const res = await missionApi.detail(props.no)
    dto.value = res.data.data ?? null
  } catch {
    message.error('加载任务详情失败')
  } finally {
    loading.value = false
  }
}

async function doSubmit () {
  submitting.value = true
  try {
    await payloadApi.confirmRelease(props.no, {
      operatorId: 'OP1',
      confirmedArrival: checklist.arrival,
      confirmedNoPeopleRisk: checklist.noRisk,
      confirmedWindOk: checklist.wind,
      confirmedPayloadReady: checklist.payload,
      confirmedRelease: checklist.release,
      checklistTimestamps: { ...timestamps },
    })
    message.success('投放指令已提交')
    router.push(`/fire-mission-detail/${props.no}`)
  } catch (e: unknown) {
    const err = e as { response?: { data?: { message?: string } } }
    Modal.error({
      title: '投放失败',
      content: err?.response?.data?.message ?? '未知错误，请联系管理员',
    })
  } finally {
    submitting.value = false
  }
}

function submit () {
  Modal.confirm({
    title: '确认执行投放？',
    content: '投放操作不可撤销，请确认所有检查项已通过，再次确认执行。',
    okText: '确认投放',
    okType: 'danger',
    cancelText: '取消',
    onOk: doSubmit,
  })
}

onMounted(refresh)
</script>

<template>
  <div style="max-width: 640px; margin: 40px auto; padding: 0 16px">
    <div style="margin-bottom: 16px; display: flex; align-items: center; gap: 12px">
      <a-button @click="router.back()">← 返回</a-button>
      <span style="font-weight: 600; font-size: 16px">投放确认 — {{ props.no }}</span>
      <StatusTag v-if="dto" :status="dto.status" />
    </div>

    <a-alert
      type="error"
      show-icon
      message="投放操作不可撤销，请仔细核对所有检查项后再执行"
      style="margin-bottom: 16px"
    />

    <a-spin :spinning="loading">
      <a-card title="投放确认清单">
        <a-space direction="vertical" style="width: 100%" :size="12">
          <a-checkbox
            v-model:checked="checklist.arrival"
            @change="check('arrival')"
          >
            ① 我已确认 FC100 到达投放等待点
          </a-checkbox>

          <a-checkbox
            v-model:checked="checklist.noRisk"
            @change="check('noRisk')"
          >
            ② 我已确认投放区域无人员风险
          </a-checkbox>

          <a-checkbox
            v-model:checked="checklist.wind"
            @change="check('wind')"
          >
            ③ 我已确认当前风速满足投放要求
            <span v-if="dto?.windSpeedAtApproval != null" style="color: #888; margin-left: 4px">
              (审批风速 {{ dto.windSpeedAtApproval }} m/s，风向 {{ dto.windDirectionDeg }}°)
            </span>
          </a-checkbox>

          <a-checkbox
            v-model:checked="checklist.payload"
            @change="check('payload')"
          >
            ④ 我已确认消防桶处于可投放状态
          </a-checkbox>

          <a-checkbox
            v-model:checked="checklist.release"
            @change="check('release')"
          >
            ⑤ 我确认执行投放
          </a-checkbox>
        </a-space>

        <a-divider />

        <a-button
          type="primary"
          danger
          size="large"
          :disabled="!allChecked"
          :loading="submitting"
          @click="submit"
        >
          执行投放
        </a-button>
      </a-card>
    </a-spin>
  </div>
</template>
