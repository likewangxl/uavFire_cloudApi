<template>
  <section class="compliance-panel">
    <div class="section-heading">
      <h4>合规预检</h4>
      <a-button
        size="small"
        type="primary"
        :loading="preflightLoading"
        :disabled="!incidentId"
        @click="emit('run-action', { actionId: 'RUN_PREFLIGHT' })"
      >
        运行预检
      </a-button>
    </div>

    <a-alert
      v-if="preflightResult"
      class="preflight-summary"
      :type="preflightResult.status === 'PASS' ? 'success' : 'error'"
      show-icon
      :message="preflightSummary"
    />
    <a-empty v-else class="preflight-empty" description="尚未运行预检" />

    <div v-if="preflightResult" class="rule-list">
      <div
        v-for="item in normalizedItems"
        :key="item.ruleId"
        class="rule-row"
        :class="{ blocked: item.status === 'BLOCK' }"
      >
        <div class="rule-main">
          <strong>{{ item.ruleId }} {{ item.description }}</strong>
          <small>{{ item.message || '通过' }}</small>
        </div>
        <a-tag :color="ruleColor(item.status)">{{ item.status }}</a-tag>
      </div>
    </div>

    <a-collapse class="record-collapse">
      <a-collapse-panel key="records" header="录入合规记录">
        <a-form layout="vertical" class="record-form">
          <h5>飞行申请</h5>
          <a-form-item label="申请单号">
            <a-input v-model:value="flightApplication.applicationNo" />
          </a-form-item>
          <a-form-item label="批复文号">
            <a-input v-model:value="flightApplication.approvalNo" />
          </a-form-item>
          <div class="form-grid">
            <a-form-item label="有效期开始">
              <a-input v-model:value="flightApplication.validFrom" placeholder="YYYY-MM-DD" />
            </a-form-item>
            <a-form-item label="有效期结束">
              <a-input v-model:value="flightApplication.validTo" placeholder="YYYY-MM-DD" />
            </a-form-item>
          </div>
          <a-button
            size="small"
            type="primary"
            :loading="submittingAction === 'RECORD_FLIGHT_APPLICATION'"
            :disabled="!flightApplication.applicationNo.trim() || !flightApplication.approvalNo.trim()"
            @click="submitFlightApplication"
          >
            提交飞行申请
          </a-button>
        </a-form>

        <a-form layout="vertical" class="record-form">
          <h5>起飞确认</h5>
          <a-form-item label="确认编号">
            <a-input v-model:value="takeoffConfirmation.confirmationNo" placeholder="可选" />
          </a-form-item>
          <a-form-item label="确认时间">
            <a-input v-model:value="takeoffConfirmation.confirmedAt" placeholder="YYYY-MM-DD HH:mm，可选" />
          </a-form-item>
          <a-button
            size="small"
            type="primary"
            :loading="submittingAction === 'RECORD_TAKEOFF_CONFIRMATION'"
            @click="submitTakeoffConfirmation"
          >
            提交起飞确认
          </a-button>
        </a-form>

        <a-form layout="vertical" class="record-form">
          <h5>落地报告</h5>
          <a-form-item label="报告编号">
            <a-input v-model:value="landingReport.reportNo" placeholder="可选" />
          </a-form-item>
          <a-form-item label="落地时间">
            <a-input v-model:value="landingReport.landedAt" placeholder="YYYY-MM-DD HH:mm，可选" />
          </a-form-item>
          <a-button
            size="small"
            type="primary"
            :loading="submittingAction === 'RECORD_LANDING_REPORT'"
            @click="submitLandingReport"
          >
            提交落地报告
          </a-button>
        </a-form>

        <a-form layout="vertical" class="record-form">
          <h5>资质档案</h5>
          <a-form-item label="类型">
            <a-select v-model:value="qualification.qualificationType">
              <a-select-option value="CLUSTER_FLIGHT_PERMIT">集群飞行许可</a-select-option>
              <a-select-option value="AIRDROP_APPROVAL">空投批准</a-select-option>
              <a-select-option value="AIRWORTHINESS">审定状态</a-select-option>
              <a-select-option value="JOINT_OPERATION_AGREEMENT">联合运行协议</a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item label="编号">
            <a-input v-model:value="qualification.qualificationNo" />
          </a-form-item>
          <a-form-item label="发证机构">
            <a-input v-model:value="qualification.issuer" />
          </a-form-item>
          <div class="form-grid">
            <a-form-item label="生效日期">
              <a-input v-model:value="qualification.validFrom" placeholder="YYYY-MM-DD" />
            </a-form-item>
            <a-form-item label="失效日期">
              <a-input v-model:value="qualification.validTo" placeholder="YYYY-MM-DD" />
            </a-form-item>
          </div>
          <a-button
            size="small"
            type="primary"
            :loading="submittingAction === 'RECORD_QUALIFICATION'"
            :disabled="!qualification.qualificationNo.trim()"
            @click="submitQualification"
          >
            提交资质
          </a-button>
        </a-form>
      </a-collapse-panel>
    </a-collapse>
  </section>
