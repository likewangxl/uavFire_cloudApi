<template>
  <div class="project-wayline-wrapper height-100">
    <a-spin :spinning="loading" :delay="300" tip="downloading" size="large">
    <div style="height: 50px; line-height: 50px; border-bottom: 1px solid #4f4f4f; font-weight: 450;">
      <a-row>
        <a-col :span="1"></a-col>
        <a-col :span="15">Flight Route Library</a-col>
        <a-col :span="8" v-if="importVisible" class="flex-row flex-justify-end flex-align-center">
          <a-upload
            name="file"
            :multiple="false"
            :before-upload="beforeUpload"
            :show-upload-list="false"
            :customRequest="uploadFile"
          >
            <a-button type="text" style="color: white;">
              <SelectOutlined />
            </a-button>
          </a-upload>
        </a-col>
      </a-row>
    </div>
    <div :style="{ height : height + 'px'}" class="scrollbar">
      <!-- Planned Wayline (click-to-fly), see WORK_RECORD.md §8 -->
      <div class="planning-panel">
        <div class="planning-panel-title">
          <span>Planned Wayline (Click-to-fly)</span>
          <a-tooltip title="Pick an aircraft, press 'Start Placing', then click on the map to add waypoints.">
            <QuestionCircleOutlined style="margin-left: 6px; color: #8c8c8c;" />
          </a-tooltip>
        </div>
        <div class="planning-row">
          <span class="planning-label">Aircraft</span>
          <a-select
            size="small"
            style="width: 100%;"
            :value="selectedAircraftSn"
            :disabled="planningState.executing || planningState.active"
            placeholder="Select an online aircraft"
            @change="onSelectAircraft">
            <a-select-option v-for="d in onlineAircrafts" :key="d.sn" :value="d.sn">
              {{ d.callsign || d.sn }}
            </a-select-option>
          </a-select>
        </div>
        <div class="planning-row planning-two-col">
          <div>
            <span class="planning-label">Height (m)</span>
            <a-input-number
              size="small"
              style="width: 100%;"
              :min="15"
              :step="1"
              :disabled="planningState.executing"
              v-model:value="planningState.defaultHeight" />
          </div>
          <div>
            <span class="planning-label">Max speed (m/s)</span>
            <a-input-number
              size="small"
              style="width: 100%;"
              :min="2"
              :max="15"
              :step="1"
              :disabled="planningState.executing"
              v-model:value="planningState.maxSpeed" />
          </div>
        </div>
        <div class="planning-row planning-actions">
          <a-button
            v-if="!planningState.active"
            size="small"
            type="primary"
            :disabled="planningState.executing || !selectedAircraftSn"
            @click="onStartPlacing">
            Start Placing
          </a-button>
          <a-button
            v-else
            size="small"
            @click="onStopPlacing">
            Stop Placing
          </a-button>
          <a-button
            size="small"
            :disabled="planningState.executing || planningState.waypoints.length === 0"
            @click="onClearWaypoints">
            Clear
          </a-button>
        </div>
        <div class="planning-row">
          <span class="planning-label">Waypoints ({{ planningState.waypoints.length }})</span>
          <div class="planning-empty" v-if="planningState.waypoints.length === 0">
            No waypoints. Start placing and click the map.
          </div>
          <div class="planning-waypoints" v-else>
            <div
              v-for="(wp, idx) in planningState.waypoints"
              :key="wp.id"
              class="planning-wp"
              :class="{ active: planningState.executing && planningState.currentIndex === idx }">
              <div class="planning-wp-head">
                <span class="planning-wp-index">#{{ idx + 1 }}</span>
                <span class="planning-wp-coord">
                  {{ wp.wgsLat.toFixed(6) }}, {{ wp.wgsLng.toFixed(6) }}
                </span>
              </div>
              <div class="planning-wp-tail">
                <a-input-number
                  size="small"
                  :min="15"
                  :step="1"
                  :disabled="planningState.executing"
                  :value="wp.height"
                  @change="(v) => onUpdateHeight(wp.id, v)"
                  style="width: 70px;" />
                <span class="planning-wp-unit">m</span>
                <a-button size="small" :disabled="planningState.executing || idx === 0" @click="onMove(wp.id, 'up')">↑</a-button>
                <a-button size="small" :disabled="planningState.executing || idx === planningState.waypoints.length - 1" @click="onMove(wp.id, 'down')">↓</a-button>
                <a-button size="small" danger :disabled="planningState.executing" @click="onRemove(wp.id)">✕</a-button>
              </div>
            </div>
          </div>
        </div>
        <div class="planning-row planning-actions">
          <a-button
            v-if="!planningState.executing"
            size="small"
            type="primary"
            :disabled="planningState.waypoints.length === 0 || !selectedAircraftSn"
            @click="onStartExecution">
            Execute
          </a-button>
          <a-button
            v-else
            size="small"
            danger
            @click="onStopExecution">
            Stop Execute
          </a-button>
        </div>
        <div class="planning-status" v-if="planningState.statusText">
          <span>{{ planningState.statusText }}</span>
        </div>
      </div>
      <div class="planning-section-gap"></div>
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
                        <span>Download</span>
                      </a-menu-item>
                      <a-menu-item @click="showWaylineTip(wayline.id)">
                        <span>Delete</span>
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
              <span class="mr10">Update at {{ new Date(wayline.update_time).toLocaleString() }}</span>
            </div>
          </div>
        </div>
      </div>
      <div v-else>
        <a-empty :image-style="{ height: '60px', marginTop: '60px' }" />
      </div>
      <a-modal v-model:visible="deleteTip" width="450px" :closable="false" :maskClosable="false" centered :okButtonProps="{ danger: true }" @ok="deleteWayline">
          <p class="pt10 pl20" style="height: 50px;">Wayline file is unrecoverable once deleted. Continue?</p>
          <template #title>
              <div class="flex-row flex-justify-center">
                  <span>Delete</span>
              </div>
          </template>
      </a-modal>
    </div>
    </a-spin>
  </div>
