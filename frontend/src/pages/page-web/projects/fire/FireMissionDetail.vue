<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { missionApi } from '/@/api/fire/mission'
import { eventApi } from '/@/api/fire/event'
import { waypointApi } from '/@/api/fire/waypoint'
import { deliveryApi } from '/@/api/fire/delivery'
import { reviewApi } from '/@/api/fire/review'
import type { ReviewDTO } from '/@/api/fire/review'
import type { FireMissionDTO, MissionLogDTO } from '/@/types/fire/mission'
import type { FireEventDTO } from '/@/types/fire/event'
import type { WaypointDTO } from '/@/types/fire/waypoint'
import type { DeliveryTaskStatus } from '/@/api/fire/delivery'
import StatusTag from '/@/components/fire/StatusTag.vue'
import AmapMissionMap from '/@/components/fire/AmapMissionMap.vue'
import ActionButtons from '/@/components/fire/ActionButtons.vue'
import MissionTimeline from '/@/components/fire/MissionTimeline.vue'

const props = defineProps<{ no: string }>()
const router = useRouter()

const dto = ref<FireMissionDTO | null>(null)
const fireEvent = ref<FireEventDTO | null>(null)
const waypoints = ref<WaypointDTO[]>([])
const logs = ref<MissionLogDTO[]>([])
const deliveryStatus = ref<DeliveryTaskStatus | null>(null)
const review = ref<ReviewDTO | null>(null)
const loading = ref(false)

const waypointColumns = [
  { title: '序号', dataIndex: 'waypointIndex', key: 'waypointIndex', width: 60 },
  { title: '类型', key: 'waypointType', width: 130 },
  { title: '纬度', dataIndex: 'lat', key: 'lat' },
  { title: '经度', dataIndex: 'lng', key: 'lng' },
  { title: '高度(m)', dataIndex: 'alt', key: 'alt', width: 90 },
  { title: '备注', dataIndex: 'remark', key: 'remark' },
]

const waypointTypeColor: Record<string, string> = {
  TAKEOFF: 'blue',
  CLIMB: 'geekblue',
  APPROACH: 'cyan',
  HOLD_UPWIND: 'gold',
  DROP: 'red',
  EXIT: 'orange',
  RETURN: 'green',
}

async function refresh () {
  loading.value = true
  try {
    const res = await missionApi.detail(props.no)
    dto.value = res.data.data ?? null

    if (!dto.value) return

    // Parallel fetch: event, waypoints, logs, delivery, review
    const [evRes, wpRes, logRes, delRes, revRes] = await Promise.allSettled([
      eventApi.get(String(dto.value.fireEventId)),
      waypointApi.list(props.no),
      missionApi.logs(props.no),
      deliveryApi.status(props.no),
      reviewApi.get(props.no),
    ])

    if (evRes.status === 'fulfilled') {
      fireEvent.value = evRes.value.data.data ?? null
    }
    if (wpRes.status === 'fulfilled') {
      waypoints.value = wpRes.value.data.data ?? []
    }
    if (logRes.status === 'fulfilled') {
      logs.value = logRes.value.data.data ?? []
    }
    if (delRes.status === 'fulfilled') {
      deliveryStatus.value = delRes.value.data.data ?? null
    }
    if (revRes.status === 'fulfilled') {
      review.value = revRes.value.data.data ?? null
    }
    // 404 on review is normal — leave review.value as null
  } catch (e) {
    message.error('加载任务详情失败')
  } finally {
    loading.value = false
  }
}

function fmt (ts: number | null | undefined): string {
  if (!ts) return '-'
  return new Date(ts).toLocaleString('zh-CN')
}