</template>

<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import type { PreflightResult, RuleCheckResult } from '/@/types/operation/compliance'

const props = defineProps<{
  incidentId?: number;
  preflightResult?: PreflightResult | null;
  preflightLoading?: boolean;
  submittingAction?: string;
}>()

const emit = defineEmits(['run-action'])

const ruleDescriptions: Record<string, string> = {
  R01: '火情已人工确认',
  R02: '空域申请批复记录有效',
  R03: '投放审批记录有效',
  R04: 'FC100 在线并已接入 Delivery Sync',
  R05: '电量满足最低阈值',
  R06: '风速不超过派发阈值',
  R07: '载荷不超过 FC100 双电 85kg 含吊具口径',
  R08: '火点定位质量为 PRECISE',
  R09: '起降点、投放点和航线不越界',
  R10: '操作员已记录起飞确认',
  R11: '资源锁无冲突',
  R12: '指令队列无未完成危险指令',
  R13: '任务时间和能量预算充足',
  R14: 'DeliveryHub 连通性正常',
  R15: '运行资质档案有效',
}

const ruleIds = Object.keys(ruleDescriptions)

const flightApplication = reactive({
  applicationNo: '',
  approvalNo: '',
  validFrom: todayDate(),
  validTo: nextMonthDate(),
})

const takeoffConfirmation = reactive({
  confirmationNo: '',
  confirmedAt: '',
})

const landingReport = reactive({
  reportNo: '',
  landedAt: '',
})

const qualification = reactive({
  qualificationType: 'CLUSTER_FLIGHT_PERMIT',
  qualificationNo: '',
  issuer: '',
  validFrom: todayDate(),
  validTo: nextYearDate(),
})

const preflightSummary = computed(() => {
  const status = props.preflightResult?.status || '-'
  const blockCount = (props.preflightResult?.items || []).filter(item => item.status === 'BLOCK').length
  const warnCount = (props.preflightResult?.items || []).filter(item => item.status === 'WARN').length
  return `总体结论：${status}，BLOCK ${blockCount} 项，WARN ${warnCount} 项`
})

const normalizedItems = computed<RuleCheckResult[]>(() => {
  const byId = new Map((props.preflightResult?.items || []).map(item => [item.ruleId, item]))
  return ruleIds.map(ruleId => byId.get(ruleId) || {
    ruleId,
    description: ruleDescriptions[ruleId],
    status: '未返回',
    message: '后端未返回该规则结果',
  })
})

watch(() => props.incidentId, () => {
  resetForms()
})

function resetForms () {
  flightApplication.applicationNo = ''
  flightApplication.approvalNo = ''
  flightApplication.validFrom = todayDate()
  flightApplication.validTo = nextMonthDate()
  takeoffConfirmation.confirmationNo = ''
  takeoffConfirmation.confirmedAt = ''
  landingReport.reportNo = ''
  landingReport.landedAt = ''
  qualification.qualificationType = 'CLUSTER_FLIGHT_PERMIT'
  qualification.qualificationNo = ''
  qualification.issuer = ''
  qualification.validFrom = todayDate()
  qualification.validTo = nextYearDate()
}

