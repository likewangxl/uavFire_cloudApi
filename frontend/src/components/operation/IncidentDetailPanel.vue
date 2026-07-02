<template>
  <aside class="incident-detail-panel">
    <a-spin :spinning="loading">
      <template v-if="detail">
        <div class="detail-header">
          <div>
            <span class="panel-kicker">Incident Detail</span>
            <h3>{{ detail.incidentNo }}</h3>
          </div>
          <div class="detail-tags">
            <a-tag :color="levelBadge(detail.level).color">{{ levelBadge(detail.level).label }}</a-tag>
            <a-tag :color="statusBadge(detail.status).color">{{ statusBadge(detail.status).label }}</a-tag>
          </div>
        </div>

        <OperationActionBar
          :incident="detail"
          :assignments="assignments"
          :submitting-action="submittingAction"
          @run="payload => emit('run-action', payload)"
        />

        <a-descriptions
          size="small"
          :column="1"
          bordered
          class="detail-descriptions"
        >
          <a-descriptions-item label="火情ID">{{ detail.fireEventId }}</a-descriptions-item>
          <a-descriptions-item label="中心坐标">{{ formatCoord(detail.centerLat, detail.centerLng) }}</a-descriptions-item>
          <a-descriptions-item label="坐标质量">
            <a-tag :color="coordinateQualityBadge(detail.locationQuality).color">
              {{ coordinateQualityBadge(detail.locationQuality).label }}
            </a-tag>
          </a-descriptions-item>
          <a-descriptions-item label="置信度">{{ formatConfidence(detail.confidence) }}</a-descriptions-item>
          <a-descriptions-item label="热成像温度">{{ formatTemperature(detail.thermalTemperature) }}</a-descriptions-item>
          <a-descriptions-item label="风险半径">{{ formatRadius(detail.riskRadiusM) }}</a-descriptions-item>
          <a-descriptions-item label="创建人">{{ detail.createdBy || '-' }}</a-descriptions-item>
          <a-descriptions-item label="确认人">{{ detail.confirmedBy || '-' }}</a-descriptions-item>
          <a-descriptions-item label="更新时间">{{ formatTime(detail.updateTime) }}</a-descriptions-item>
        </a-descriptions>

        <a-alert
          v-if="detail.status !== 'CANDIDATE'"
          class="detail-alert"
          :type="missionInfo.type"
          show-icon
          :message="missionInfo.title"
        />
        <a-alert
          v-if="shouldShowSaturationWarning(detail.thermalTemperature, saturationTempC)"
          class="detail-alert"
          type="warning"
          show-icon
          message="测温可能饱和"
        />
        <a-alert
          v-if="detail.status === 'CANDIDATE'"
          class="detail-alert"
          :type="draftMissionHint(detail.locationQuality).type"
          show-icon
          :message="draftMissionHint(detail.locationQuality).text"
        />
        <a-alert
          v-if="detail.recommendedRecheck"
          class="detail-alert"
          type="info"
          show-icon
          :message="detail.recheckReason || '建议复测'"
        />

        <section class="detail-section">
          <h4>资源分配</h4>
          <div v-if="assignments.length" class="assignment-list">
            <div
              v-for="item in assignments"
              :key="item.id"
              class="assignment-row"
            >
              <span>
                <strong>{{ roleLabel(item.role) }}</strong>
                <small>{{ item.resourceSn }}</small>
              </span>
              <a-tag :color="item.status === 'ACTIVE' ? 'green' : 'default'">{{ item.status }}</a-tag>
            </div>
          </div>
          <a-empty v-else description="暂无分配资源" />
        </section>

        <section class="detail-section">
          <CompliancePanel
            :incident-id="detail.id"
            :preflight-result="preflightResult"
            :preflight-loading="preflightLoading"
            :submitting-action="submittingAction"
            @run-action="payload => emit('run-action', payload)"
          />
        </section>

        <section class="detail-section">
          <h4>复测记录</h4>
          <a-form layout="vertical" class="recheck-form">
            <a-form-item label="复测温度(°C)">
              <a-input-number v-model:value="recheckForm.maxTemp" style="width: 100%" />
            </a-form-item>
            <a-form-item label="热源面积(m²)">
              <a-input-number v-model:value="recheckForm.hotAreaM2" :min="0" style="width: 100%" />
            </a-form-item>
            <a-form-item label="仍有明火">
              <a-switch v-model:checked="recheckForm.flameVisible" />
            </a-form-item>
            <a-form-item label="建议">
              <a-select v-model:value="recheckForm.suggestion">
                <a-select-option value="RESOLVED">火情已解除</a-select-option>
                <a-select-option value="CONTINUE_RESPONSE">继续处置</a-select-option>
              </a-select>
            </a-form-item>
            <a-button
              size="small"
              type="primary"
              :loading="submittingAction === 'SUBMIT_RECHECK'"
              @click="submitRecheck"
            >
              提交复测
            </a-button>
          </a-form>
        </section>

        <section class="detail-section">
          <h4>视频</h4>
          <div class="video-placeholder">
            <span>Video Placeholder</span>
          </div>
        </section>
      </template>
      <a-empty v-else description="请选择事件" />
    </a-spin>
  </aside>
