<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { missionApi } from '/@/api/fire/mission'
import { eventApi } from '/@/api/fire/event'
import { waypointApi } from '/@/api/fire/waypoint'
import { routeApi } from '/@/api/fire/route'
import type { FireMissionDTO } from '/@/types/fire/mission'
import type { FireEventDTO } from '/@/types/fire/event'
import type { WaypointDTO } from '/@/types/fire/waypoint'
import StatusTag from '/@/components/fire/StatusTag.vue'
import AmapMissionMap from '/@/components/fire/AmapMissionMap.vue'

const props = defineProps<{ no: string }>()
const router = useRouter()

const dto = ref<FireMissionDTO | null>(null)
const fireEvent = ref<FireEventDTO | null>(null)
const waypoints = ref<WaypointDTO[]>([])
const latestFileId = ref<number | null>(null)
const loading = ref(false)

const WAYPOINT_TYPE_COLOR: Record<string, string> = {
  TAKEOFF: 'blue',
  CLIMB: 'geekblue',
  APPROACH: 'cyan',
  HOLD_UPWIND: 'gold',
  DROP: 'red',
  EXIT: 'orange',
  RETURN: 'green',
}

function typeColor (t: string): string {
  return WAYPOINT_TYPE_COLOR[t] ?? 'default'
}

// haversine 两点距离，单位 m
function haversine (lat1: number, lng1: number, lat2: number, lng2: number): number {
  const R = 6371000
  const toRad = (d: number) => (d * Math.PI) / 180
  const dLat = toRad(lat2 - lat1)
  const dLng = toRad(lng2 - lng1)
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2
  return 2 * R * Math.asin(Math.sqrt(a))
}

const totalDistance = computed(() => {
  const wps = waypoints.value
  if (wps.length < 2) return 0
  let sum = 0
  for (let i = 1; i < wps.length; i++) {
    sum += haversine(wps[i - 1].lat, wps[i - 1].lng, wps[i].lat, wps[i].lng)
  }
  return Math.round(sum)
})

const altStats = computed(() => {
  const alts = waypoints.value.map((w) => w.alt)
  if (alts.length === 0) return { max: 0, min: 0, avg: 0 }
  const max = Math.max(...alts)
  const min = Math.min(...alts)
  const avg = Math.round(alts.reduce((s, v) => s + v, 0) / alts.length)
  return { max, min, avg }
})

async function downloadKmz () {
  if (latestFileId.value == null) return
  try {
    const res = await routeApi.fileDownloadUrl(props.no, latestFileId.value)
    const url = res.data.data?.url
    if (!url) {
      message.error('获取下载链接失败')
      return
    }
    window.open(url, '_blank')
  } catch {
    message.error('获取下载链接失败')
  }
}

async function refresh () {
  loading.value = true
  try {
    const mRes = await missionApi.detail(props.no)
    dto.value = mRes.data.data ?? null
    if (!dto.value) return

    const [evRes, wpRes, fileRes] = await Promise.allSettled([
      eventApi.get(String(dto.value.fireEventId)),
      waypointApi.list(props.no),
      routeApi.getLatest(props.no),
    ])

    if (evRes.status === 'fulfilled') {
      fireEvent.value = evRes.value.data.data ?? null
    }
    if (wpRes.status === 'fulfilled') {
      waypoints.value = wpRes.value.data.data ?? []
    }
    if (fileRes.status === 'fulfilled') {
      latestFileId.value = fileRes.value.data.data?.id ?? null
    }
  } catch {
    message.error('加载航线预览失败')
  } finally {
    loading.value = false
  }
}

onMounted(refresh)
</script>

<template>
  <a-spin :spinning="loading" style="display: block">
    <!-- 顶部标题栏 -->
    <div style="padding: 8px 16px; display: flex; align-items: center; gap: 12px; background: #fff; border-bottom: 1px solid #f0f0f0">
      <a-button size="small" @click="router.back()">← 返回</a-button>
      <span style="font-weight: 600; font-size: 15px">航线预览 — {{ props.no }}</span>
      <StatusTag v-if="dto" :status="dto.status" />
      <a-button size="small" :loading="loading" @click="refresh">刷新</a-button>
      <a-button
        type="primary"
        size="small"
        :disabled="latestFileId == null"
        @click="downloadKmz"
      >下载 KMZ</a-button>
    </div>

    <a-row style="height: calc(100vh - 49px); overflow: hidden">
      <!-- 左侧：全屏地图 -->
      <a-col :span="18" style="height: 100%">
        <AmapMissionMap
          v-if="fireEvent"
          :fire-lat="fireEvent.lat"
          :fire-lng="fireEvent.lng"
          :waypoints="waypoints"
          :wind-direction-deg="dto?.windDirectionDeg ?? undefined"
          :wind-speed="dto?.windSpeedAtApproval ?? undefined"
          height="calc(100vh - 49px)"
        />
        <div v-else style="height: 100%; display: flex; align-items: center; justify-content: center">
          <a-empty description="火情坐标加载中..." />
        </div>
      </a-col>

      <!-- 右侧：航点列表 + 统计 -->
      <a-col :span="6" style="height: 100%; overflow-y: auto; border-left: 1px solid #f0f0f0; padding: 8px">
        <a-card size="small" title="航点 P0-P6 + 操作" :bordered="false">
          <a-list
            size="small"
            :data-source="waypoints"
          >
            <template #renderItem="{ item }: { item: WaypointDTO }">
              <a-list-item style="font-size: 12px; padding: 4px 0">
                <span style="font-weight: 600; min-width: 28px; display: inline-block">P{{ item.waypointIndex }}</span>
                <a-tag style="margin: 0 4px" :color="typeColor(item.waypointType)">{{ item.waypointType }}</a-tag>
                <span>H{{ item.alt }}m</span>
                <span style="color: #888; margin-left: 4px">{{ item.lat.toFixed(6) }},{{ item.lng.toFixed(6) }}</span>
              </a-list-item>
            </template>
          </a-list>

          <a-divider style="margin: 8px 0" />

          <a-descriptions :column="1" size="small" bordered>
            <a-descriptions-item label="航线总长">{{ totalDistance }} m</a-descriptions-item>
            <a-descriptions-item label="最高高度">{{ altStats.max }} m</a-descriptions-item>
            <a-descriptions-item label="最低高度">{{ altStats.min }} m</a-descriptions-item>
            <a-descriptions-item label="平均高度">{{ altStats.avg }} m</a-descriptions-item>
            <a-descriptions-item label="风速">{{ dto?.windSpeedAtApproval ?? '-' }} m/s</a-descriptions-item>
            <a-descriptions-item label="风向">{{ dto?.windDirectionDeg ?? '-' }}°</a-descriptions-item>
          </a-descriptions>
        </a-card>
      </a-col>
    </a-row>
  </a-spin>
</template>
