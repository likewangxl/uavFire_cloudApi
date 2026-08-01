<template>
  <a-tag :color="color">{{ label }}</a-tag>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { formatMissionStatus } from '/@/pages/page-web/projects/fire/fire-event-status.mjs'

const props = defineProps<{ status: string }>()

const colorMap: Record<string, string> = {
  CREATED: 'default',
  WAITING_REVIEW: 'gold',
  APPROVED: 'cyan',
  ROUTE_GENERATED: 'blue',
  ROUTE_EXPORTED: 'geekblue',
  SENT_TO_DELIVERY: 'purple',
  ACCEPTED_BY_PILOT: 'purple',
  IN_PROGRESS: 'magenta',
  PAYLOAD_RELEASE_PENDING: 'volcano',
  PAYLOAD_RELEASED: 'lime',
  RETURNING: 'green',
  REVIEWING: 'cyan',
  COMPLETED: 'success',
  REJECTED: 'red',
  CANCELLED: 'default',
  FAILED: 'error',
  MANUAL_TAKEOVER: 'warning',
  PAYLOAD_RELEASE_FAILED: 'red',
  RETURN_FAILED: 'red',
  ARCHIVED: 'default',
}

const labelMap: Record<string, string> = {
  CREATED: '已创建',
  WAITING_REVIEW: '待审批',
  APPROVED: '已审批',
  ROUTE_GENERATED: '航线已生成',
  ROUTE_EXPORTED: '航线已导出',
  SENT_TO_DELIVERY: '已发送投递',
  ACCEPTED_BY_PILOT: '飞手已接收',
  IN_PROGRESS: '执行中',
  PAYLOAD_RELEASE_PENDING: '待投放',
  PAYLOAD_RELEASED: '已投放',
  RETURNING: '返航中',
  REVIEWING: '复盘中',
  COMPLETED: '已完成',
  REJECTED: '已拒绝',
  CANCELLED: '已取消',
  FAILED: '已失败',
  MANUAL_TAKEOVER: '人工接管',
  PAYLOAD_RELEASE_FAILED: '投放失败',
  RETURN_FAILED: '返航失败',
  ARCHIVED: '已归档',
}

const color = computed(() => colorMap[props.status] ?? 'default')
const label = computed(() => labelMap[props.status] ?? formatMissionStatus(props.status))
</script>