</template>

<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import CompliancePanel from './CompliancePanel.vue'
import OperationActionBar from './OperationActionBar.vue'
import type { OperationIncidentDetailDTO } from '/@/types/operation/incident'
import type { PreflightResult } from '/@/types/operation/compliance'
import {
  coordinateQualityBadge,
  draftMissionHint,
  levelBadge,
  missionPresentation,
  shouldShowSaturationWarning,
  statusBadge,
} from '/@/pages/page-web/projects/operation-policy.mjs'

const props = defineProps<{
  detail: OperationIncidentDetailDTO | null;
  loading: boolean;
  submittingAction?: string;
  preflightResult?: PreflightResult | null;
  preflightLoading?: boolean;
}>()

const emit = defineEmits(['run-action'])

const assignments = computed(() => props.detail?.assignments || [])
const saturationTempC = 540
const missionInfo = computed(() => missionPresentation({
  missionNo: props.detail?.missionNo,
  missionStatus: props.detail?.missionStatus,
  locationQuality: props.detail?.locationQuality,
}))
const recheckForm = reactive({
  maxTemp: undefined as number | undefined,
  hotAreaM2: 0,
  flameVisible: false,
  suggestion: 'CONTINUE_RESPONSE',
})

watch(() => props.detail?.id, () => {
  recheckForm.maxTemp = props.detail?.thermalTemperature ?? undefined
  recheckForm.hotAreaM2 = 0
  recheckForm.flameVisible = false
  recheckForm.suggestion = 'CONTINUE_RESPONSE'
}, { immediate: true })

function formatCoord (lat?: number, lng?: number) {
  const y = Number(lat)
  const x = Number(lng)
  if (!Number.isFinite(y) || !Number.isFinite(x)) return '-'
  return `${y.toFixed(6)}, ${x.toFixed(6)}`
}

function formatRadius (value?: number) {
  const n = Number(value)
  return Number.isFinite(n) ? `${n.toFixed(0)} m` : '-'
}

function formatConfidence (value?: number | string) {
  const n = Number(value)
  return Number.isFinite(n) ? n.toFixed(2) : '-'
}

function formatTemperature (value?: number | null) {
  const n = Number(value)
  return Number.isFinite(n) ? `${n.toFixed(1)} °C` : '-'
}

function formatTime (value?: number) {
  if (!value) return '-'
  return new Date(value).toLocaleString('zh-CN')
}

function roleLabel (role?: string) {
  const labels: Record<string, string> = {
    MONITOR_PRIMARY: '主监测',
    MONITOR_RECHECK: '复核监测',
    DELIVERY_PRIMARY: '主投送',
    DELIVERY_BACKUP: '备用投送',
    COMMANDER: '指挥员',
  }
  return labels[String(role || '')] || role || '-'
}

function submitRecheck () {
  emit('run-action', {
    actionId: 'SUBMIT_RECHECK',
    maxTemp: recheckForm.maxTemp,
    hotAreaM2: recheckForm.hotAreaM2,
    flameVisible: recheckForm.flameVisible,
    suggestion: recheckForm.suggestion,
  })
}
</script>

<style lang="scss" scoped>
.incident-detail-panel {
  display: flex;
  min-width: 0;
  min-height: 0;
  flex-direction: column;
  gap: 12px;
  padding: 14px;
  border: 1px solid #dde3ea;
  border-radius: 8px;
  background: #ffffff;
  overflow: auto;
}

.detail-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;

  h3 {
    margin: 0;
    color: #1f2933;
    font-size: 16px;
    overflow-wrap: anywhere;
  }
}

.panel-kicker {
  display: block;
  color: #6b7a8c;
  font-size: 11px;
  text-transform: uppercase;
}

.detail-tags {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 4px;
}

.detail-descriptions {
  margin-top: 12px;
}

.detail-alert {
  margin-top: 10px;
}

.recheck-form {
  display: grid;
  gap: 8px;
}

.detail-section {
  margin-top: 14px;

  h4 {
    margin: 0 0 8px;
    color: #253140;
    font-size: 14px;
  }
}

.assignment-list {
  display: grid;
  gap: 8px;
}

.assignment-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid #e4e9ef;
  border-radius: 6px;
  background: #f9fbfd;

  span {
    display: grid;
    gap: 2px;
    min-width: 0;
  }

  strong {
    color: #1f2933;
    font-size: 13px;
  }

  small {
    color: #697586;
    overflow-wrap: anywhere;
  }
}

.placeholder-box,
.video-placeholder {
  display: flex;
  min-height: 74px;
  align-items: center;
  justify-content: center;
  padding: 12px;
  border: 1px dashed #c9d3df;
  border-radius: 6px;
  background: #f8fafc;
  color: #65758a;
  line-height: 1.6;
  text-align: center;
}

.video-placeholder {
  min-height: 128px;
  background: #111827;
  color: #d1d5db;
}
</style>
