<template>
  <div class="project-wayline-wrapper height-100">
    <a-spin :spinning="loading" :delay="300" tip="下载中" size="large">
    <div style="height: 50px; line-height: 50px; border-bottom: 1px solid #4f4f4f; font-weight: 450;">
      <a-row>
        <a-col :span="1"></a-col>
        <a-col :span="15">{{ isTaskRouteSelector ? '选择KMZ航线文件' : '航线任务' }}</a-col>
        <a-col :span="8" v-if="importVisible" class="wayline-header-actions">
          <a-tooltip title="导入航线">
            <a-upload
              name="file"
              accept=".kmz"
              :multiple="false"
              :before-upload="beforeUpload"
              :show-upload-list="false"
              :custom-request="uploadFile"
            >
              <a-button class="wayline-header-icon-button" type="text" :loading="loading">
                <ImportOutlined />
              </a-button>
            </a-upload>
          </a-tooltip>
        </a-col>
      </a-row>
    </div>
    <div :style="{ height : height + 'px'}" class="scrollbar">
      <a-tabs
        v-if="showPlanningTools"
        v-model:activeKey="plannerTab"
        class="planner-mode-tabs">
        <a-tab-pane key="monitor" tab="监测规划" />
        <a-tab-pane key="delivery" tab="投放任务" />
      </a-tabs>
      <div v-if="showPlanningTools" v-show="plannerTab === 'monitor'">
      <div class="planned-wayline-panel">
        <div class="planned-wayline-title">
          <span>监测航线库</span>
          <a-button size="small" type="link" :loading="plannedWaylinesLoading" @click="refreshPlannedWaylines">
            刷新
          </a-button>
        </div>
        <div class="planning-empty" v-if="!plannedWaylinesLoading && plannedWaylinesData.data.length === 0">
          暂无已保存监测航线。
        </div>
        <div v-else class="planned-wayline-list" @scroll="onPlannedWaylinesScroll">
          <div class="planned-wayline-card" v-for="record in plannedWaylinesData.data" :key="record.plannedWaylineId" @click="onPreviewPlannedWayline(record)">
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
            <div class="planned-wayline-reason" v-if="getPlannedWaylineTaskReason(record)">
              {{ getPlannedWaylineTaskReason(record) }}
            </div>
            <div class="planned-wayline-meta muted">
              <span>机型 {{ record.aircraftModelKey || '-' }}</span>
              <span>更新于 {{ formatTimestamp(record.updateTime) }}</span>
            </div>
            <!-- P2.c L2 任务监控:只在 task 已 prepare 后显示 -->
            <div class="planned-wayline-monitor" v-if="record.flightId" @click.stop>
              <WaylineMissionMonitor
                :workspace-id="monitorWorkspaceId"
                :record="record"
                @change="(updated: any) => onMissionMonitorChange(updated)"
                @execute="openExecuteTargetModal" />
            </div>
            <div class="planned-wayline-actions">
              <a-button size="small" @click.stop="showPlannedWaylineDetail(record)">详情</a-button>
              <a-button size="small" :disabled="!canOverwritePlannedWayline(record)" @click.stop="onEditPlannedWayline(record)">编辑</a-button>
              <a-button
                v-for="action in getPlannedWaylineActions(record)"
                :key="action.key"
                size="small"
                :type="action.primary ? 'primary' : 'default'"
                :danger="action.danger"
                :class="{ 'wayline-button-wrap': action.wrap }"
                @click.stop="action.handler(record)">
                {{ action.label }}
              </a-button>
              <a-button size="small" danger @click.stop="onDeletePlannedWayline(record)">删除</a-button>
            </div>
          </div>
          <div class="planned-wayline-list-footer" v-if="plannedWaylinesLoading">加载中...</div>
          <div class="planned-wayline-list-footer" v-else-if="plannedWaylinesData.data.length > 0 && !plannedWaylinesCanRefresh">已加载全部</div>
        </div>
      </div>
      </div>
      <Fc100DeliveryView
        v-if="showPlanningTools"
        v-show="plannerTab === 'delivery'"
        :planned-waylines-data="plannedWaylinesData"
        :planned-waylines-loading="plannedWaylinesLoading"
        :planned-waylines-can-refresh="plannedWaylinesCanRefresh"
        :refresh-planned-waylines="refreshPlannedWaylines"
        :on-planned-waylines-scroll="onPlannedWaylinesScroll"
        :show-planned-wayline-detail="showPlannedWaylineDetail"
        :on-delete-planned-wayline="onDeletePlannedWayline" />
      <a-collapse
        v-if="showPlanningTools"
        v-show="plannerTab === 'monitor'"
        class="wayline-mode-collapse generated-wayline-collapse"
        :bordered="false"
        expandIconPosition="right"
        accordion
        style="background: #232323;">
        <a-collapse-panel key="generated-wayline" header="已生成航线" style="border-bottom: 1px solid #4f4f4f;">
          <div id="data" class="height-100 uranus-scrollbar" v-if="waylinesData.data.length !== 0" @scroll="onScroll">
            <div v-for="wayline in waylinesData.data" :key="wayline.id">
              <div class="wayline-panel" style="padding-top: 5px;" @click="selectRoute(wayline)">
                <div class="title">
                  <a-tooltip :title="wayline.name">
                    <div class="pr10" style="width: 120px; white-space: nowrap; text-overflow: ellipsis; overflow: hidden;">{{ wayline.name }}</div>
                  </a-tooltip>
                  <div class="ml10"><UserOutlined /></div>
                  <a-tooltip :title="wayline.user_name">
                    <div class="ml5 pr10" style="width: 80px; white-space: nowrap; text-overflow: ellipsis; overflow: hidden;">{{ wayline.user_name }}</div>
                  </a-tooltip>
                  <div class="fz20">
                    <a-dropdown>
                      <a style="color: white;">
                        <EllipsisOutlined />
                      </a>
                      <template #overlay>
                        <a-menu theme="dark" class="more" style="background: #3c3c3c;">
                          <a-menu-item @click="downloadWayline(wayline.id, wayline.name)">
                            <span>下载</span>
                          </a-menu-item>
                          <a-menu-item @click="showWaylineTip(wayline.id)">
                            <span>删除</span>
                          </a-menu-item>
                        </a-menu>
                      </template>
                    </a-dropdown>
                  </div>
                </div>
                <div class="ml10 mt5" style="color: hsla(0,0%,100%,0.65);">
                  <span><RocketOutlined /></span>
                  <span class="ml5">{{ DEVICE_NAME[wayline.drone_model_key] }}</span>
                  <span class="ml10"><CameraFilled style="border-top: 1px solid; padding-top: -3px;" /></span>
                  <span class="ml5" v-for="payload in wayline.payload_model_keys" :key="payload.id">
                    {{ DEVICE_NAME[payload] }}
                  </span>
                </div>
                <div class="mt5 ml10" style="color: hsla(0,0%,100%,0.35);">
                  <span class="mr10">更新于 {{ new Date(wayline.update_time).toLocaleString() }}</span>
                </div>
              </div>
            </div>
          </div>
          <div v-else>
            <a-empty :image-style="{ height: '60px', marginTop: '60px' }" />
          </div>
        </a-collapse-panel>
      </a-collapse>
      <div id="data" class="height-100 uranus-scrollbar" v-else-if="waylinesData.data.length !== 0" @scroll="onScroll">
        <div v-for="wayline in waylinesData.data" :key="wayline.id">
          <div class="wayline-panel" style="padding-top: 5px;" @click="selectRoute(wayline)">
            <div class="title">
              <a-tooltip :title="wayline.name">
                <div class="pr10" style="width: 120px; white-space: nowrap; text-overflow: ellipsis; overflow: hidden;">{{ wayline.name }}</div>
              </a-tooltip>
              <div class="ml10"><UserOutlined /></div>
              <a-tooltip :title="wayline.user_name">
                <div class="ml5 pr10" style="width: 80px; white-space: nowrap; text-overflow: ellipsis; overflow: hidden;">{{ wayline.user_name }}</div>
              </a-tooltip>
              <div class="fz20">
                <a-dropdown>
                  <a style="color: white;">
                    <EllipsisOutlined />
                  </a>
                  <template #overlay>
                    <a-menu theme="dark" class="more" style="background: #3c3c3c;">
                      <a-menu-item @click="downloadWayline(wayline.id, wayline.name)">
                        <span>下载</span>
                      </a-menu-item>
                      <a-menu-item @click="showWaylineTip(wayline.id)">
                        <span>删除</span>
                      </a-menu-item>
                    </a-menu>
                  </template>
                </a-dropdown>
              </div>
            </div>
            <div class="ml10 mt5" style="color: hsla(0,0%,100%,0.65);">
              <span><RocketOutlined /></span>
              <span class="ml5">{{ DEVICE_NAME[wayline.drone_model_key] }}</span>
              <span class="ml10"><CameraFilled style="border-top: 1px solid; padding-top: -3px;" /></span>
              <span class="ml5" v-for="payload in wayline.payload_model_keys" :key="payload.id">
                {{ DEVICE_NAME[payload] }}
              </span>
            </div>
            <div class="mt5 ml10" style="color: hsla(0,0%,100%,0.35);">
              <span class="mr10">更新于 {{ new Date(wayline.update_time).toLocaleString() }}</span>
            </div>
          </div>
        </div>
      </div>
      <div v-else>
        <a-empty :description="isTaskRouteSelector ? '暂无可选KMZ航线文件' : undefined" :image-style="{ height: '60px', marginTop: '60px' }" />
      </div>
      <a-modal v-model:visible="deleteTip" width="450px" :closable="false" :maskClosable="false" centered :okButtonProps="{ danger: true }" @ok="deleteWayline">
          <p class="pt10 pl20" style="height: 50px;">航线文件删除后不可恢复，是否继续？</p>
          <template #title>
              <div class="flex-row flex-justify-center">
                  <span>删除</span>
              </div>
          </template>
      </a-modal>
      <a-modal
        v-model:visible="savePlannedWaylineModal.visible"
        width="520px"
        :confirmLoading="loading"
        :title="savePlannedWaylineModal.saveAs ? '另存为规划航线' : '保存规划航线'"
        @ok="confirmSavePlannedWayline">
        <div class="planned-wayline-form">
          <div class="planning-row">
            <span class="planning-label">航线名称</span>
            <a-input v-model:value="savePlannedWaylineModal.name" placeholder="请输入规划航线名称" />
          </div>
          <div class="planning-row planning-two-col">
            <div>
              <span class="planning-label">机型</span>
              <a-select
                size="small"
                style="width: 100%;"
                v-model:value="savePlannedWaylineModal.aircraftModelKey">
                <a-select-option v-for="model in PLANNED_WAYLINE_MODEL_OPTIONS" :key="model" :value="model">
                  {{ model }}
                </a-select-option>
              </a-select>
            </div>
            <div>
              <span class="planning-label">航点数</span>
              <a-input :value="String(savePlannedWaylineModal.waypointCount)" disabled />
            </div>
          </div>
          <div class="planning-row planning-two-col">
            <div>
              <span class="planning-label">默认高度（米）</span>
              <a-input-number size="small" style="width: 100%;" :min="15" :step="1" v-model:value="savePlannedWaylineModal.defaultHeight" />
            </div>
            <div>
              <span class="planning-label">最大速度（米/秒）</span>
              <a-input-number size="small" style="width: 100%;" :min="2" :max="15" :step="1" v-model:value="savePlannedWaylineModal.maxSpeed" />
            </div>
          </div>
        </div>
      </a-modal>
      <a-modal
        v-model:visible="plannedWaylineDetailVisible"
        width="760px"
        title="规划航线详情"
        :footer="null">
        <div v-if="selectedPlannedWayline" class="planned-wayline-detail">
          <div class="planned-wayline-detail-grid">
            <span>名称</span><strong>{{ selectedPlannedWayline.name }}</strong>
            <span>状态</span><strong>{{ formatPlannedWaylineStatus(selectedPlannedWayline) }}</strong>
            <span>机型</span><strong>{{ selectedPlannedWayline.aircraftModelKey || '-' }}</strong>
            <span>飞行器</span><strong>{{ selectedPlannedWayline.aircraftSn || '-' }}</strong>
            <span>网关</span><strong>{{ selectedPlannedWayline.gatewaySn || '-' }}</strong>
            <span>默认高度</span><strong>{{ formatNumber(selectedPlannedWayline.defaultHeight) }} m</strong>
            <span>最大速度</span><strong>{{ formatNumber(selectedPlannedWayline.maxSpeed) }} m/s</strong>
            <span>创建人</span><strong>{{ selectedPlannedWayline.creator || '-' }}</strong>
            <span>发布人</span><strong>{{ selectedPlannedWayline.publisher || '-' }}</strong>
            <span>发布时间</span><strong>{{ formatTimestamp(selectedPlannedWayline.publishTime) }}</strong>
            <span>KMZ地址</span><strong>{{ selectedPlannedWayline.kmzUrl || '-' }}</strong>
            <span>KMZ MD5</span><strong>{{ selectedPlannedWayline.kmzMd5 || '-' }}</strong>
            <span>任务ID</span><strong>{{ selectedPlannedWayline.flightId || '-' }}</strong>
            <span>目标机场</span><strong>{{ selectedPlannedWayline.dockSn || '-' }}</strong>
            <span>目标无人机</span><strong>{{ selectedPlannedWayline.droneSn || '-' }}</strong>
            <span>任务进度</span><strong>{{ selectedPlannedWayline.taskProgress ?? '-' }}</strong>
            <span>失败原因</span><strong>{{ getPlannedWaylineTaskReason(selectedPlannedWayline) || '-' }}</strong>
            <span>创建时间</span><strong>{{ formatTimestamp(selectedPlannedWayline.createTime) }}</strong>
            <span>更新时间</span><strong>{{ formatTimestamp(selectedPlannedWayline.updateTime) }}</strong>
          </div>
          <div class="planned-wayline-detail-actions">
            <a-button size="small" :disabled="!canOverwritePlannedWayline(selectedPlannedWayline)" @click="onEditPlannedWayline(selectedPlannedWayline)">编辑</a-button>
            <a-button size="small" @click="onSavePlannedWaylineAs(selectedPlannedWayline)">另存为</a-button>
            <a-button
              v-for="action in getPlannedWaylineActions(selectedPlannedWayline)"
              :key="action.key"
              size="small"
              :type="action.primary ? 'primary' : 'default'"
              :danger="action.danger"
              :class="{ 'wayline-button-wrap': action.wrap }"
              @click="action.handler(selectedPlannedWayline)">
              {{ action.label }}
            </a-button>
            <a-button
              v-for="action in getFc100GeneratedWaylineActions(selectedPlannedWayline)"
              :key="action.key"
              size="small"
              :type="action.primary ? 'primary' : 'default'"
              :class="{ 'wayline-button-wrap': action.wrap }"
              :disabled="action.disabled"
              @click="action.handler(selectedPlannedWayline)">
              {{ action.label }}
            </a-button>
            <a-button size="small" danger @click="onDeletePlannedWayline(selectedPlannedWayline)">删除</a-button>
          </div>
          <div class="planned-wayline-waypoint-table">
            <div class="planned-wayline-waypoint-row head">
              <span>#</span><span>WGS84</span><span>GCJ02</span><span>高度</span>
            </div>
            <div class="planned-wayline-waypoint-row" v-for="wp in selectedPlannedWayline.waypoints" :key="wp.order">
              <span>{{ wp.order }}</span>
              <span>{{ wp.wgsLat.toFixed(6) }}, {{ wp.wgsLng.toFixed(6) }}</span>
              <span>{{ wp.gcjLat.toFixed(6) }}, {{ wp.gcjLng.toFixed(6) }}</span>
              <span>{{ formatNumber(wp.height) }} m</span>
            </div>
          </div>
        </div>
      </a-modal>
      <a-modal
        v-model:visible="executeTargetModal.visible"
        width="520px"
        title="选择执行飞行器"
        :confirmLoading="executeTargetModal.loading"
        ok-text="确认执行"
        cancel-text="取消"
        @ok="confirmExecuteTarget">
        <div class="execute-target-modal">
          <div class="execute-target-summary">
            <span>航线</span>
            <strong>{{ executeTargetModal.record?.name || '-' }}</strong>
          </div>
          <div class="execute-target-field">
            <span class="planning-label">在线飞行器</span>
            <a-select
              style="width: 100%;"
              :value="executeTargetModal.targetSn"
              placeholder="请选择在线飞行器"
              @change="(sn: string) => executeTargetModal.targetSn = sn">
              <a-select-option
                v-for="aircraft in executeTargetOptions"
                :key="aircraft.sn"
                :value="aircraft.sn">
                {{ aircraft.callsign || aircraft.sn }}<span v-if="aircraft.aircraftModelKey"> · {{ aircraft.aircraftModelKey }}</span>
              </a-select-option>
            </a-select>
          </div>
          <div class="planning-empty" v-if="executeTargetOptions.length === 0">
            当前没有检测到在线飞行器，请确认 MSDK 程序在线后点击左侧刷新。
          </div>
        </div>
      </a-modal>
    </div>
    </a-spin>
    <Teleport v-if="showPlanningTools && planningOverlayReady" to="#wayline-planning-overlay-host">
      <PlannerWorkspace
        v-show="plannerTab === 'monitor'"
        :can-execute="!!selectedAircraftSn"
        :on-start-placing="onStartPlacing"
        :on-stop-placing="onStopPlacing"
        :on-start-execution="onStartExecution"
        :on-stop-execution="onStopExecution"
        :on-save="onSavePlannedWayline" />
    </Teleport>
  </div>
