<template>
  <section class="operation-map-panel">
    <div class="operation-map-toolbar">
      <div>
        <span class="panel-kicker">Operation Map</span>
        <h3>处置地图</h3>
      </div>
      <label class="uom-toggle">
        <span>UOM参考层</span>
        <a-switch v-model:checked="uomVisible" size="small" />
      </label>
    </div>
    <div class="operation-map-stage">
      <CockpitSituationMap
        :layers="layers"
        :focus-key="focusKey"
        :show-uom-airspace="uomVisible"
        @select="handleLayerSelect"
      />
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import CockpitSituationMap from '/@/pages/page-web/projects/CockpitSituationMap.vue'
import type { OperationIncidentDTO } from '/@/types/operation/incident'
import { levelBadge, statusBadge } from '/@/pages/page-web/projects/operation-policy.mjs'

const props = defineProps<{
  incidents: OperationIncidentDTO[];
  selectedIncident: OperationIncidentDTO | null;
}>()

const emit = defineEmits(['select'])

const uomVisible = ref(false)

const focusKey = computed(() =>
  props.selectedIncident ? incidentLayerId(props.selectedIncident) : undefined,
)

const layers = computed(() => buildOperationLayers(props.incidents))

function buildOperationLayers (incidents: OperationIncidentDTO[]) {
  const fireMarkers: any[] = []
  const errorCircles: any[] = []
  const boundsPoints: [number, number][] = []
  const unlocatedEvents: any[] = []

  for (const incident of incidents) {
    const point = incidentPoint(incident)
    const level = levelBadge(incident.level)
    const status = statusBadge(incident.status)
    if (!point) {
      unlocatedEvents.push({
        eventId: incident.incidentNo,
        reason: '缺少事件中心坐标',
        missionNo: null,
      })
      continue
    }

    fireMarkers.push({
      id: incidentLayerId(incident),
      eventId: incident.incidentNo,
      coordinates: point,
      level: incident.level || 'UNKNOWN',
      tone: level.tone,
      color: colorByTone(level.tone),
      priority: level.priority,
      popup: {
        title: `${level.label}风险 · ${incident.incidentNo}`,
        detail: [
          `状态 ${status.label}`,
          `火情 #${incident.fireEventId}`,
          `半径 ${formatRadius(incident.riskRadiusM)}`,
          `更新 ${formatTime(incident.updateTime)}`,
        ].join(' · '),
      },
    })
    boundsPoints.push(point)

    const radius = Number(incident.riskRadiusM)
    if (Number.isFinite(radius) && radius > 0) {
      errorCircles.push({
        id: `risk:${incident.id}`,
        eventId: incident.incidentNo,
        center: point,
        radiusM: radius,
        color: colorByTone(level.tone),
        estimated: true,
        label: '风险半径',
      })
    }
  }

  fireMarkers.sort((a, b) => b.priority - a.priority)

  return {
    fireMarkers,
    errorCircles,
    routeLines: [],
    aircraftMarkers: [],
    bounds: buildBounds(boundsPoints),
    unlocatedEvents,
  }
}

function handleLayerSelect (key: string) {
  const incident = props.incidents.find(item => incidentLayerId(item) === key)
  if (incident) emit('select', incident)
}

function incidentLayerId (incident: OperationIncidentDTO) {
  return `incident:${incident.id}`
}

function incidentPoint (incident: OperationIncidentDTO): [number, number] | null {
  const lng = Number(incident.centerLng)
  const lat = Number(incident.centerLat)
  if (!Number.isFinite(lng) || !Number.isFinite(lat)) return null
  if (Math.abs(lng) > 180 || Math.abs(lat) > 90) return null
  if (lng === 0 && lat === 0) return null
  return [lng, lat]
}

function buildBounds (points: [number, number][]) {
  if (!points.length) return null
  return points.reduce((acc, point) => ({
    west: Math.min(acc.west, point[0]),
    south: Math.min(acc.south, point[1]),
    east: Math.max(acc.east, point[0]),
    north: Math.max(acc.north, point[1]),
  }), {
    west: points[0][0],
    south: points[0][1],
    east: points[0][0],
    north: points[0][1],
  })
}

function colorByTone (tone: string) {
  const colors: Record<string, string> = {
    danger: '#ff4d4f',
    warning: '#faad14',
    safe: '#52c41a',
    default: '#1677ff',
  }
  return colors[tone] || colors.default
}

function formatRadius (value?: number) {
  const n = Number(value)
  return Number.isFinite(n) ? `${n.toFixed(0)}m` : '-'
}

function formatTime (value?: number) {
  if (!value) return '-'
  return new Date(value).toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  })
}
</script>

<style lang="scss" scoped>
.operation-map-panel {
  display: flex;
  min-width: 0;
  min-height: 0;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  border: 1px solid #2a3b50;
  border-radius: 8px;
  background: #121f2f;
}

.operation-map-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;

  h3 {
    margin: 0;
    color: #e2eaf5;
    font-size: 16px;
  }
}

.panel-kicker {
  display: block;
  color: #91a4bd;
  font-size: 11px;
  text-transform: uppercase;
}

.uom-toggle {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: #b8cbe2;
  font-size: 12px;
  white-space: nowrap;
}

.operation-map-stage {
  min-width: 0;
  min-height: 0;
  flex: 1;

  :deep(.cockpit-situation-map) {
    border-radius: 6px;
  }
}
</style>
