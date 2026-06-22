<template>
  <div class="fc100-delivery-view">
      <div class="planned-wayline-panel planned-wayline-panel--delivery">
        <div class="planned-wayline-title">
          <span>FC100 投放航线库</span>
          <a-button size="small" type="link" :loading="plannedWaylinesLoading" @click="() => refreshPlannedWaylines(true)">
            刷新
          </a-button>
        </div>
        <div class="planning-empty" v-if="!plannedWaylinesLoading && plannedWaylinesData.data.length === 0">
          暂无可用于投放的已保存规划航线。
        </div>
        <div v-else class="planned-wayline-list" @scroll="onPlannedWaylinesScroll">
          <div
            class="planned-wayline-card"
            :class="{ 'planned-wayline-card--selected': fc100PlanningState.selectedRecord?.plannedWaylineId === record.plannedWaylineId }"
            v-for="record in plannedWaylinesData.data"
            :key="`fc100-${record.plannedWaylineId}`"
            @click="onFc100PreviewGeneratedWayline(record)">
            <div class="planned-wayline-card-head">
              <a-tooltip :title="record.name">
                <span class="planned-wayline-name">{{ record.name }}</span>
              </a-tooltip>
              <span class="planned-wayline-status" :class="{ failed: normalizePlannedWaylineStatus(record) === PlannedWaylineStatus.FAILED }">
                {{ formatPlannedWaylineStatus(record) }}
              </span>
            </div>
            <div class="planned-wayline-meta">
              <span>航点 {{ record.waypoints?.length || 0 }}</span>
              <span>高度 {{ formatNumber(record.defaultHeight) }} m</span>
              <span>速度 {{ formatNumber(record.maxSpeed) }} m/s</span>
            </div>
            <div class="planned-wayline-meta muted">
              <span>机型 {{ formatFc100DeliveryAircraftModel() }}</span>
              <span>更新于 {{ formatTimestamp(record.updateTime) }}</span>
            </div>
            <div class="planned-wayline-actions planned-wayline-actions--minimal">
              <a-button size="small" @click.stop="showPlannedWaylineDetail(record)">详情</a-button>
              <a-button
                size="small"
                type="primary"
                :disabled="!record.kmzUrl"
                @click.stop="onOpenDeliveryTask(record, $event)">
                下发
              </a-button>
              <a-button size="small" danger @click.stop="onDeletePlannedWayline(record)">删除</a-button>
            </div>
          </div>
          <div class="planned-wayline-list-footer" v-if="plannedWaylinesLoading">加载中...</div>
          <div class="planned-wayline-list-footer" v-else-if="plannedWaylinesData.data.length > 0 && !plannedWaylinesCanRefresh">已加载全部</div>
        </div>
      </div>
  </div>
</template>

<script lang="ts" setup>
import { onMounted, onUnmounted } from 'vue'
import { PlannedWaylineRecord, PlannedWaylineStatus } from '/@/types/wayline'
import {
  fc100PlanningState,
  formatFc100DeliveryAircraftModel,
  handleFc100RefreshDevices,
  onFc100PreviewGeneratedWayline,
  startFc100RealtimeRefresh,
  stopFc100RealtimeRefresh,
} from '/@/hooks/use-fc100-delivery'
import {
  formatNumber,
  formatPlannedWaylineStatus,
  formatTimestamp,
  normalizePlannedWaylineStatus,
} from '/@/components/wayline-planner/wayline-format'

defineProps<{
  plannedWaylinesData: { data: PlannedWaylineRecord[] }
  plannedWaylinesLoading: boolean
  plannedWaylinesCanRefresh: boolean
  refreshPlannedWaylines:(reset?: boolean) => void
  onPlannedWaylinesScroll: (e: any) => void
  showPlannedWaylineDetail: (record: PlannedWaylineRecord) => void
  onOpenDeliveryTask: (record: PlannedWaylineRecord, ev?: Event) => void
  onDeletePlannedWayline: (record: PlannedWaylineRecord) => void
}>()

onMounted(() => {
  handleFc100RefreshDevices().catch(() => {})
  startFc100RealtimeRefresh()
})

onUnmounted(() => {
  stopFc100RealtimeRefresh()
})
</script>

<style lang="scss" scoped>
/* 自 wayline.vue 平移的 FC100 区块样式；.planned-wayline-* / .planning-* 与 wayline.vue
   暂存在两份（M4T 列表仍在父页面），Task 8 面板深色重构时统一收敛。 */