</template>

<script lang="ts" setup>
import { reactive } from '@vue/reactivity'
import { message, Modal } from 'ant-design-vue'
import { computed, nextTick, onMounted, onUnmounted, onUpdated, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  createPlannedWayline,
  deletePlannedWayline,
  deleteWaylineFile,
  downloadWaylineFile,
  cancelPlannedWaylineTask,
  executePlannedWaylineTask,
  generatePlannedWaylineFile,
  getPlannedWayline,
  getPlannedWaylines,
  getWaylineFiles,
  importPlannedWaylineKmzFile,
  preparePlannedWaylineTask,
  updatePlannedWayline,
} from '/@/api/wayline'
import { ELocalStorageKey, ERouterName, EDeviceTypeName } from '/@/types'
import { EllipsisOutlined, CameraFilled, UserOutlined, ImportOutlined } from '@ant-design/icons-vue'
import { DEVICE_MODEL_KEY, DEVICE_NAME, EModeCode } from '/@/types/device'
import { useMyStore } from '/@/store'
import { CreatePlannedWaylineBody, PlannedWaypoint, PlannedWaylineRecord, PlannedWaylineStatus, WaylineFile } from '/@/types/wayline'
import { downloadFile } from '/@/utils/common'
import { IPage } from '/@/api/http/type'
import { CURRENT_CONFIG } from '/@/api/http/config'
import { load } from '@amap/amap-jsapi-loader'
import { getRoot } from '/@/root'
import { gcj02towgs84, wgs84togcj02 } from '/@/vendors/coordtransform'
import {
  getPlanningStateRaw,
  startPlanning as planningStart,
  stopPlanning as planningStop,
  startExecution as planningExecute,
  stopExecution as planningStopExec,
  setTargetAircraft as planningSetTarget,
  buildPlannedWaylineBody,
  loadPlannedWayline,
  previewPlannedWayline,
  resetPlanningDraft,
  setFlightPositionFromRecord,
  setFlightPositionFromWgs,
  requestAircraftRecenter,
  setTrackedAircraft,
} from '/@/hooks/use-wayline-planning'
import { getDeviceTopo } from '/@/api/manage'
import { listMsdkDevices, type MsdkDeviceState } from '/@/api/msdk-device'
import WaylineMissionMonitor from '/@/components/WaylineMissionMonitor.vue'
import Fc100DeliveryView from '/@/components/wayline-planner/Fc100DeliveryView.vue'
import PlannerWorkspace from '/@/components/wayline-planner/PlannerWorkspace.vue'
import { setPlannerTab, usePlannerUi } from '/@/hooks/use-planner-ui'
import { getFc100GeneratedWaylineActions } from '/@/hooks/use-fc100-delivery'
import type { FileItem } from '/@/components/wayline-planner/wayline-format'
import { canOverwritePlannedWayline, formatNumber, formatPlannedWaylineStatus, formatSafePlannedWaylineTimestamp, formatTimestamp, getPlannedWaylineTaskReason, normalizePlannedWaylineStatus, sanitizeDjiWaylineName } from '/@/components/wayline-planner/wayline-format'

