<script setup lang="ts">
import type { MissionLogDTO } from '/@/types/fire/mission'

const props = defineProps<{ logs: MissionLogDTO[] }>()

const statusLabel: Record<string, string> = {
  CREATED: '已创建',
  WAITING_REVIEW: '待审批',
  APPROVED: '已审批',
  ROUTE_GENERATED: '航线已生成',
  ROUTE_EXPORTED: '航线已导出',
  SENT_TO_DELIVERY: '已发送投递',
  ACCEPTED_BY_PILOT: '飞手已接受',
  IN_PROGRESS: '执行中',
  PAYLOAD_RELEASE_PENDING: '投放确认中',
  PAYLOAD_RELEASED: '已投放',
  PAYLOAD_RELEASE_FAILED: '投放失败',
  RETURNING: '返航中',
  RETURN_FAILED: '返航失败',
  MANUAL_TAKEOVER: '人工接管',
  REVIEWING: '复查中',
  COMPLETED: '已完成',
  FAILED: '已失败',
  REJECTED: '已驳回',
  CANCELLED: '已取消',
  ARCHIVED: '已归档',
}

const actionLabel: Record<string, string> = {
  APPROVE: '审批通过',
  REJECT: '审批驳回',
  GEN_WP: '生成航线',
  EXP_KMZ: '导出航线',
  CREATE_DELIVERY_TASK: '创建投递任务',
  START_DELIVERY: '执行航线',
  MARK_RELEASE_PENDING: '标记待投放',
  CONFIRM_RELEASE: '确认投放',
  MARK_RELEASE_FAILED: '标记投放失败',
  RETRY_RELEASE: '重试投放',
  MARK_RETURNING: '标记返航',
  MARK_RETURN_COMPLETED: '返航完成',
  MARK_RETURN_FAILED: '返航失败',
  TAKEOVER: '人工接管',
  RESOLVE_TAKEOVER_OK: '接管处理完成',
  RESOLVE_TAKEOVER_FAILED: '接管处理失败',
  SUBMIT_REVIEW: '提交复查',
  CANCEL: '取消',
  FORCE_FAIL: '强制失败',
  ARCHIVE: '归档',
}

function fmt (ts: number): string {
  return new Date(ts).toLocaleString('zh-CN')
}

function labelStatus (status: string): string {
  return statusLabel[status] ?? status
}

function labelAction (action: string): string {
  return actionLabel[action] ?? action
}
</script>

<template>
  <a-timeline>
    <a-timeline-item
      v-for="log in props.logs"
      :key="log.id"
    >
      <span style="color: #888; font-size: 12px">{{ fmt(log.createTime) }}</span>
      &nbsp;
      <strong>{{ labelStatus(log.fromStatus) }} → {{ labelStatus(log.toStatus) }}</strong>
      &nbsp;&nbsp;
      <span style="color: #555">操作: {{ labelAction(log.action) }}</span>
      &nbsp;&nbsp;
      <span style="color: #888">by {{ log.operatorId }}</span>
      <span v-if="log.remark" style="color: #aaa; margin-left: 8px">（{{ log.remark }}）</span>
    </a-timeline-item>
    <a-timeline-item v-if="props.logs.length === 0">
      <span style="color: #aaa">暂无日志</span>
    </a-timeline-item>
  </a-timeline>
</template>
