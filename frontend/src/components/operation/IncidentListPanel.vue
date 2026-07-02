<template>
  <aside class="incident-list-panel">
    <div class="panel-title-row">
      <div>
        <span class="panel-kicker">Incidents</span>
        <h3>事件列表</h3>
      </div>
      <a-button size="small" @click="emit('refresh')">刷新</a-button>
    </div>

    <div class="incident-filters">
      <a-select
        :value="statusFilter || undefined"
        allow-clear
        size="small"
        placeholder="状态"
        style="width: 100%"
        @change="handleStatusChange"
      >
        <a-select-option
          v-for="item in statusOptions"
          :key="item.value"
          :value="item.value"
        >
          {{ item.label }}
        </a-select-option>
      </a-select>
      <a-select
        :value="levelFilter || undefined"
        allow-clear
        size="small"
        placeholder="级别"
        style="width: 100%"
        @change="handleLevelChange"
      >
        <a-select-option
          v-for="item in levelOptions"
          :key="item.value"
          :value="item.value"
        >
          {{ item.label }}
        </a-select-option>
      </a-select>
    </div>

    <a-spin :spinning="loading">
      <div v-if="incidents.length" class="incident-list">
        <button
          v-for="item in incidents"
          :key="item.id"
          type="button"
          class="incident-row"
          :class="{ selected: selectedId === item.id }"
          @click="emit('select', item)"
        >
          <span class="incident-row-main">
            <strong>{{ item.incidentNo }}</strong>
            <small>火情 #{{ item.fireEventId }} · {{ formatTime(item.createTime) }}</small>
          </span>
          <span class="incident-row-tags">
            <a-tag :color="levelBadge(item.level).color">{{ levelBadge(item.level).label }}</a-tag>
            <a-tag :color="statusBadge(item.status).color">{{ statusBadge(item.status).label }}</a-tag>
          </span>
        </button>
      </div>
      <a-empty v-else description="暂无事件" />
    </a-spin>
  </aside>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { OperationIncidentDTO } from '/@/types/operation/incident'
import {
  INCIDENT_STATUSES,
  LEVEL_BADGE_MAP,
  levelBadge,
  statusBadge,
} from '/@/pages/page-web/projects/operation-policy.mjs'

defineProps<{
  incidents: OperationIncidentDTO[];
  loading: boolean;
  selectedId?: number;
  statusFilter: string;
  levelFilter: string;
}>()

const emit = defineEmits(['select', 'refresh', 'update:statusFilter', 'update:levelFilter'])

const statusOptions = computed(() =>
  INCIDENT_STATUSES.map((value: string) => ({
    value,
    label: statusBadge(value).label,
  })),
)

const levelOptions = computed(() =>
  Object.keys(LEVEL_BADGE_MAP).map(value => ({
    value,
    label: LEVEL_BADGE_MAP[value].label,
  })),
)

function handleStatusChange (value: string | undefined) {
  emit('update:statusFilter', value || '')
}

function handleLevelChange (value: string | undefined) {
  emit('update:levelFilter', value || '')
}

function formatTime (value?: number) {
  if (!value) return '-'
  return new Date(value).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  })
}
</script>

<style lang="scss" scoped>
.incident-list-panel {
  display: flex;
  min-width: 0;
  min-height: 0;
  flex-direction: column;
  gap: 12px;
  padding: 14px;
  border: 1px solid #dde3ea;
  border-radius: 8px;
  background: #ffffff;
}

.panel-title-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;

  h3 {
    margin: 0;
    color: #1f2933;
    font-size: 16px;
    line-height: 1.4;
  }
}

.panel-kicker {
  display: block;
  color: #6b7a8c;
  font-size: 11px;
  text-transform: uppercase;
}

.incident-filters {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.incident-list {
  display: flex;
  min-height: 0;
  flex-direction: column;
  gap: 8px;
  overflow: auto;
}

.incident-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 8px;
  width: 100%;
  padding: 10px;
  border: 1px solid #e4e9ef;
  border-radius: 6px;
  background: #f9fbfd;
  text-align: left;
  cursor: pointer;
}

.incident-row:hover,
.incident-row.selected {
  border-color: #1677ff;
  background: #eef6ff;
}

.incident-row-main {
  display: grid;
  gap: 4px;
  min-width: 0;

  strong {
    color: #1f2933;
    font-size: 13px;
    overflow-wrap: anywhere;
  }

  small {
    color: #697586;
    font-size: 12px;
  }
}

.incident-row-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
</style>