const loading = ref(false)
const store = useMyStore()
const route = useRoute()
const isTaskRouteSelector = computed(() => route.name === ERouterName.SELECT_PLAN)
const showPlanningTools = computed(() => !isTaskRouteSelector.value)

// ---------- Planned wayline (click-to-fly) ----------
const planningState = getPlanningStateRaw()
const plannerUi = usePlannerUi()
const plannerTab = computed({
  get: () => plannerUi.activeTab,
  set: (v: 'monitor' | 'delivery') => setPlannerTab(v),
})
const selectedAircraftSn = ref('')
const planningOverlayReady = ref(false)
const monitorWorkspaceId = computed(() => localStorage.getItem(ELocalStorageKey.WorkspaceId) || '')
function onMissionMonitorChange (updated: PlannedWaylineRecord) {
  const idx = plannedWaylinesData.data.findIndex(r => r.plannedWaylineId === updated.plannedWaylineId)
  if (idx >= 0) plannedWaylinesData.data.splice(idx, 1, updated)
  applyPlannedWaylineFlightPosition(updated)
}

interface AircraftSummary {
  sn: string
  callsign: string
  gatewaySn: string
  aircraftModelKey: string
  source: 'cloud-topology' | 'msdk-agent'
  lastSeen?: number
}

const onlineAircraftMap = reactive({} as Record<string, AircraftSummary>)
const msdkAircraftMap = reactive({} as Record<string, MsdkDeviceState>)
const onlineAircrafts = computed<AircraftSummary[]>(() => Object.values(onlineAircraftMap))
const executeTargetModal = reactive({
  visible: false,
  loading: false,
  record: null as PlannedWaylineRecord | null,
  targetSn: '',
})
const executeTargetOptions = computed<AircraftSummary[]>(() => {
  const targetMap = new Map<string, AircraftSummary>()
  onlineAircrafts.value.forEach(aircraft => {
    if (aircraft.sn) targetMap.set(aircraft.sn, aircraft)
  })
  Object.values(store.state.msdkDeviceState.devices || {}).forEach((device: any) => {
    if (!device?.aircraftSn || !device.online) return
    if (!targetMap.has(device.aircraftSn)) {
      targetMap.set(device.aircraftSn, {
        sn: device.aircraftSn,
        callsign: device.model || device.aircraftSn,
        gatewaySn: device.gatewaySn || device.aircraftSn,
        aircraftModelKey: normalizeAircraftModelKey(device.model) || DEFAULT_PLANNED_WAYLINE_MODEL,
        source: 'msdk-agent',
        lastSeen: device.updatedAt || Date.now(),
      })
    }
  })
  return Array.from(targetMap.values())
})
const plannedWaylinesLoading = ref(false)
const plannedWaylinesData = reactive({
  data: [] as PlannedWaylineRecord[]
})
const plannedWaylinesPagination: IPage = reactive({
  page: 1,
  total: -1,
  page_size: 10
})
const plannedWaylinesCanRefresh = ref(true)
const selectedPlannedWayline = ref<PlannedWaylineRecord | null>(null)
const plannedWaylineDetailVisible = ref(false)
const savePlannedWaylineModal = reactive({
  visible: false,
  saveAs: false,
  name: '',
  aircraftModelKey: '',
  defaultHeight: 80,
  maxSpeed: 10,
  waypointCount: 0,
})

let topoTimer: number | null = null

const AIRCRAFT_MODEL_KEY_MAP: Record<string, string> = {
  [DEVICE_MODEL_KEY.M30]: 'M30',
  [DEVICE_MODEL_KEY.M30T]: 'M30T',
  [DEVICE_MODEL_KEY.M3E]: 'M3E',
  [DEVICE_MODEL_KEY.M3T]: 'M3T',
  [DEVICE_MODEL_KEY.M300]: 'M300',
  [DEVICE_MODEL_KEY.M350]: 'M350',
  [DEVICE_MODEL_KEY.M3D]: 'M3D',
  [DEVICE_MODEL_KEY.M3TD]: 'M3TD',
  '0-99-0': 'M4E',
  '0-99-1': 'M4T',
  M30: 'M30',
  M30T: 'M30T',
  M3E: 'M3E',
  M3T: 'M3T',
  M300: 'M300',
  M350: 'M350',
  M3D: 'M3D',
  M3TD: 'M3TD',
  M4E: 'M4E',
  M4T: 'M4T',
}

const AIRCRAFT_MODEL_NAME_ORDER = ['M3TD', 'M30T', 'M4T', 'M3T', 'M350', 'M300', 'M30', 'M3E', 'M3D', 'M4E']
const PLANNED_WAYLINE_MODEL_OPTIONS = ['M4T', 'M4E', 'M30T', 'M30', 'M3T', 'M3E', 'M3TD', 'M3D', 'M350', 'M300']
const DEFAULT_PLANNED_WAYLINE_MODEL = 'M4T'

watch(
  () => selectedAircraftSn.value,
  sn => {
    // 用户选择 MSDK 飞机即设为跟踪目标（执行中的航线会在轮询里持续覆盖）。
    if (sn) setTrackedAircraft(sn)
    syncSelectedAircraftFlightPosition(sn)
  },
)

