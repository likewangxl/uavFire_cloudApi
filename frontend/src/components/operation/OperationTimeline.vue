<template>
  <section class="operation-timeline-panel">
    <div class="timeline-header">
      <div>
        <span class="panel-kicker">处置记录</span>
        <h3>事件时间线</h3>
      </div>
      <span class="timeline-count">{{ sortedItems.length }} 条</span>
    </div>
    <a-spin :spinning="loading">
      <a-timeline v-if="sortedItems.length" class="operation-timeline">
        <a-timeline-item
          v-for="(item, index) in sortedItems"
          :key="`${item.action}-${item.createTime || index}`"
          :color="timelineColor(item)"
        >
          <div class="timeline-item">
            <span class="timeline-time">{{ formatTime(item.createTime) }}</span>
            <strong>{{ actionLabel(item.action) }}</strong>
            <p>{{ timelineDescription(item) }}</p>
          </div>
        </a-timeline-item>
      </a-timeline>
      <a-empty v-else description="暂无时间线" />
    </a-spin>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { OperationTimelineItem } from '/@/types/operation/incident'
import { sortTimelineItems, statusBadge } from '/@/pages/page-web/projects/operation-policy.mjs'

const props = defineProps<{
  items: OperationTimelineItem[];
  loading: boolean;
}>()

const sortedItems = computed<OperationTimelineItem[]>(() => sortTimelineItems(props.items))

function timelineColor (item: OperationTimelineItem) {
  if (item.type === 'ASSIGNMENT') return 'blue'
  const to = item.toStatus ? statusBadge(item.toStatus) : null
  if (to?.color === 'processing') return 'blue'
  if (to?.color === 'default') return 'gray'
  return to?.color || 'blue'
}

function actionLabel (action?: string) {
  const labels: Record<string, string> = {
    CREATE: '创建事件',
    DETECT: '候选识别',
    CONFIRM: '确认火情',
    DISPATCH: '派发',
    RESPOND: '响应',
    ABORT: '中止',
    ARCHIVE: '归档',
    MARK_FALSE_ALARM: '标记误报',
    RESOLVE: '解决',
    START_RECHECK: '开始复核',
    CONTINUE_RESPONSE: '继续处置',
  }
  if (!action) return '-'
  if (action.startsWith('ASSIGN_')) return '资源分配'
  return labels[action] || action
}

function timelineDescription (item: OperationTimelineItem) {
  if (item.description) return item.description
  if (item.type === 'ASSIGNMENT') {
    return `${item.role || '-'} · ${item.resourceSn || '-'} · ${item.status || '-'}`
  }
  if (item.fromStatus || item.toStatus) {
    return `${statusBadge(item.fromStatus).label} → ${statusBadge(item.toStatus).label}`
  }
  return item.operatorId || '-'
}

function formatTime (value?: number) {
  if (!value) return '-'
  return new Date(value).toLocaleString('zh-CN')
}
</script>

<style lang="scss" scoped>
.operation-timeline-panel {
  min-width: 0;
  min-height: 0;
  padding: 14px;
  border: 1px solid #dde3ea;
  border-radius: 8px;
  background: #ffffff;
  overflow: auto;
}

.timeline-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;

  h3 {
    margin: 0;
    color: #1f2933;
    font-size: 16px;
  }
}

.panel-kicker {
  display: block;
  color: #6b7a8c;
  font-size: 11px;
  text-transform: uppercase;
}

.timeline-count {
  color: #697586;
  font-size: 12px;
}

.operation-timeline {
  padding-top: 4px;
}

.timeline-item {
  display: grid;
  gap: 2px;

  strong {
    color: #1f2933;
    font-size: 13px;
  }

  p {
    margin: 0;
    color: #5d6b7c;
    font-size: 12px;
    line-height: 1.6;
    overflow-wrap: anywhere;
  }
}

.timeline-time {
  color: #8391a2;
  font-size: 11px;
}
</style>