function ruleColor (status?: string) {
  if (status === 'PASS') return 'green'
  if (status === 'WARN') return 'orange'
  if (status === 'BLOCK') return 'red'
  return 'default'
}

function submitFlightApplication () {
  emit('run-action', {
    actionId: 'RECORD_FLIGHT_APPLICATION',
    applicationNo: flightApplication.applicationNo.trim(),
    approvalNo: flightApplication.approvalNo.trim(),
    validFrom: parseDateMs(flightApplication.validFrom),
    validTo: parseDateMs(flightApplication.validTo, true),
  })
}

function submitTakeoffConfirmation () {
  emit('run-action', {
    actionId: 'RECORD_TAKEOFF_CONFIRMATION',
    confirmationNo: takeoffConfirmation.confirmationNo.trim() || undefined,
    confirmedAt: parseDateTimeMs(takeoffConfirmation.confirmedAt) || Date.now(),
  })
}

function submitLandingReport () {
  emit('run-action', {
    actionId: 'RECORD_LANDING_REPORT',
    reportNo: landingReport.reportNo.trim() || undefined,
    landedAt: parseDateTimeMs(landingReport.landedAt) || Date.now(),
  })
}

function submitQualification () {
  emit('run-action', {
    actionId: 'RECORD_QUALIFICATION',
    qualificationType: qualification.qualificationType,
    qualificationNo: qualification.qualificationNo.trim(),
    issuer: qualification.issuer.trim() || undefined,
    validFrom: parseDateMs(qualification.validFrom),
    validTo: parseDateMs(qualification.validTo, true),
    status: 'VALID',
  })
  qualification.qualificationNo = ''
}

function parseDateMs (value: string, endOfDay = false) {
  if (!value) return undefined
  const suffix = endOfDay ? 'T23:59:59' : 'T00:00:00'
  const ts = new Date(`${value}${suffix}`).getTime()
  return Number.isFinite(ts) ? ts : undefined
}

function parseDateTimeMs (value: string) {
  if (!value) return undefined
  const ts = new Date(value.replace(' ', 'T')).getTime()
  return Number.isFinite(ts) ? ts : undefined
}

function dateString (date: Date) {
  return date.toISOString().slice(0, 10)
}

function todayDate () {
  return dateString(new Date())
}

function nextMonthDate () {
  const d = new Date()
  d.setMonth(d.getMonth() + 1)
  return dateString(d)
}

function nextYearDate () {
  const d = new Date()
  d.setFullYear(d.getFullYear() + 1)
  return dateString(d)
}
</script>

<style lang="scss" scoped>
.compliance-panel {
  display: grid;
  gap: 10px;
}

.section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;

  h4 {
    margin: 0;
    color: #e2eaf5;
    font-size: 14px;
  }
}

.preflight-summary {
  margin-top: 0;
}

.preflight-empty {
  padding: 10px 0;
}

.rule-list {
  display: grid;
  gap: 6px;
}

.rule-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px;
  border: 1px solid #2a3b50;
  border-radius: 6px;
  background: #1a2a3f;

  &.blocked {
    border-color: #ffccc7;
    background: #3b2430;
  }
}

.rule-main {
  display: grid;
  gap: 2px;
  min-width: 0;

  strong {
    color: #e2eaf5;
    font-size: 12px;
  }

  small {
    color: #91a4bd;
    overflow-wrap: anywhere;
  }
}

.record-collapse {
  background: transparent;
}

.record-form {
  display: grid;
  gap: 8px;
  margin-bottom: 14px;

  h5 {
    margin: 0;
    color: #e2eaf5;
    font-size: 13px;
  }
}

.form-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 8px;
}
</style>