watch(
  () => {
    const sn = selectedAircraftSn.value
    const osd = sn ? store.state.deviceState.deviceInfo[sn] : null
    return osd ? `${sn}:${(osd as any).longitude}:${(osd as any).latitude}:${(osd as any).height}` : ''
  },
  () => syncSelectedAircraftFlightPosition(),
)

function normalizeAircraftModelKey (raw: any): string {
  if (raw === undefined || raw === null) return ''
  const text = String(raw).trim()
  if (!text) return ''
  const upper = text.toUpperCase().replace(/\s+/g, '')
  return AIRCRAFT_MODEL_KEY_MAP[text] || AIRCRAFT_MODEL_KEY_MAP[upper] || ''
}

function inferAircraftModelKey (child: any): string {
  const candidates = [
    child?.device_model_key,
    child?.device_model?.key,
    child?.device_model?.device,
    child?.device_model?.value,
    child?.device_model,
    child?.domain !== undefined && child?.type !== undefined && child?.sub_type !== undefined
      ? `${child.domain}-${child.type}-${child.sub_type}`
      : '',
    child?.domain !== undefined && child?.type !== undefined && child?.subType !== undefined
      ? `${child.domain}-${child.type}-${child.subType}`
      : '',
  ]

  for (const candidate of candidates) {
    const aircraftModelKey = normalizeAircraftModelKey(candidate)
    if (aircraftModelKey) return aircraftModelKey
  }

  const label = `${child?.device_name || ''} ${child?.nickname || ''} ${child?.callsign || ''}`.toUpperCase().replace(/\s+/g, '')
  return AIRCRAFT_MODEL_NAME_ORDER.find(name => label.includes(name)) || ''
}

function normalizePlannedWaylineModel (model: string): string {
  const normalized = normalizeAircraftModelKey(model)
  return PLANNED_WAYLINE_MODEL_OPTIONS.includes(normalized) ? normalized : DEFAULT_PLANNED_WAYLINE_MODEL
}

function pickDeviceField (source: any, camelKey: string, snakeKey: string) {
  if (!source || typeof source !== 'object') return undefined
  return source[camelKey] !== undefined ? source[camelKey] : source[snakeKey]
}

function normalizeDeviceDomain (domain: any): string {
  if (domain === null || domain === undefined) return ''
  if (typeof domain === 'object') {
    return normalizeDeviceDomain(domain.domain ?? domain.value ?? domain.name)
  }
  return String(domain).toLowerCase()
}

function isGatewayDomain (domain: any): boolean {
  const value = normalizeDeviceDomain(domain)
  return value === String(EDeviceTypeName.Gateway) ||
    value === 'gateway' ||
    value === 'remoter_control' ||
    value === 'remote_control'
}

function isDeviceOnline (device: any): boolean {
  return Boolean(pickDeviceField(device, 'status', 'status') ?? pickDeviceField(device, 'online', 'online') ?? true)
}

function upsertOnlineAircraft (seen: Set<string>, summary: AircraftSummary) {
  if (!summary.sn) return
  seen.add(summary.sn)
  const existing = onlineAircraftMap[summary.sn]
  if (existing?.source === 'msdk-agent' && summary.source !== 'msdk-agent') return
  onlineAircraftMap[summary.sn] = summary
}

function syncManagedTopoAircrafts (devices: any[], seen: Set<string>) {
  devices.forEach((gateway: any) => {
    const children = pickDeviceField(gateway, 'children', 'children')
    const childList = Array.isArray(children) ? children : (children ? [children] : [])
    if (!isGatewayDomain(pickDeviceField(gateway, 'domain', 'domain')) && childList.length === 0) return
    const gatewayOnline = isDeviceOnline(gateway)
    childList.forEach((child: any) => {
      const childSn = pickDeviceField(child, 'deviceSn', 'device_sn')
      if (!childSn) return
      if (!gatewayOnline && !isDeviceOnline(child)) return
      upsertOnlineAircraft(seen, {
        sn: childSn,
        callsign: pickDeviceField(child, 'nickname', 'nickname') ||
          pickDeviceField(child, 'deviceName', 'device_name') ||
          childSn,
        gatewaySn: pickDeviceField(gateway, 'deviceSn', 'device_sn') || pickDeviceField(child, 'parentSn', 'parent_sn') || '',
        aircraftModelKey: inferAircraftModelKey(child) || DEFAULT_PLANNED_WAYLINE_MODEL,
        source: 'cloud-topology',
        lastSeen: Date.now(),
      })
    })
  })
}

function syncMsdkOnlineAircrafts (devices: MsdkDeviceState[], seen: Set<string>) {
  store.commit('SET_MSDK_DEVICE_STATE', devices)
  Object.keys(msdkAircraftMap).forEach(sn => {
    delete msdkAircraftMap[sn]
  })
  devices.forEach((device) => {
    if (!device.aircraftSn || !device.online) return
    msdkAircraftMap[device.aircraftSn] = device
    upsertOnlineAircraft(seen, {
      sn: device.aircraftSn,
      callsign: device.model || device.aircraftSn,
      gatewaySn: device.gatewaySn || device.aircraftSn,
      aircraftModelKey: normalizeAircraftModelKey(device.model) || DEFAULT_PLANNED_WAYLINE_MODEL,
      source: 'msdk-agent',
      lastSeen: device.updatedAt || Date.now(),
    })
    if ((selectedAircraftSn.value === device.aircraftSn || planningState.aircraftSn === device.aircraftSn) &&
      device.longitude && device.latitude) {
      setFlightPositionFromWgs(device.aircraftSn, device.longitude, device.latitude, {
        height: device.height,
        updatedAt: device.updatedAt || Date.now(),
      })
    }
  })
}

function syncSelectedAircraftFlightPosition (sn = selectedAircraftSn.value) {
  if (!sn) return
  const msdkDevice = msdkAircraftMap[sn]
  if (msdkDevice?.longitude && msdkDevice?.latitude) {
    setFlightPositionFromWgs(sn, msdkDevice.longitude, msdkDevice.latitude, {
      height: msdkDevice.height,
      updatedAt: msdkDevice.updatedAt || Date.now(),
    })
    return
  }
  const osd = store.state.deviceState.deviceInfo[sn]
  if (!osd) return
  setFlightPositionFromWgs(sn, (osd as any).longitude, (osd as any).latitude, {
    height: (osd as any).height,
    updatedAt: Date.now(),
  })
}

function getRecordAircraftSn (record: PlannedWaylineRecord | null | undefined) {
  return record?.droneSn || record?.aircraftSn || ''
}

function getSingleOnlineMsdkAircraft () {
  const msdkSnList = Object.keys(msdkAircraftMap)
  return msdkSnList.length === 1 ? msdkSnList[0] : ''
}

function applyPrepareTargetSelection (sn: string) {
  if (!sn) return
  const summary = onlineAircraftMap[sn]
  if (!summary) return
  selectedAircraftSn.value = summary.sn
  planningSetTarget(summary.gatewaySn, summary.sn)
  syncSelectedAircraftFlightPosition(summary.sn)
}

function getMsdkAircraftSummary (sn: string): AircraftSummary | null {
  const device = msdkAircraftMap[sn]
  if (!device?.aircraftSn || !device.online) return null
  return {
    sn: device.aircraftSn,
    callsign: device.model || device.aircraftSn,
    gatewaySn: device.gatewaySn || device.aircraftSn,
    aircraftModelKey: normalizeAircraftModelKey(device.model) || DEFAULT_PLANNED_WAYLINE_MODEL,
    source: 'msdk-agent',
    lastSeen: device.updatedAt || Date.now(),
  }
}

function resolvePlanningExecutionTarget (): AircraftSummary | null {
  const candidateSn = selectedAircraftSn.value || planningState.aircraftSn || getSingleOnlineMsdkAircraft()
  if (!candidateSn) return null
  return onlineAircraftMap[candidateSn] || getMsdkAircraftSummary(candidateSn)
}

function isPlannedWaylineLive (record: PlannedWaylineRecord | null | undefined): boolean {
  if (!record) return false
  const status = normalizePlannedWaylineStatus(record)
  return status === PlannedWaylineStatus.EXECUTING || status === PlannedWaylineStatus.PUBLISHING
}

