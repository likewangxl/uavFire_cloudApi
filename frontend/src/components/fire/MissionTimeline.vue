<script setup lang="ts">
import type { MissionLogDTO } from '/@/types/fire/mission'

const props = defineProps<{ logs: MissionLogDTO[] }>()

function fmt (ts: number): string {
  return new Date(ts).toLocaleString('zh-CN')
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
      <strong>{{ log.fromStatus }} → {{ log.toStatus }}</strong>
      &nbsp;&nbsp;
      <span style="color: #555">操作: {{ log.action }}</span>
      &nbsp;&nbsp;
      <span style="color: #888">by {{ log.operatorId }}</span>
      <span v-if="log.remark" style="color: #aaa; margin-left: 8px">（{{ log.remark }}）</span>
    </a-timeline-item>
    <a-timeline-item v-if="props.logs.length === 0">
      <span style="color: #aaa">暂无日志</span>
    </a-timeline-item>
  </a-timeline>
</template>
