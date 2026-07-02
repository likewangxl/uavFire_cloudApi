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
          <a-descriptions-item label="风险半径">{{ formatRadius(detail.riskRadiusM) }}</a-descriptions-item>
          <a-descriptions-item label="创建人">{{ detail.createdBy || '-' }}</a-descriptions-item>
          <a-descriptions-item label="确认人">{{ detail.confirmedBy || '-' }}</a-descriptions-item>
          <a-descriptions-item label="更新时间">{{ formatTime(detail.updateTime) }}</a-descriptions-item>
        </a-descriptions>

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
          <h4>预检结果</h4>
          <div class="placeholder-box">
            S5接入后展示风场、载重、航线、安全边界等预检结果。
          </div>
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
import { computed } from 'vue'
import OperationActionBar from './OperationActionBar.vue'
import type { OperationIncidentDetailDTO } from '/@/types/operation/incident'
import { levelBadge, statusBadge } from '/@/pages/page-web/projects/operation-policy.mjs'

const props = defineProps<{
  detail: OperationIncidentDetailDTO | null;
  loading: boolean;
  submittingAction?: string;
}>()

const emit = defineEmits(['run-action'])

const assignments = computed(() => props.detail?.assignments || [])

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