function applyPlannedWaylineFlightPosition (record: PlannedWaylineRecord | null | undefined) {
  const recordAircraftSn = getRecordAircraftSn(record)
  if (!recordAircraftSn) return
  // 执行中的航线 → 该飞机认领地图跟踪，挡掉其它(停地)飞机的位置写入。
  if (isPlannedWaylineLive(record)) {
    setTrackedAircraft(recordAircraftSn)
  }
  const onlineMsdkSnList = Object.keys(msdkAircraftMap)
  const msdkDevice = msdkAircraftMap[recordAircraftSn]
  if (msdkDevice?.longitude && msdkDevice?.latitude) {
    setFlightPositionFromWgs(recordAircraftSn, msdkDevice.longitude, msdkDevice.latitude, {
      height: msdkDevice.height,
      updatedAt: msdkDevice.updatedAt || Date.now(),
      currentWaypointIndex: record?.currentWaypointIndex,
      totalWaypoints: record?.totalWaypoints,
    })
    return
  }
  if (onlineMsdkSnList.length > 0) {
    return
  }
  setFlightPositionFromRecord(record)
}

function finiteSaveNumber (value: unknown): number | null {
  if (value === null || value === undefined || value === '') return null
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? numberValue : null
}

function positiveSaveNumber (value: unknown, fallback: number): number {
  const numberValue = finiteSaveNumber(value)
  return numberValue !== null && numberValue > 0 ? numberValue : fallback
}

function buildPagePlannedWaypointBody (wp: any, idx: number, defaultHeight: number): PlannedWaypoint {
  let gcjLng = finiteSaveNumber(wp?.gcjLng ?? wp?.lng ?? wp?.longitude)
  let gcjLat = finiteSaveNumber(wp?.gcjLat ?? wp?.lat ?? wp?.latitude)
  let wgsLng = finiteSaveNumber(wp?.wgsLng)
  let wgsLat = finiteSaveNumber(wp?.wgsLat)

  if ((wgsLng === null || wgsLat === null) && gcjLng !== null && gcjLat !== null) {
    const [convertedWgsLng, convertedWgsLat] = gcj02towgs84(gcjLng, gcjLat) as [number, number]
    wgsLng = finiteSaveNumber(convertedWgsLng)
    wgsLat = finiteSaveNumber(convertedWgsLat)
  }
  if ((gcjLng === null || gcjLat === null) && wgsLng !== null && wgsLat !== null) {
    const [convertedGcjLng, convertedGcjLat] = wgs84togcj02(wgsLng, wgsLat) as [number, number]
    gcjLng = finiteSaveNumber(convertedGcjLng)
    gcjLat = finiteSaveNumber(convertedGcjLat)
  }
  if (gcjLng === null || gcjLat === null || wgsLng === null || wgsLat === null) {
    throw new Error(`waypoint ${idx + 1} coordinates required`)
  }

  return {
    order: idx + 1,
    gcjLng,
    gcjLat,
    wgsLng,
    wgsLat,
    height: positiveSaveNumber(wp?.height, defaultHeight),
    // L1 per-航点定制字段透传 (null 时由后端默认值兜底)
    speed: wp?.speed ?? undefined,
    gimbalPitch: wp?.gimbalPitch ?? undefined,
    gimbalYaw: wp?.gimbalYaw ?? undefined,
    headingMode: wp?.headingMode ?? undefined,
    headingAngle: wp?.headingAngle ?? undefined,
    poiLng: wp?.poiLng ?? undefined,
    poiLat: wp?.poiLat ?? undefined,
    poiAlt: wp?.poiAlt ?? undefined,
    turnMode: wp?.turnMode ?? undefined,
    turnDamping: wp?.turnDamping ?? undefined,
    actions: wp?.actions && wp.actions.length > 0 ? wp.actions : undefined,
  }
}

function buildPagePlannedWaylineBody (name: string, aircraftModelKey: string): CreatePlannedWaylineBody {
  const defaultHeight = positiveSaveNumber(savePlannedWaylineModal.defaultHeight, 30)
  const maxSpeed = positiveSaveNumber(savePlannedWaylineModal.maxSpeed, 5)
  return {
    name,
    aircraftModelKey: normalizePlannedWaylineModel(aircraftModelKey),
    gatewaySn: planningState.gatewaySn || '',
    aircraftSn: planningState.aircraftSn || '',
    defaultHeight,
    maxSpeed,
    // L1 全局 mission 配置 (undefined 时由后端 entity 默认值兜底)
    finishAction: planningState.finishAction ?? undefined,
    exitOnRcLost: planningState.exitOnRcLost ?? undefined,
    rcLostAction: planningState.rcLostAction ?? undefined,
    takeoffSecurityHeight: planningState.takeoffSecurityHeight ?? undefined,
    globalTransitionalSpeed: planningState.globalTransitionalSpeed ?? undefined,
    rthAltitude: planningState.rthAltitude ?? undefined,
    waypoints: planningState.waypoints.map((wp, idx) => buildPagePlannedWaypointBody(wp, idx, defaultHeight)),
  }
}

async function refreshOnlineAircrafts () {
  const workspaceIdForPlanning = localStorage.getItem(ELocalStorageKey.WorkspaceId) || ''
  if (!workspaceIdForPlanning) return
  const seen = new Set<string>()
  try {
    const res = await getDeviceTopo(workspaceIdForPlanning)
    if (res.code === 0 && Array.isArray(res.data)) {
      syncManagedTopoAircrafts(res.data, seen)
    }
  } catch (e) {
    // silent — MSDK state below and the next timer tick can still populate candidates.
  }
  try {
    const msdkRes = await listMsdkDevices()
    if (msdkRes.code === 0 && Array.isArray(msdkRes.data)) {
      syncMsdkOnlineAircrafts(msdkRes.data, seen)
    }
  } catch (e) {
    // silent — topo will retry.
  }
  // Drop entries that have gone offline.
  Object.keys(onlineAircraftMap).forEach(sn => {
    if (!seen.has(sn)) delete onlineAircraftMap[sn]
  })
  // Keep the selection valid.
  if (selectedAircraftSn.value && !onlineAircraftMap[selectedAircraftSn.value]) {
    if (planningState.active && planningState.aircraftSn === selectedAircraftSn.value) {
      planningStop()
    }
    if (!planningState.executing && planningState.aircraftSn === selectedAircraftSn.value) {
      planningSetTarget('', '')
    }
    selectedAircraftSn.value = ''
  }
  const aircrafts = onlineAircrafts.value
  if (!selectedAircraftSn.value && !planningState.executing && !planningState.active && aircrafts.length === 1) {
    onSelectAircraft(aircrafts[0].sn)
  }
}

function onSelectAircraft (sn: string) {
  selectedAircraftSn.value = sn
  const summary = onlineAircraftMap[sn]
  if (summary) {
    planningSetTarget(summary.gatewaySn, summary.sn)
  }
  syncSelectedAircraftFlightPosition(sn)
}

function onStartPlacing () {
  const summary = onlineAircraftMap[selectedAircraftSn.value]
  if (summary) {
    planningStart(summary.gatewaySn, summary.sn)
  } else {
    planningStart('', '')
  }
}

function onStopPlacing () {
  planningStop()
}

async function onStartExecution () {
  await refreshOnlineAircrafts()
  const summary = resolvePlanningExecutionTarget()
  if (!summary) {
    message.warning('执行航线前需要选择在线飞行器。')
    return
  }
  selectedAircraftSn.value = summary.sn
  planningSetTarget(summary.gatewaySn, summary.sn)
  syncSelectedAircraftFlightPosition(summary.sn)
  await planningExecute()
}

async function onStopExecution () {
  await planningStopExec()
}

function validatePlannedWaylineSave (): AircraftSummary {
  let summary = onlineAircraftMap[selectedAircraftSn.value]
  if (!summary && planningState.gatewaySn && planningState.aircraftSn && (planningState as any).aircraftModelKey) {
    summary = {
      sn: planningState.aircraftSn,
      callsign: planningState.aircraftSn,
      gatewaySn: planningState.gatewaySn,
      aircraftModelKey: (planningState as any).aircraftModelKey,
    }
  }
  if (planningState.waypoints.length === 0) {
    message.warning('请至少添加一个航点。')
    throw new Error('waypoints required')
  }
  const fallbackModel = normalizePlannedWaylineModel(savePlannedWaylineModal.aircraftModelKey || (planningState as any).aircraftModelKey || DEFAULT_PLANNED_WAYLINE_MODEL)
  const target = summary || {
    sn: planningState.aircraftSn || '',
    callsign: planningState.aircraftSn || '',
    gatewaySn: planningState.gatewaySn || '',
    aircraftModelKey: fallbackModel,
  }
  target.aircraftModelKey = normalizePlannedWaylineModel(target.aircraftModelKey || fallbackModel)
  if (!target.aircraftModelKey) {
    message.warning('请选择机型后再保存规划航线。')
    throw new Error('aircraft model required')
  }
  planningSetTarget(target.gatewaySn, target.sn)
  ;(planningState as any).aircraftModelKey = target.aircraftModelKey
  return target
}