function fmtRemaining (ms: number | null | undefined): string {
  if (ms == null) return '-'
  const totalSeconds = Math.ceil(Math.max(0, Number(ms)) / 1000)
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${minutes}:${String(seconds).padStart(2, '0')}`
}

function fmtTemperature (temperature: number | null | undefined, unit: string | null | undefined): string {
  if (temperature == null || Number.isNaN(temperature)) return '-'
  return `${temperature.toFixed(2)} ${unit ?? 'C'}`
}

onMounted(refresh)
</script>

<template>
  <div style="padding: 24px">
    <div style="margin-bottom: 16px; display: flex; align-items: center; gap: 12px">
      <a-button @click="router.back()">← 返回</a-button>
      <h2 style="margin: 0">任务详情 — {{ props.no }}</h2>
      <a-button type="primary" :loading="loading" @click="refresh">刷新</a-button>
    </div>

    <a-spin :spinning="loading">
      <a-row :gutter="[16, 16]">
        <a-col :span="24">
          <a-card title="可用操作" size="small" class="mission-detail-actions-card">
            <ActionButtons
              v-if="dto"
              :mission-no="dto.missionNo"
              :actions="dto.availableActions"
              @refresh="refresh"
            />
            <span v-else style="color: #aaa">-</span>
          </a-card>
        </a-col>

        <!-- 区 1: 基本信息 -->
        <a-col :span="24">
          <a-card title="基本信息" size="small">
            <a-descriptions :column="3" size="small">
              <a-descriptions-item label="任务编号">{{ dto?.missionNo ?? '-' }}</a-descriptions-item>
              <a-descriptions-item label="状态">
                <StatusTag v-if="dto" :status="dto.status" />
              </a-descriptions-item>
              <a-descriptions-item label="版本">{{ dto?.version ?? '-' }}</a-descriptions-item>
              <a-descriptions-item label="尝试次数">{{ dto?.attemptIndex ?? '-' }}</a-descriptions-item>
              <a-descriptions-item label="飞机 SN">{{ dto?.aircraftSn ?? '-' }}</a-descriptions-item>
              <a-descriptions-item label="审批人">{{ dto?.approverId ?? '-' }}</a-descriptions-item>
              <a-descriptions-item label="创建时间">{{ fmt(dto?.createTime) }}</a-descriptions-item>
              <a-descriptions-item label="审批时间">{{ fmt(dto?.approvedAt) }}</a-descriptions-item>
              <a-descriptions-item label="待释放倒计时">{{ fmtRemaining(dto?.releasePendingRemainingMs) }}</a-descriptions-item>
            </a-descriptions>
            <a-alert
              v-if="dto?.status === 'PAYLOAD_RELEASE_PENDING'"
              type="warning"
              show-icon
              message="超时未确认将自动返航"
              style="margin-top: 8px"
            />
          </a-card>
        </a-col>

        <!-- 区 2: 火情 -->
        <a-col :span="12">
          <a-card title="火情信息" size="small">
            <a-descriptions :column="2" size="small">
              <a-descriptions-item label="纬度">{{ fireEvent?.lat ?? '-' }}</a-descriptions-item>
              <a-descriptions-item label="经度">{{ fireEvent?.lng ?? '-' }}</a-descriptions-item>
              <a-descriptions-item label="置信度">{{ fireEvent?.confidence ?? '-' }}</a-descriptions-item>
              <a-descriptions-item label="火情等级">{{ fireEvent?.fireLevel ?? '-' }}</a-descriptions-item>
              <a-descriptions-item label="热成像温度">
                {{ fmtTemperature(fireEvent?.thermalTemperature, fireEvent?.temperatureUnit) }}
              </a-descriptions-item>
            </a-descriptions>
            <div v-if="fireEvent?.thermalImageUrl || fireEvent?.visibleImageUrl" style="margin-top: 8px; display: flex; gap: 8px">
              <img v-if="fireEvent?.thermalImageUrl" :src="fireEvent.thermalImageUrl" style="height: 80px; object-fit: cover" alt="热成像" />
              <img v-if="fireEvent?.visibleImageUrl" :src="fireEvent.visibleImageUrl" style="height: 80px; object-fit: cover" alt="可见光" />
            </div>
          </a-card>
        </a-col>

        <!-- 区 3: Mini 地图 -->
        <a-col :span="12">
          <a-card title="位置地图" size="small" style="cursor: pointer" @click="router.push(`/fire-route-preview/${props.no}`)">
            <AmapMissionMap
              v-if="fireEvent"
              :fire-lat="fireEvent.lat"
              :fire-lng="fireEvent.lng"
              height="200px"
            />
            <a-empty v-else description="火情坐标加载中..." />
            <div style="text-align: center; margin-top: 4px; color: #1890ff; font-size: 12px">点击查看完整航线图</div>
          </a-card>
        </a-col>

        <!-- 区 4: 任务参数 -->
        <a-col :span="12">
          <a-card title="任务参数" size="small">
            <a-descriptions :column="2" size="small">
              <a-descriptions-item label="载水量 (L)">{{ dto?.waterLoadLiters ?? '-' }}</a-descriptions-item>
              <a-descriptions-item label="风速 (m/s)">{{ dto?.windSpeedAtApproval ?? '-' }}</a-descriptions-item>
              <a-descriptions-item label="风向 (°)">{{ dto?.windDirectionDeg ?? '-' }}</a-descriptions-item>
              <a-descriptions-item label="起飞纬度">{{ dto?.takeoffLat ?? '-' }}</a-descriptions-item>
              <a-descriptions-item label="起飞经度">{{ dto?.takeoffLng ?? '-' }}</a-descriptions-item>
              <a-descriptions-item label="起飞高度 (m)">{{ dto?.takeoffAlt ?? '-' }}</a-descriptions-item>
              <a-descriptions-item label="载荷 ID">{{ dto?.payloadId ?? '-' }}</a-descriptions-item>
              <a-descriptions-item label="载荷类型">{{ dto?.payloadType ?? '-' }}</a-descriptions-item>
            </a-descriptions>
          </a-card>
        </a-col>

        <!-- 区 5: 状态机时间线 -->
        <a-col :span="12">
          <a-card title="操作日志" size="small" style="max-height: 360px; overflow-y: auto">
            <MissionTimeline :logs="logs" />
          </a-card>
        </a-col>

        <!-- 区 6: 安全校验（placeholder） -->
        <a-col :span="24">
          <a-card title="安全校验结果" size="small">
            <!-- 区 6 安全校验展示 — Day 14 补充，当前留 placeholder -->
            <a-empty description="安全校验详情（待 Day 14 实现）" />
          </a-card>
        </a-col>

        <!-- 区 7: 航点列表 -->
        <a-col :span="24">
          <a-card title="航点列表" size="small">
            <a-table
              :columns="waypointColumns"
              :data-source="waypoints"
              :row-key="(r: WaypointDTO) => r.waypointIndex"
              :pagination="false"
              size="small"
            >
              <template #bodyCell="{ column, record }: { column: { key: string }, record: WaypointDTO }">
                <template v-if="column.key === 'waypointType'">
                  <a-tag :color="waypointTypeColor[record.waypointType] ?? 'default'">
                    {{ record.waypointType }}
                  </a-tag>
                </template>
              </template>
            </a-table>
          </a-card>
        </a-col>

        <!-- 区 8: Delivery 状态 -->
        <a-col :span="12">
          <a-card title="Delivery 状态" size="small">
            <a-descriptions :column="2" size="small">
              <a-descriptions-item label="DJI 任务 ID">{{ dto?.djiTaskId ?? '-' }}</a-descriptions-item>
              <a-descriptions-item label="状态">{{ deliveryStatus?.status ?? '-' }}</a-descriptions-item>
              <a-descriptions-item label="阶段">{{ deliveryStatus?.phase ?? '-' }}</a-descriptions-item>
              <a-descriptions-item label="进度">
                {{ deliveryStatus?.progressPercent != null ? `${deliveryStatus.progressPercent}%` : '-' }}
              </a-descriptions-item>
              <a-descriptions-item label="消息">{{ deliveryStatus?.message ?? '-' }}</a-descriptions-item>
            </a-descriptions>
          </a-card>
        </a-col>

        <!-- 区 9: 复查结果 -->
        <a-col :span="12">
          <a-card title="复查结果" size="small">
            <template v-if="review">
              <a-descriptions :column="2" size="small">
                <a-descriptions-item label="复查员">{{ review.reviewerId }}</a-descriptions-item>
                <a-descriptions-item label="火已压制">{{ review.fireSuppressed ? '是' : '否' }}</a-descriptions-item>
                <a-descriptions-item label="需二次投放">{{ review.needSecondDrop ? '是' : '否' }}</a-descriptions-item>
                <a-descriptions-item label="建议">{{ review.suggestion ?? '-' }}</a-descriptions-item>
                <a-descriptions-item label="备注">{{ review.remark ?? '-' }}</a-descriptions-item>
              </a-descriptions>
            </template>
            <a-empty v-else description="未提交复查" />
          </a-card>
        </a-col>

      </a-row>
    </a-spin>
  </div>
</template>

<style lang="scss" scoped>
.mission-detail-actions-card {
  position: sticky;
  top: 0;
  z-index: 2;
}
</style>