</template>

<script lang="ts" setup>
import { reactive } from '@vue/reactivity'
import { message } from 'ant-design-vue'
import { computed, onMounted, onUnmounted, onUpdated, ref } from 'vue'
import { deleteWaylineFile, downloadWaylineFile, getWaylineFiles, importKmzFile } from '/@/api/wayline'
import { ELocalStorageKey, ERouterName, EDeviceTypeName } from '/@/types'
import { EllipsisOutlined, RocketOutlined, CameraFilled, UserOutlined, SelectOutlined, QuestionCircleOutlined } from '@ant-design/icons-vue'
import { DEVICE_NAME } from '/@/types/device'
import { useMyStore } from '/@/store'
import { WaylineFile } from '/@/types/wayline'
import { downloadFile } from '/@/utils/common'
import { IPage } from '/@/api/http/type'
import { CURRENT_CONFIG } from '/@/api/http/config'
import { load } from '@amap/amap-jsapi-loader'
import { getRoot } from '/@/root'
import {
  getPlanningStateRaw,
  startPlanning as planningStart,
  stopPlanning as planningStop,
  clearWaypoints as planningClear,
  removeWaypoint as planningRemove,
  moveWaypoint as planningMove,
  updateWaypointHeight as planningUpdateHeight,
  startExecution as planningExecute,
  stopExecution as planningStopExec,
  setTargetAircraft as planningSetTarget,
} from '/@/hooks/use-wayline-planning'
import { getDeviceTopo } from '/@/api/manage'

const loading = ref(false)
const store = useMyStore()

// ---------- Planned wayline (click-to-fly) ----------
const planningState = getPlanningStateRaw()
const selectedAircraftSn = ref('')

interface AircraftSummary {
  sn: string
  callsign: string
  gatewaySn: string
}

const onlineAircraftMap = reactive({} as Record<string, AircraftSummary>)
const onlineAircrafts = computed<AircraftSummary[]>(() => Object.values(onlineAircraftMap))

let topoTimer: number | null = null

async function refreshOnlineAircrafts () {
  const workspaceIdForPlanning = localStorage.getItem(ELocalStorageKey.WorkspaceId) || ''
  if (!workspaceIdForPlanning) return
  try {
    const res = await getDeviceTopo(workspaceIdForPlanning)
    if (res.code !== 0) return
    const seen = new Set<string>()
    res.data.forEach((gateway: any) => {
      // Only consider Gateway domain (RC / RC2); Docks are handled separately elsewhere.
      if (gateway.domain !== EDeviceTypeName.Gateway) return
      if (!gateway.status) return
      const child = gateway.children
      if (!child?.device_sn) return
      seen.add(child.device_sn)
      onlineAircraftMap[child.device_sn] = {
        sn: child.device_sn,
        callsign: child.nickname || child.device_name || child.device_sn,
        gatewaySn: gateway.device_sn,
      }
    })
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
  } catch (e) {
    // silent — topo will retry.
  }
}

function onSelectAircraft (sn: string) {
  selectedAircraftSn.value = sn
  const summary = onlineAircraftMap[sn]
  if (summary) {
    planningSetTarget(summary.gatewaySn, summary.sn)
  }
}

function onStartPlacing () {
  const summary = onlineAircraftMap[selectedAircraftSn.value]
  if (!summary) {
    message.warning('Select an online aircraft first.')
    return
  }
  planningStart(summary.gatewaySn, summary.sn)
}