function onSavePlannedWayline (saveAs: boolean) {
  openSavePlannedWaylineModal(saveAs)
}

function openSavePlannedWaylineModal (saveAs: boolean) {
  let summary: AircraftSummary
  try {
    summary = validatePlannedWaylineSave()
  } catch (e) {
    return
  }

  const editingId = (planningState as any).editingPlannedWaylineId
  const editingRecord = plannedWaylinesData.data.find(record => record.plannedWaylineId === editingId)
  if (!saveAs && editingId && !editingRecord) {
    message.warning('当前编辑的规划航线不在列表中，请刷新后重试或使用另存为。')
    return
  }
  if (!saveAs && editingRecord && !canOverwritePlannedWayline(editingRecord)) {
    message.warning('当前状态的规划航线不能直接覆盖，请使用另存为。')
    return
  }
  const editingName = editingRecord?.name || ''
  const defaultName = saveAs
    ? `${sanitizeDjiWaylineName(editingName || '规划航线')} 副本`
    : sanitizeDjiWaylineName(editingName || `规划航线 ${formatSafePlannedWaylineTimestamp(new Date())}`)
  savePlannedWaylineModal.visible = true
  savePlannedWaylineModal.saveAs = saveAs
  savePlannedWaylineModal.name = defaultName
  savePlannedWaylineModal.aircraftModelKey = normalizePlannedWaylineModel(summary.aircraftModelKey)
  savePlannedWaylineModal.defaultHeight = planningState.defaultHeight
  savePlannedWaylineModal.maxSpeed = planningState.maxSpeed
  savePlannedWaylineModal.waypointCount = planningState.waypoints.length
}

async function confirmSavePlannedWayline () {
  let summary: AircraftSummary
  try {
    summary = validatePlannedWaylineSave()
  } catch (e) {
    return
  }
  const name = sanitizeDjiWaylineName(savePlannedWaylineModal.name)
  if (!name) {
    message.warning('请输入规划航线名称。')
    return
  }

  const editingId = (planningState as any).editingPlannedWaylineId
  planningState.defaultHeight = Number(savePlannedWaylineModal.defaultHeight)
  planningState.maxSpeed = Number(savePlannedWaylineModal.maxSpeed)
  const aircraftModelKey = normalizePlannedWaylineModel(savePlannedWaylineModal.aircraftModelKey || summary.aircraftModelKey)
  let body
  try {
    body = buildPagePlannedWaylineBody(name, aircraftModelKey)
    try {
      buildPlannedWaylineBody(name, aircraftModelKey)
    } catch (e) {
      console.warn('planned wayline hook payload normalization failed; page payload was rebuilt from current waypoints', e)
    }
  } catch (e) {
    message.warning('航点坐标异常，请清空后重新布点。')
    return
  }
  loading.value = true
  try {
    const res = !savePlannedWaylineModal.saveAs && editingId
      ? await updatePlannedWayline(workspaceId, editingId, body)
      : await createPlannedWayline(workspaceId, body)
    if (res.code !== 0) return
    message.success(savePlannedWaylineModal.saveAs || !editingId ? '规划航线已保存' : '规划航线已更新')
    savePlannedWaylineModal.visible = false
    resetPlanningDraft()
    selectedAircraftSn.value = ''
    if (res.data?.plannedWaylineId) {
      previewPlannedWayline(res.data)
    }
    await refreshPlannedWaylines(true)
  } finally {
    loading.value = false
  }
}

async function refreshPlannedWaylines (reset = false) {
  if (plannedWaylinesLoading.value) return
  if (reset) {
    plannedWaylinesPagination.page = 1
    plannedWaylinesPagination.total = -1
    plannedWaylinesData.data = []
    plannedWaylinesCanRefresh.value = true
  }
  if (!plannedWaylinesCanRefresh.value) return
  plannedWaylinesLoading.value = true
  try {
    const res = await getPlannedWaylines(workspaceId, {
      page: plannedWaylinesPagination.page,
      total: plannedWaylinesPagination.total,
      page_size: plannedWaylinesPagination.page_size
    })
    if (res.code !== 0) return
    const list = res.data?.list || []
    plannedWaylinesData.data = reset ? list : [...plannedWaylinesData.data, ...list]
    const activeRecord = plannedWaylinesData.data.find(record => {
      const status = String(record.taskStatus || record.status || '').toLowerCase()
      return ['executing', 'paused', 'broken'].includes(status) &&
        (record.aircraftGcjLng != null || record.aircraftLng != null)
    })
    if (activeRecord) applyPlannedWaylineFlightPosition(activeRecord)
    plannedWaylinesPagination.total = res.data?.pagination?.total ?? list.length
    plannedWaylinesPagination.page = res.data?.pagination?.page ?? plannedWaylinesPagination.page
    plannedWaylinesCanRefresh.value = Math.ceil(plannedWaylinesPagination.total / plannedWaylinesPagination.page_size) > plannedWaylinesPagination.page
  } finally {
    plannedWaylinesLoading.value = false
  }
}

function onPlannedWaylinesScroll (e: any) {
  const element = e.srcElement
  if (element.scrollTop + element.clientHeight >= element.scrollHeight - 5 && plannedWaylinesCanRefresh.value && !plannedWaylinesLoading.value) {
    plannedWaylinesPagination.page++
    refreshPlannedWaylines()
  }
}

function onPreviewPlannedWayline (record: PlannedWaylineRecord) {
  resetPlanningDraft()
  const recordAircraftSn = getRecordAircraftSn(record)
  if (recordAircraftSn && onlineAircraftMap[recordAircraftSn]) {
    applyPrepareTargetSelection(recordAircraftSn)
  } else {
    applyPrepareTargetSelection(getSingleOnlineMsdkAircraft())
  }
  previewPlannedWayline(record)
}

function onEditPlannedWayline (record: PlannedWaylineRecord) {
  loadPlannedWayline(record)
  selectedAircraftSn.value = record.aircraftSn
  const summary = onlineAircraftMap[record.aircraftSn]
  if (summary) {
    planningSetTarget(summary.gatewaySn, summary.sn)
  }
}

function onSavePlannedWaylineAs (record: PlannedWaylineRecord | null) {
  if (!record) return
  onEditPlannedWayline(record)
  openSavePlannedWaylineModal(true)
}

async function runPlannedWaylineAction (
  record: PlannedWaylineRecord,
  action: () => Promise<any>,
  successText: string,
) {
  loading.value = true
  try {
    const res = await action()
    if (res.code !== 0) return
    message.success(successText)
    await refreshPlannedWaylines(true)
    if (selectedPlannedWayline.value?.plannedWaylineId === record.plannedWaylineId) {
      await showPlannedWaylineDetail(record)
    }
  } finally {
    loading.value = false
  }
}

async function onGeneratePlannedWaylineFile (record: PlannedWaylineRecord) {
  Modal.confirm({
    title: '生成航线文件',
    content: `确认生成“${record.name}”的 KMZ 航线文件？生成航线文件后不可直接覆盖，只能另存为新规划航线。`,
    okText: '生成',
    cancelText: '取消',
    async onOk () {
      await runPlannedWaylineAction(
        record,
        () => generatePlannedWaylineFile(workspaceId, record.plannedWaylineId),
        '航线文件已生成')
      refreshWaylineFiles(true)
    }
  })
}

function resolvePrepareTargetDroneSn (record: PlannedWaylineRecord) {
  const selectedSummary = selectedAircraftSn.value ? onlineAircraftMap[selectedAircraftSn.value] : null
  if (selectedSummary?.sn) return selectedSummary.sn
  const recordAircraftSn = getRecordAircraftSn(record)
  if (recordAircraftSn && onlineAircraftMap[recordAircraftSn]) {
    applyPrepareTargetSelection(recordAircraftSn)
    return recordAircraftSn
  }
  const onlyMsdkAircraftSn = getSingleOnlineMsdkAircraft()
  if (onlyMsdkAircraftSn) {
    applyPrepareTargetSelection(onlyMsdkAircraftSn)
    return onlyMsdkAircraftSn
  }
  if (onlineAircrafts.value.length === 1) {
    const onlyAircraft = onlineAircrafts.value[0]
    applyPrepareTargetSelection(onlyAircraft.sn)
    return onlyAircraft.sn
  }
  return ''
}