.fc100-delivery-view {
  font-size: 12px;
  color: #d9d9d9;
}
.planning-row {
  margin-bottom: 8px;
}
.planning-row:last-child {
  margin-bottom: 0;
}
.planning-label {
  display: block;
  color: #8c8c8c;
  margin-bottom: 4px;
}
.planning-actions {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 6px;
}
.fc100-task-actions {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}
.fc100-task-hint {
  margin: 2px 0 8px;
  padding: 6px 8px;
  border-radius: 4px;
  font-size: 11px;
  color: #9aa4b0;
  background: #2f2f2f;
}
.fc100-task-actions .ant-btn:not(.wayline-button-wrap) {
  padding-left: 8px;
  padding-right: 8px;
}
.planning-actions .ant-btn {
  min-width: 0;
  max-width: 100%;
  white-space: nowrap;
}
.planning-empty {
  padding: 8px;
  border-radius: 3px;
  background: #353535;
  color: #8c8c8c;
  text-align: center;
}
.planning-section-gap {
  height: 6px;
  border-bottom: 1px solid #4f4f4f;
  margin: 10px 0;
}
.planned-wayline-panel {
  margin: 10px auto 0;
  width: 95%;
  padding: 10px;
  background: #2b2b2b;
  border-radius: 4px;
  color: #d9d9d9;
  font-size: 12px;
}
.fc100-planning-panel {
  margin: 10px auto 0;
  width: 95%;
  padding: 10px;
  background: #242f2c;
  border: 1px solid rgba(25, 190, 107, 0.28);
  border-radius: 4px;
  color: #d9d9d9;
  font-size: 12px;
}
.fc100-planning-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 13px;
  font-weight: 700;
  color: #f5f5f5;
  margin-bottom: 8px;
}
.fc100-planning-title > span {
  min-width: 0;
}
.fc100-planning-title .ant-btn {
  flex: 0 0 auto;
}
.fc100-device-option {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
  padding: 2px 0;
}
.fc100-device-option-main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
}
.fc100-device-option-model {
  min-width: 0;
  color: #262626;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.fc100-device-option-sn {
  color: #8c8c8c;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.fc100-device-option-status {
  flex: 0 0 auto;
  padding: 1px 6px;
  border-radius: 2px;
  background: #f0f0f0;
  color: #8c8c8c;
  font-size: 12px;
}
.fc100-device-option-status.online {
  background: rgba(25, 190, 107, 0.14);
  color: #19be6b;
}
.fc100-device-props,
.fc100-task-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
  margin-bottom: 8px;
  color: hsla(0, 0%, 100%, 0.65);
}
.fc100-selected-wayline {
  min-height: 30px;
  padding: 6px 8px;
  border-radius: 3px;
  background: #1f1f1f;
  color: #bfbfbf;
  word-break: break-word;
}
.fc100-result {
  margin-top: 8px;
  padding: 6px 8px;
  border-left: 2px solid #19be6b;
  border-radius: 3px;
  background: rgba(25, 190, 107, 0.12);
  color: #d9f7be;
  line-height: 1.4;
  word-break: break-word;
}
.planned-wayline-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  font-weight: 700;
  color: #f5f5f5;
  margin-bottom: 8px;
}
.planned-wayline-card {
  padding: 8px;
  margin-bottom: 8px;
  background: #353535;
  border-radius: 3px;
  border: 1px solid transparent;
}
.planned-wayline-card--selected {
  border-color: #19be6b;
  background: #26382f;
}
.planned-wayline-list {
  max-height: 360px;
  overflow-y: auto;
  padding-right: 2px;
}
.planned-wayline-list-footer {
  padding: 8px 0 2px;
  color: #8c8c8c;
  text-align: center;
}
.planned-wayline-card:last-child {
  margin-bottom: 0;
}
.planned-wayline-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}
.planned-wayline-name {
  min-width: 0;
  color: #f5f5f5;
  font-weight: 700;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.planned-wayline-status {
  flex: 0 0 auto;
  padding: 1px 6px;
  border-radius: 2px;
  background: #1f1f1f;
  color: #faad14;
  font-size: 11px;
}
.planned-wayline-status.failed {
  background: #cf1322;
  color: #fff;
}
.planned-wayline-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
  color: hsla(0, 0%, 100%, 0.65);
  margin-bottom: 5px;
}
.planned-wayline-meta.muted {
  color: hsla(0, 0%, 100%, 0.35);
}
.planned-wayline-actions {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  grid-auto-flow: row dense;
  gap: 6px;
  margin-top: 6px;
}
.planned-wayline-actions .ant-btn {
  min-width: 0;
  max-width: 100%;
  white-space: nowrap;
}
.planned-wayline-actions--minimal {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}
.fc100-terminal-panel {
  margin-top: 10px;
  padding: 10px;
  border: 1px solid rgba(82, 196, 26, 0.42);
  border-radius: 4px;
  background: rgba(82, 196, 26, 0.08);
}
.fc100-terminal-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  color: #f5f5f5;
  font-weight: 700;
}
.fc100-terminal-head small {
  min-width: 0;
  color: #8c8c8c;
  font-weight: 400;
  text-align: right;
}
.fc100-terminal-note {
  margin-top: 5px;
  color: #bfbfbf;
  font-size: 12px;
  line-height: 1.4;
}
.fc100-terminal-actions {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 6px;
  margin-top: 8px;
}
.fc100-terminal-actions .ant-btn {
  min-width: 0;
  max-width: 100%;
  font-weight: 600;
  white-space: nowrap;
}
.fc100-terminal-actions__primary {
  border-color: #1677ff;
  background: #1677ff;
  color: #fff;
}
.fc100-terminal-actions__neutral {
  border-color: #d9d9d9;
  background: #f5f5f5;
  color: #262626;
}
.fc100-terminal-actions__danger {
  border-color: #ff4d4f;
  background: #ff4d4f;
  color: #fff;
}
.fc100-terminal-actions__return {
  border-color: #faad14;
  background: #faad14;
  color: #1f1f1f;
}
.fc100-terminal-actions__primary[disabled],
.fc100-terminal-actions__neutral[disabled],
.fc100-terminal-actions__danger[disabled],
.fc100-terminal-actions__return[disabled] {
  border-color: #595959;
  background: #f5f5f5;
  color: #bfbfbf;
}
.wayline-button-wrap {
  grid-column: 1 / -1;
  min-width: 0 !important;
  max-width: 100%;
  height: auto;
  min-height: 24px;
  white-space: normal !important;
  overflow-wrap: anywhere;
}
</style>