function onStopPlacing () {
  planningStop()
}

function onClearWaypoints () {
  planningClear()
}

function onRemove (id: string) {
  planningRemove(id)
}

function onMove (id: string, direction: 'up' | 'down') {
  planningMove(id, direction)
}

function onUpdateHeight (id: string, value: number | string | null) {
  const n = typeof value === 'number' ? value : Number(value)
  if (Number.isFinite(n)) planningUpdateHeight(id, n)
}

async function onStartExecution () {
  const summary = onlineAircraftMap[selectedAircraftSn.value]
  if (!summary) {
    message.warning('Select an online aircraft first.')
    return
  }
  planningSetTarget(summary.gatewaySn, summary.sn)
  await planningExecute()
}

async function onStopExecution () {
  await planningStopExec()
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
const importVisible = ref<boolean>(root.$router.currentRoute.value.name === ERouterName.WAYLINE)
const height = ref()

onMounted(() => {
  const parent = document.getElementsByClassName('scrollbar').item(0)?.parentNode as HTMLDivElement
  height.value = document.body.clientHeight - parent.firstElementChild!.clientHeight
  getWaylines()

  const key = setInterval(() => {
    const data = document.getElementById('data')?.lastElementChild as HTMLDivElement
    if (pagination.total === 0 || Math.ceil(pagination.total / pagination.page_size) <= pagination.page || height.value <= data?.clientHeight + data?.offsetTop) {
      clearInterval(key)
      return
    }
    pagination.page++
    getWaylines()
  }, 1000)

  // Populate online aircraft list for planning target selection.
  selectedAircraftSn.value = planningState.aircraftSn || ''
  refreshOnlineAircrafts()
  topoTimer = window.setInterval(refreshOnlineAircrafts, 5000)
})

onUnmounted(() => {
  if (topoTimer !== null) {
    window.clearInterval(topoTimer)
    topoTimer = null
  }
  // Leaving the page exits placing mode; any active execution keeps running
  // until the user explicitly stops it from tsa.vue or re-enters this page.
  if (planningState.active) planningStop()
})

function getWaylines () {
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
      message.success('Wayline file deleted')
    }
    deleteWaylineId.value = ''
    deleteTip.value = false
    pagination.total = 0
    pagination.page = 1
    waylinesData.data = []
    getWaylines()
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

interface FileItem {
  uid: string;
  name?: string;
  status?: string;
  response?: string;
  url?: string;
}

interface FileInfo {
  file: FileItem;
  fileList: FileItem[];
}
const fileList = ref<FileItem[]>([])

function beforeUpload (file: FileItem) {
  fileList.value = [file]
  loading.value = true
  return true
}
const uploadFile = async () => {
  fileList.value.forEach(async (file: FileItem) => {
    const fileData = new FormData()
    fileData.append('file', file, file.name)
    await importKmzFile(workspaceId, fileData).then((res) => {
      if (res.code === 0) {
        message.success(`${file.name} file uploaded successfully`)
        canRefresh.value = true
        pagination.total = 0
        pagination.page = 1
        waylinesData.data = []
        getWaylines()
      }
    }).finally(() => {
      loading.value = false
      fileList.value = []
    })
  })
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

.planning-panel {
  margin: 10px auto 0;
  width: 95%;
  padding: 10px;
  background: #2b2b2b;
  border-radius: 4px;
  color: #d9d9d9;
  font-size: 12px;
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
.planning-actions {
  display: flex;
  gap: 6px;
}
.planning-actions .ant-btn {
  flex: 1;
}
.planning-empty {
  padding: 8px;
  border-radius: 3px;
  background: #353535;
  color: #8c8c8c;
  text-align: center;
}
.planning-waypoints {
  max-height: 200px;
  overflow-y: auto;
}
.planning-wp {
  padding: 6px;
  margin-bottom: 4px;
  background: #353535;
  border-radius: 3px;
  border: 1px solid transparent;
}
.planning-wp.active {
  border-color: #faad14;
  background: #3a2f1a;
}
.planning-wp-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 4px;
}
.planning-wp-index {
  color: #faad14;
  font-weight: 700;
}
.planning-wp-coord {
  font-size: 11px;
  color: #bfbfbf;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.planning-wp-tail {
  display: flex;
  align-items: center;
  gap: 4px;
}
.planning-wp-unit {
  color: #8c8c8c;
  font-size: 11px;
}
.planning-status {
  margin-top: 4px;
  padding: 6px;
  border-radius: 3px;
  background: #1f1f1f;
  color: #faad14;
  font-size: 11px;
}
.planning-section-gap {
  height: 6px;
  border-bottom: 1px solid #4f4f4f;
  margin: 10px 0;
}
</style>