async function onPreparePlannedWaylineTask (record: PlannedWaylineRecord) {
  clearPlannedWaylineTaskReason(record)
  await refreshOnlineAircrafts()
  const targetDroneSn = resolvePrepareTargetDroneSn(record)
  if (!targetDroneSn) {
    message.warning('检测到多台或未检测到在线飞行器，请先在上方飞行器下拉框选择目标后再下发准备。')
    return
  }
  // Agent 路径 (M4T + RC,无机场) 不需要 dockSn,后端按 dockSn 是否非空自动路由。
  // 如果用户绑定了机场就走 dock 路径;否则走 agent 把 KMZ 推到 RC + MSDK。
  const body: any = {
    droneSn: targetDroneSn,
    executeTime: 0,
    taskType: 'IMMEDIATE',
  }
  // 仅当 record.dockSn 存在 (用户显式选了机场) 才透传,gatewaySn 是 RC 的 SN,不能当 dockSn 用
  if (record.dockSn) {
    body.dockSn = record.dockSn
  }
  await runPlannedWaylineAction(
    record,
    () => preparePlannedWaylineTask(workspaceId, record.plannedWaylineId, body),
    '航线任务已下发准备')
}

async function openExecuteTargetModal (record: PlannedWaylineRecord) {
  clearPlannedWaylineTaskReason(record)
  await refreshOnlineAircrafts()
  const targetDroneSn = selectedAircraftSn.value ||
    (record.droneSn && executeTargetOptions.value.some(item => item.sn === record.droneSn) ? record.droneSn : '') ||
    (record.aircraftSn && executeTargetOptions.value.some(item => item.sn === record.aircraftSn) ? record.aircraftSn : '') ||
    (executeTargetOptions.value.length === 1 ? executeTargetOptions.value[0].sn : '')
  executeTargetModal.record = record
  executeTargetModal.targetSn = targetDroneSn
  executeTargetModal.visible = true
}

function onExecutePlannedWaylineTask (record: PlannedWaylineRecord) {
  openExecuteTargetModal(record)
}

async function confirmExecuteTarget () {
  const record = executeTargetModal.record
  if (!record) return
  const targetDroneSn = executeTargetModal.targetSn
  if (!targetDroneSn) {
    message.warning('请选择在线飞行器后再执行。')
    return
  }
  const summary = executeTargetOptions.value.find(item => item.sn === targetDroneSn) || onlineAircraftMap[targetDroneSn]
  planningSetTarget(summary?.gatewaySn || record.gatewaySn || record.dockSn || targetDroneSn, targetDroneSn)
  selectedAircraftSn.value = targetDroneSn

  executeTargetModal.loading = true
  try {
    const prepareBody: any = {
      droneSn: targetDroneSn,
      executeTime: 0,
      taskType: 'IMMEDIATE',
    }
    if (record.dockSn) {
      prepareBody.dockSn = record.dockSn
    }
    const prepareRes = await preparePlannedWaylineTask(workspaceId, record.plannedWaylineId, prepareBody)
    if (prepareRes.code !== 0) return
    const executeRes = await executePlannedWaylineTask(workspaceId, record.plannedWaylineId, prepareBody)
    if (executeRes.code !== 0) return
    executeTargetModal.visible = false
    executeTargetModal.record = null
    executeTargetModal.targetSn = ''
    message.success('执行指令已发出，等待飞行器回传状态')
    await refreshPlannedWaylines(true)
    if (selectedPlannedWayline.value?.plannedWaylineId === record.plannedWaylineId) {
      await showPlannedWaylineDetail(executeRes.data || record)
    }
  } finally {
    executeTargetModal.loading = false
  }
}

async function onCancelPlannedWaylineTask (record: PlannedWaylineRecord) {
  await runPlannedWaylineAction(
    record,
    () => cancelPlannedWaylineTask(workspaceId, record.plannedWaylineId),
    '航线任务已取消')
}

async function onDeletePlannedWayline (record: PlannedWaylineRecord) {
  Modal.confirm({
    title: '删除规划航线',
    content: `确认删除“${record.name}”？状态：${formatPlannedWaylineStatus(record.status)}，航点：${record.waypoints?.length || 0} 个。`,
    okText: '删除',
    cancelText: '取消',
    okButtonProps: { danger: true },
    async onOk () {
      loading.value = true
      try {
        const res = await deletePlannedWayline(workspaceId, record.plannedWaylineId)
        if (res.code !== 0) return
        message.success('规划航线已删除')
        if ((planningState as any).editingPlannedWaylineId === record.plannedWaylineId) {
          resetPlanningDraft()
          selectedAircraftSn.value = ''
        }
        if (selectedPlannedWayline.value?.plannedWaylineId === record.plannedWaylineId) {
          plannedWaylineDetailVisible.value = false
          selectedPlannedWayline.value = null
        }
        await refreshPlannedWaylines(true)
      } finally {
        loading.value = false
      }
    }
  })
}

async function showPlannedWaylineDetail (record: PlannedWaylineRecord) {
  loading.value = true
  try {
    const res = await getPlannedWayline(workspaceId, record.plannedWaylineId)
    if (res.code !== 0 || !res.data) return
    selectedPlannedWayline.value = res.data
    plannedWaylineDetailVisible.value = true
  } finally {
    loading.value = false
  }
}

function clearPlannedWaylineTaskReason (record: PlannedWaylineRecord) {
  record.taskStatusReason = ''
  const cached = plannedWaylinesData.data.find(item => item.plannedWaylineId === record.plannedWaylineId)
  if (cached) cached.taskStatusReason = ''
  if (selectedPlannedWayline.value?.plannedWaylineId === record.plannedWaylineId) {
    selectedPlannedWayline.value.taskStatusReason = ''
  }
}

function getPlannedWaylineActions (record: PlannedWaylineRecord) {
  const status = normalizePlannedWaylineStatus(record)
  if (status === PlannedWaylineStatus.DRAFT) {
    return [{ key: 'generate', label: '生成航线文件', primary: true, danger: false, wrap: true, handler: onGeneratePlannedWaylineFile }]
  }
  if (status === PlannedWaylineStatus.FILE_GENERATED) {
    return [{ key: 'prepare', label: '下发准备', primary: true, danger: false, wrap: true, handler: onPreparePlannedWaylineTask }]
  }
  if (status === PlannedWaylineStatus.PREPARED) {
    return [
      { key: 'execute', label: '开始执行', primary: true, danger: false, handler: onExecutePlannedWaylineTask },
      { key: 'cancel', label: '取消任务', primary: false, danger: true, handler: onCancelPlannedWaylineTask },
    ]
  }
  if (status === PlannedWaylineStatus.PUBLISHING || status === PlannedWaylineStatus.EXECUTING) {
    return [{ key: 'cancel', label: '取消任务', primary: false, danger: true, handler: onCancelPlannedWaylineTask }]
  }
  if (status === PlannedWaylineStatus.FAILED || status === PlannedWaylineStatus.CANCELED) {
    return [{
      key: record.publishedWaylineId ? 'prepare' : 'generate',
      label: record.publishedWaylineId ? '下发准备' : '生成航线文件',
      primary: true,
      danger: false,
      wrap: true,
      handler: record.publishedWaylineId ? onPreparePlannedWaylineTask : onGeneratePlannedWaylineFile,
    }]
  }
  return []
}

const pagination :IPage = {
  page: 1,
  total: -1,
  page_size: 10
}

const waylinesData = reactive({
  data: [] as WaylineFile[]
})

const root = getRoot()
const workspaceId = localStorage.getItem(ELocalStorageKey.WorkspaceId)!
const deleteTip = ref(false)
const deleteWaylineId = ref<string>('')
const canRefresh = ref(true)
const importVisible = computed(() => route.name === ERouterName.WAYLINE)
const height = ref()

onMounted(() => {
  nextTick(() => {
    planningOverlayReady.value = true
  })
  const parent = document.getElementsByClassName('scrollbar').item(0)?.parentNode as HTMLDivElement
  height.value = document.body.clientHeight - parent.firstElementChild!.clientHeight
  getWaylines()
  if (showPlanningTools.value) {
    refreshPlannedWaylines(true)
  }

  const key = setInterval(() => {
    const data = document.getElementById('data')?.lastElementChild as HTMLDivElement
    if (pagination.total === 0 || Math.ceil(pagination.total / pagination.page_size) <= pagination.page || height.value <= data?.clientHeight + data?.offsetTop) {
      clearInterval(key)
      return
    }
    pagination.page++
    getWaylines()
  }, 1000)

  if (showPlanningTools.value) {
    // Populate online aircraft list for planning target selection.
    selectedAircraftSn.value = planningState.aircraftSn || ''
    refreshOnlineAircrafts()
    topoTimer = window.setInterval(refreshOnlineAircrafts, 5000)
  }
  // 每次进入航线页面：请求地图以飞机当前位置为中心（飞机位置就绪后由 GMap 居中一次）。
  requestAircraftRecenter()
})

onUnmounted(() => {
  if (topoTimer !== null) {
    window.clearInterval(topoTimer)
    topoTimer = null
  }
  if (showPlanningTools.value) {
    if (planningState.executing) {
      if (planningState.active) planningStop()
      return
    }
    resetPlanningDraft()
    selectedAircraftSn.value = ''
  }
})

function getWaylines () {
  refreshWaylineFiles()
}

function refreshWaylineFiles (reset = false) {
  if (reset) {
    pagination.total = 0
    pagination.page = 1
    waylinesData.data = []
  }
  if (!canRefresh.value) {
    return
  }
  canRefresh.value = false
  getWaylineFiles(workspaceId, {
    page: pagination.page,
    page_size: pagination.page_size,
    order_by: 'update_time desc'
  }).then(res => {
    if (res.code !== 0) {
      return
    }
    waylinesData.data = [...waylinesData.data, ...res.data.list]
    pagination.total = res.data.pagination.total
    pagination.page = res.data.pagination.page
  }).finally(() => {
    canRefresh.value = true
  })
}

function showWaylineTip (waylineId: string) {
  deleteWaylineId.value = waylineId
  deleteTip.value = true
}

function deleteWayline () {
  deleteWaylineFile(workspaceId, deleteWaylineId.value).then(res => {
    if (res.code === 0) {
      message.success('航线文件已删除')
    }
    deleteWaylineId.value = ''
    deleteTip.value = false
    refreshWaylineFiles(true)
  })
}

function downloadWayline (waylineId: string, fileName: string) {
  loading.value = true
  downloadWaylineFile(workspaceId, waylineId).then(res => {
    if (!res) {
      return
    }
    const data = new Blob([res], { type: 'application/zip' })
    downloadFile(data, fileName + '.kmz')
  }).finally(() => {
    loading.value = false
  })
}

function selectRoute (wayline: WaylineFile) {
  store.commit('SET_SELECT_WAYLINE_INFO', wayline)
}

function onScroll (e: any) {
  const element = e.srcElement
  if (element.scrollTop + element.clientHeight >= element.scrollHeight - 5 && Math.ceil(pagination.total / pagination.page_size) > pagination.page && canRefresh.value) {
    pagination.page++
    getWaylines()
  }
}

function beforeUpload (file: FileItem) {
  if (!file.name || !file.name.toLowerCase().endsWith('.kmz')) {
    message.error('文件格式错误，请选择 KMZ 文件。')
    return false
  }
  return true
}

const uploadFile = async (options?: { file?: FileItem; onSuccess?: (res: any) => void; onError?: (err: any) => void }) => {
  const file = options?.file
  if (!file) {
    message.error('请选择 KMZ 文件。')
    return
  }
  loading.value = true
  const fileData = new FormData()
  fileData.append('file', file, file.name)
  try {
    const res = await importPlannedWaylineKmzFile(workspaceId, fileData)
    if (res.code === 0) {
      message.success(`${file.name} 已导入为可执行航线`)
      canRefresh.value = true
      refreshPlannedWaylines(true)
      refreshWaylineFiles(true)
      options?.onSuccess?.(res)
    } else {
      options?.onError?.(new Error(res.message || 'KMZ 导入失败'))
    }
  } catch (error) {
    options?.onError?.(error)
  } finally {
    loading.value = false
  }
}

</script>

<style lang="scss" scoped>
.wayline-panel {
  background: #3c3c3c;
  margin-left: auto;
  margin-right: auto;
  margin-top: 10px;
  height: 90px;
  width: 95%;
  font-size: 13px;
  border-radius: 2px;
  cursor: pointer;
  .title {
    display: flex;
    flex-direction: row;
    align-items: center;
    height: 30px;
    font-weight: bold;
    margin: 0px 10px 0 10px;
  }
}
.uranus-scrollbar {
  overflow: auto;
  scrollbar-width: thin;
  scrollbar-color: #c5c8cc transparent;
}
:deep(.wayline-mode-collapse.ant-collapse > .ant-collapse-item > .ant-collapse-header) {
  color: #fff !important;
}
:deep(.wayline-mode-collapse.ant-collapse > .ant-collapse-item > .ant-collapse-header .ant-collapse-arrow) {
  color: #fff !important;
}
.project-wayline-wrapper :deep(.ant-btn) {
  font-size: 14px;
  font-family: inherit;
  font-weight: 400;
  line-height: 1.5715;
}
.project-wayline-wrapper :deep(.ant-btn-sm) {
  font-size: 14px;
}
.wayline-header-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  padding-right: 18px;
}
.wayline-header-icon-button {
  width: 32px;
  height: 32px;
  padding: 0;
  color: #fff;
  font-size: 22px;
}
.wayline-header-icon-button:hover,
.wayline-header-icon-button:focus {
  color: #fff;
  background: rgba(255, 255, 255, 0.08);
}

.planning-panel {
  margin: 10px auto 0;
  width: 95%;
  padding: 10px;
  background: #2b2b2b;
  border-radius: 4px;
  color: #d9d9d9;
  font-size: 12px;
}
.workflow-section-note {
  width: 95%;
  margin: 10px auto 0;
  padding: 9px 10px;
  display: flex;
  flex-direction: column;
  gap: 3px;
  border-left: 3px solid #1677ff;
  background: #262c33;
  color: #d9d9d9;
  font-size: 12px;
  line-height: 1.4;
}
.workflow-section-note strong {
  color: #fff;
  font-size: 13px;
}
.workflow-section-note span {
  color: hsla(0, 0%, 100%, 0.62);
}
.workflow-section-note--delivery {
  border-left-color: #19be6b;
  background: #22302b;
}
.planning-panel-title {
  font-size: 13px;
  font-weight: 700;
  color: #f5f5f5;
  margin-bottom: 8px;
  display: flex;
  align-items: center;
}
.planning-row {
  margin-bottom: 8px;
}
.planning-row:last-child {
  margin-bottom: 0;
}
.planning-two-col {
  display: flex;
  gap: 6px;
}
.planning-two-col > div {
  flex: 1;
}
.planning-label {
  display: block;
  color: #8c8c8c;
  margin-bottom: 4px;
}
.planning-empty {
  padding: 8px;
  border-radius: 3px;
  background: #353535;
  color: #8c8c8c;
  text-align: center;
}
.scrollbar {
  overflow-y: auto;
  overflow-x: hidden;
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
.planned-wayline-reason {
  margin-bottom: 5px;
  padding: 4px 6px;
  border-left: 2px solid #cf1322;
  background: rgba(207, 19, 34, 0.16);
  color: #ffccc7;
  font-size: 11px;
  line-height: 1.4;
  word-break: break-word;
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
.planned-wayline-detail-actions {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  grid-auto-flow: row dense;
  gap: 8px;
  flex-wrap: wrap;
}
.planned-wayline-detail-actions .ant-btn {
  min-width: 0;
  max-width: 100%;
  white-space: nowrap;
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
.planned-wayline-form {
  color: #262626;
}
.planned-wayline-detail {
  color: #262626;
}
.planned-wayline-detail-grid {
  display: grid;
  grid-template-columns: 86px minmax(0, 1fr) 86px minmax(0, 1fr);
  gap: 8px 12px;
  margin-bottom: 14px;
  font-size: 12px;
}
.planned-wayline-detail-grid span {
  color: #8c8c8c;
}
.planned-wayline-detail-grid strong {
  min-width: 0;
  font-weight: 500;
  overflow-wrap: anywhere;
}
.planned-wayline-detail-actions {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.planned-wayline-waypoint-table {
  max-height: 280px;
  overflow-y: auto;
  border: 1px solid #f0f0f0;
  border-radius: 4px;
}
.planned-wayline-waypoint-row {
  display: grid;
  grid-template-columns: 44px 1fr 1fr 72px;
  gap: 8px;
  padding: 7px 10px;
  border-bottom: 1px solid #f0f0f0;
  font-size: 12px;
}
.planned-wayline-waypoint-row:last-child {
  border-bottom: 0;
}
.planned-wayline-waypoint-row.head {
  position: sticky;
  top: 0;
  z-index: 1;
  background: #fafafa;
  color: #8c8c8c;
  font-weight: 700;
}

@media (max-width: 1180px) {
}
.planner-mode-tabs {
  padding: 0 10px;
  :deep(.ant-tabs-nav) {
    margin-bottom: 0;
  }
  :deep(.ant-tabs-tab) {
    color: #8c8c8c;
  }
  :deep(.ant-tabs-tab-active .ant-tabs-tab-btn) {
    color: #4da3ff;
  }
}
</style>
