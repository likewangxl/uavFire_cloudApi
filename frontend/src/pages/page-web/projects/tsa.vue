<template>
  <div class="project-tsa-wrapper ">
    <div>
      <a-row>
        <a-col :span="1"></a-col>
        <a-col :span="11">My Username</a-col>
        <a-col :span="11" align="right" style="font-weight: 700">{{ username }}</a-col>
        <a-col :span="1"></a-col>
      </a-row>
    </div>
    <div class="scrollbar" :style="{ height: scorllHeight + 'px'}">
      <a-collapse :bordered="false" expandIconPosition="right" accordion style="background: #232323;">
        <a-collapse-panel :key="EDeviceTypeName.Dock" header="Dock" style="border-bottom: 1px solid #4f4f4f;">
          <div v-if="onlineDocks.data.length === 0" style="height: 150px; color: white;">
            <a-empty :image="noData" :image-style="{ height: '60px' }" />
          </div>
          <div v-else class="fz12" style="color: white;">
            <div v-for="dock in onlineDocks.data" :key="dock.sn" style="background: #3c3c3c; height: 90px; width: 250px; margin-bottom: 10px;">
              <div style="border-radius: 2px; height: 100%; width: 100%;" class="flex-row flex-justify-between flex-align-center">
                <div style="float: left; padding: 0px 5px 8px 8px; width: 88%">
                  <div style="width: 80%; height: 30px; line-height: 30px; font-size: 16px;">
                    <a-tooltip :title="`${dock.gateway.callsign} - ${dock.callsign ?? 'No Drone'}`">
                      <div class="text-hidden" style="max-width: 200px;">{{ dock.gateway.callsign }} - {{ dock.callsign ?? 'No Drone' }}</div>
                    </a-tooltip>
                  </div>
                  <div class="mt5 flex-align-center flex-row flex-justify-between" style="background: #595959;">
                    <div class="flex-align-center flex-row">
                      <span class="ml5 mr5"><RobotOutlined /></span>
                      <div class="font-bold text-hidden" style="max-width: 80px;" :style="dockInfo[dock.gateway.sn] && dockInfo[dock.gateway.sn].basic_osd?.mode_code !== EDockModeCode.Disconnected ? 'color: #00ee8b' :  'color: red;'">
                        {{ dockInfo[dock.gateway.sn] ? EDockModeCode[dockInfo[dock.gateway.sn].basic_osd?.mode_code] : EDockModeCode[EDockModeCode.Disconnected] }}
                      </div>
                    </div>
                    <div class="mr5 flex-align-center flex-row" style="width: 85px; margin-right: 0; height: 18px;">
                      <div v-if="hmsInfo[dock.gateway.sn]" class="flex-align-center flex-row">
                          <div :class="hmsInfo[dock.gateway.sn][0].level === EHmsLevel.CAUTION ? 'caution-blink' :
                            hmsInfo[dock.gateway.sn][0].level === EHmsLevel.WARN ? 'warn-blink' : 'notice-blink'" style="width: 18px; height: 16px; text-align: center;">
                            <span :style="hmsInfo[dock.gateway.sn].length > 99 ? 'font-size: 11px' : 'font-size: 12px'">{{ hmsInfo[dock.gateway.sn].length }}</span>
                            <span class="fz10">{{ hmsInfo[dock.gateway.sn].length > 99 ? '+' : ''}}</span>
                          </div>
                        <a-popover trigger="click" placement="bottom" color="black" v-model:visible="hmsVisible[dock.gateway.sn]"
                          @visibleChange="readHms(hmsVisible[dock.gateway.sn], dock.gateway.sn)"
                          :overlayStyle="{width: '200px', height: '300px'}">
                          <div :class="hmsInfo[dock.gateway.sn][0].level === EHmsLevel.CAUTION ? 'caution' :
                            hmsInfo[dock.gateway.sn][0].level === EHmsLevel.WARN ? 'warn' : 'notice'" style="margin-left: 3px; width: 62px; height: 16px;">
                            <span class="word-loop">{{ hmsInfo[dock.gateway.sn][0].message_en }}</span>
                          </div>
                          <template #content>
                            <a-collapse style="background: black; height: 300px; overflow-y: auto;" :bordered="false" expand-icon-position="right" :accordion="true">
                              <a-collapse-panel v-for="hms in hmsInfo[dock.gateway.sn]" :key="hms.hms_id" :showArrow="false"
                                style=" margin: 0 auto 3px auto; border: 0; width: 140px; border-radius: 3px"
                                :class="hms.level === EHmsLevel.CAUTION ? 'caution' : hms.level === EHmsLevel.WARN ? 'warn' : 'notice'"
                                >
                                <template #header="{ isActive }">
                                  <div class="flex-row flex-align-center" style="width: 130px;">
                                    <div style="width: 110px;">
                                      <span class="word-loop">{{ hms.message_en }}</span>
                                    </div>
                                    <div style="width: 20px; height: 15px; font-size: 10px; z-index: 2 " class="flex-row flex-align-center flex-justify-center"
                                      :class="hms.level === EHmsLevel.CAUTION ? 'caution' : hms.level === EHmsLevel.WARN ? 'warn' : 'notice'"
                                    >
                                      <DoubleRightOutlined :rotate="isActive ? 90 : 0" />
                                    </div>
                                  </div>
                                </template>

                                <a-tooltip :title="hms.create_time">
                                  <div style="color: white;" class="text-hidden">{{ hms.create_time }}</div>
                                </a-tooltip>
                              </a-collapse-panel>
                            </a-collapse>
                          </template>
                        </a-popover>
                      </div>
                      <div v-else class="width-100" style="height: 90%; background: rgba(0, 0, 0, 0.35)"></div>
                    </div>
                  </div>
                  <div class="mt5 flex-align-center flex-row flex-justify-between" style="background: #595959;">
                    <div class="flex-row">
                      <span class="ml5 mr5"><RocketOutlined /></span>
                      <div class="font-bold text-hidden" style="max-width: 80px" :style="deviceInfo[dock.sn] && deviceInfo[dock.sn].mode_code !== EModeCode.Disconnected ? 'color: #00ee8b' :  'color: red;'">
                        {{ deviceInfo[dock.sn] ? EModeCode[deviceInfo[dock.sn].mode_code] : EModeCode[EModeCode.Disconnected] }}
                      </div>
                    </div>
                    <div class="mr5 flex-align-center flex-row" style="width: 85px; margin-right: 0; height: 18px;">
                      <div v-if="hmsInfo[dock.sn]" class="flex-align-center flex-row">
                        <div :class="hmsInfo[dock.sn][0].level === EHmsLevel.CAUTION ? 'caution-blink' :
                          hmsInfo[dock.sn][0].level === EHmsLevel.WARN ? 'warn-blink' : 'notice-blink'" style="width: 18px; height: 16px; text-align: center;">
                          <span :style="hmsInfo[dock.sn].length > 99 ? 'font-size: 11px' : 'font-size: 12px'">{{ hmsInfo[dock.sn].length }}</span>
                          <span class="fz10">{{ hmsInfo[dock.sn].length > 99 ? '+' : ''}}</span>
                        </div>
                        <a-popover trigger="click" placement="bottom" color="black" v-model:visible="hmsVisible[dock.sn]" @visibleChange="readHms(hmsVisible[dock.sn], dock.sn)"
                          :overlayStyle="{width: '200px', height: '300px'}">
                          <div :class="hmsInfo[dock.sn][0].level === EHmsLevel.CAUTION ? 'caution' :
                            hmsInfo[dock.sn][0].level === EHmsLevel.WARN ? 'warn' : 'notice'" style="margin-left: 3px; width: 62px; height: 16px;">
                            <span class="word-loop">{{ hmsInfo[dock.sn][0].message_en }}</span>
                          </div>
                          <template #content>
                            <a-collapse style="background: black; height: 300px; overflow-y: auto;" :bordered="false" expand-icon-position="right" :accordion="true">
                              <a-collapse-panel v-for="hms in hmsInfo[dock.sn]" :key="hms.hms_id" :showArrow="false"
                                style=" margin: 0 auto 3px auto; border: 0; width: 140px; border-radius: 3px"
                                :class="hms.level === EHmsLevel.CAUTION ? 'caution' : hms.level === EHmsLevel.WARN ? 'warn' : 'notice'"
                                >
                                <template #header="{ isActive }">
                                  <div class="flex-row flex-align-center" style="width: 130px;">
                                    <div style="width: 110px;">
                                      <span class="word-loop">{{ hms.message_en }}</span>
                                    </div>
                                    <div style="width: 20px; height: 15px; font-size: 10px; z-index: 2 " class="flex-row flex-align-center flex-justify-center"
                                      :class="hms.level === EHmsLevel.CAUTION ? 'caution' : hms.level === EHmsLevel.WARN ? 'warn' : 'notice'"
                                    >
                                      <DoubleRightOutlined :rotate="isActive ? 90 : 0" />
                                    </div>
                                  </div>
                                </template>

                                <a-tooltip :title="hms.create_time">
                                  <div style="color: white;" class="text-hidden">{{ hms.create_time }}</div>
                                </a-tooltip>
                              </a-collapse-panel>
                            </a-collapse>
                          </template>
                        </a-popover>
                      </div>
                      <div v-else class="width-100" style="height: 90%; background: rgba(0, 0, 0, 0.35)"></div>
                    </div>
                  </div>
                </div>
                <div style="float: right; background: #595959; height: 100%; width: 40px;" class="flex-row flex-justify-center flex-align-center">
                  <div class="fz16" @click="switchVisible($event, dock, true, dockInfo[dock.gateway.sn] && dockInfo[dock.gateway.sn].basic_osd?.mode_code !== EDockModeCode.Disconnected)">
                    <a v-if="osdVisible.gateway_sn === dock.gateway.sn && osdVisible.visible"><EyeOutlined /></a>
                    <a v-else><EyeInvisibleOutlined /></a>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </a-collapse-panel>
      </a-collapse>
      <a-collapse :bordered="false" expandIconPosition="right" accordion style="background: #232323;">
        <a-collapse-panel :key="EDeviceTypeName.Aircraft" header="Online Devices" style="border-bottom: 1px solid #4f4f4f;">
          <div v-if="onlineDevices.data.length === 0" style="height: 150px; color: white;">
            <a-empty :image="noData" :image-style="{ height: '60px' }" />
          </div>
          <div v-else class="fz12" style="color: white;">
            <div v-for="device in onlineDevices.data" :key="device.sn" class="aircraft-card">
              <div class="battery-slide" v-if="deviceInfo[device.sn]">
                <div style="background: #535759; width: 100%;"></div>
                <div class="capacity-percent" :style="{ width: deviceInfo[device.sn].battery.capacity_percent + '%'}"></div>
                <div class="return-home" :style="{ width: deviceInfo[device.sn].battery.return_home_power + '%'}"></div>
                <div class="landing" :style="{ width: deviceInfo[device.sn].battery.landing_power + '%'}"></div>
                <div class="battery" :style="{ left: deviceInfo[device.sn].battery.capacity_percent + '%' }"></div>
              </div>
              <div style="border-bottom: 1px solid #515151; border-radius: 2px; min-height: 50px; width: 100%;" class="flex-row flex-justify-between flex-align-center">
                <div style="float: left; padding: 5px 5px 8px 8px; width: 88%">
                  <div style="width: 100%; height: 100%;">
                    <a-tooltip>
                      <template #title>{{ device.model ? `${device.model} - ${device.callsign}` : 'No Drone'}}</template>
                      <span class="text-hidden" style="max-width: 200px; display: block; height: 20px;">{{ device.model ? `${device.model} - ${device.callsign}` : 'No Drone'}}</span>
                    </a-tooltip>
                  </div>
                  <div class="mt5" style="background: #595959;">
                    <span class="ml5 mr5"><RocketOutlined /></span>
                    <span class="font-bold" :style="deviceInfo[device.sn] && deviceInfo[device.sn].mode_code !== EModeCode.Disconnected ? 'color: #00ee8b' :  'color: red;'">
                      {{ deviceInfo[device.sn] ? EModeCode[deviceInfo[device.sn].mode_code] : EModeCode[EModeCode.Disconnected] }}
                    </span>
                  </div>
                </div>
                <div style="float: right; background: #595959; height: 50px; width: 40px;" class="flex-row flex-justify-center flex-align-center">
                  <div class="fz16" @click="switchVisible($event, device, false, deviceInfo[device.sn] && deviceInfo[device.sn].mode_code !== EModeCode.Disconnected)">
                    <a v-if="osdVisible.sn === device.sn && osdVisible.visible"><EyeOutlined /></a>
                    <a v-else><EyeInvisibleOutlined /></a>
                  </div>
                </div>
              </div>
              <div class="flex-row flex-justify-center flex-align-center" style="height: 40px; margin-bottom: 8px;">
                <div class="flex-row" style="height: 20px; background: #595959; width: 94%;" >
                  <span class="mr5"><a-image style="margin-left: 2px; margin-top: -2px; height: 20px; width: 20px;" :src="rc" /></span>
                  <a-tooltip>
                    <template #title>{{ device.gateway.model }} - {{ device.gateway.callsign }} </template>
                    <div class="text-hidden" style="max-width: 200px;">{{ device.gateway.model }} - {{ device.gateway.callsign }}</div>
                  </a-tooltip>
                </div>
              </div>
              <div class="aircraft-osd-panel" v-if="deviceInfo[device.sn]">
                <div class="aircraft-osd-title">Flight Status</div>
                <div class="aircraft-osd-grid">
                  <div class="aircraft-osd-item">
                    <span class="label">Battery</span>
                    <span class="value">{{ getBatteryPercent(device.sn) }}</span>
                  </div>
                  <div class="aircraft-osd-item">
                    <span class="label">Height</span>
                    <span class="value">{{ formatMetric(deviceInfo[device.sn].height, 'm') }}</span>
                  </div>
                  <div class="aircraft-osd-item">
                    <span class="label">Home</span>
                    <span class="value">{{ formatMetric(deviceInfo[device.sn].home_distance, 'm') }}</span>
                  </div>
                  <div class="aircraft-osd-item">
                    <span class="label">HS</span>
                    <span class="value">{{ formatMetric(deviceInfo[device.sn].horizontal_speed, 'm/s') }}</span>
                  </div>
                  <div class="aircraft-osd-item">
                    <span class="label">VS</span>
                    <span class="value">{{ formatMetric(deviceInfo[device.sn].vertical_speed, 'm/s') }}</span>
                  </div>
                  <div class="aircraft-osd-item">
                    <span class="label">Wind</span>
                    <span class="value">{{ formatMetric(deviceInfo[device.sn].wind_speed, 'm/s') }}</span>
                  </div>
                </div>
              </div>
              <div class="aircraft-osd-panel" v-else>
                <div class="aircraft-osd-title">Flight Status</div>
                <div class="aircraft-osd-empty">Waiting for aircraft OSD data.</div>
              </div>
              <div class="aircraft-action-panel">
                <div class="aircraft-osd-title">Flight Control</div>
                <div class="aircraft-action-tips">
                  {{ isCurrentRemoteGateway(device) ? 'Remote control connected. Stick presets are active.' : 'Enter remote control before sending flight commands.' }}
                </div>
                <div class="aircraft-action-row">
                  <a-button
                    size="small"
                    type="primary"
                    class="aircraft-action-btn"
                    :loading="actionLoading[device.gateway.sn] === 'connect'"
                    :disabled="isCurrentRemoteGateway(device)"
                    @click="connectRemoteControl(device)">
                    Enter Remote
                  </a-button>
                  <a-button
                    size="small"
                    class="aircraft-action-btn"
                    :loading="actionLoading[device.gateway.sn] === 'disconnect'"
                    :disabled="!isCurrentRemoteGateway(device)"
                    @click="disconnectRemoteControl()">
                    Exit Remote
                  </a-button>
                  <a-button
                    size="small"
                    danger
                    class="aircraft-action-btn"
                    :loading="actionLoading[device.gateway.sn] === 'stop'"
                    :disabled="!isCurrentRemoteGateway(device)"
                    @click="sendEmergencyStop(device)">
                    Stop
                  </a-button>
                </div>
                <div class="aircraft-action-row">
                  <a-button
                    size="small"
                    type="primary"
                    class="aircraft-action-btn"
                    :loading="actionLoading[device.gateway.sn] === 'takeoff'"
                    :disabled="!isCurrentRemoteGateway(device)"
                    @click="handleTakeoff(device)">
                    Official Takeoff
                  </a-button>
                  <a-button
                    size="small"
                    class="aircraft-action-btn"
                    :loading="actionLoading[device.gateway.sn] === 'land'"
                    :disabled="!isCurrentRemoteGateway(device)"
                    @click="handleLanding(device)">
                    Land
                  </a-button>
                </div>
                <div class="aircraft-action-row">
                  <a-button
                    size="small"
                    class="aircraft-action-btn"
                    :loading="actionLoading[device.gateway.sn] === 'up'"
                    :disabled="!isCurrentRemoteGateway(device)"
                    @click="handleAxisControl(device, 'up')">
                    Up
                  </a-button>
                  <a-button
                    size="small"
                    class="aircraft-action-btn"
                    :loading="actionLoading[device.gateway.sn] === 'hover'"
                    :disabled="!isCurrentRemoteGateway(device)"
                    @click="handleAxisControl(device, 'hover')">
                    Hover
                  </a-button>
                  <a-button
                    size="small"
                    class="aircraft-action-btn"
                    :loading="actionLoading[device.gateway.sn] === 'down'"
                    :disabled="!isCurrentRemoteGateway(device)"
                    @click="handleAxisControl(device, 'down')">
                    Down
                  </a-button>
                </div>
              </div>
            </div>
          </div>
        </a-collapse-panel>
      </a-collapse>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { EDeviceTypeName, ELocalStorageKey, EBizCode } from '/@/types'
import noData from '/@/assets/icons/no-data.png'
import rc from '/@/assets/icons/rc.png'
import { OnlineDevice, EModeCode, OSDVisible, EDockModeCode, DeviceOsd, DrcStateEnum } from '/@/types/device'
import { useMyStore } from '/@/store'
import { getDeviceTopo, getUnreadDeviceHms, updateDeviceHms } from '/@/api/manage'
import { RocketOutlined, EyeInvisibleOutlined, EyeOutlined, RobotOutlined, DoubleRightOutlined } from '@ant-design/icons-vue'
import { EHmsLevel } from '/@/types/enums'
import { message } from 'ant-design-vue'
import {
  ECommanderFlightMode,
  ECommanderModeLostAction,
  ERthMode,
  LostControlActionInCommandFLight,
  postFlightAuth,
  postTakeoffToPoint,
  WaylineLostControlActionInCommandFlight
} from '/@/api/drone-control/drone'
import { postDrc, postDrcEnter, postDrcExit } from '/@/api/drc'
import { UranusMqtt } from '/@/mqtt'
import { DRC_METHOD } from '/@/types/drc'
import { useMqtt, DeviceTopicInfo } from '/@/components/g-map/use-mqtt'
import { useManualControl, KeyCode } from '/@/components/g-map/use-manual-control'
import EventBus from '/@/event-bus/'
import { DrcStatusNotifyMessage } from '/@/types/drone-control'

const store = useMyStore()
const username = ref(localStorage.getItem(ELocalStorageKey.Username))
const workspaceId = ref(localStorage.getItem(ELocalStorageKey.WorkspaceId)!)
const osdVisible = computed(() => store.state.osdVisible)
const hmsVisible = new Map<string, boolean>()
const scorllHeight = ref()

const onlineDevices = reactive({
  data: [] as OnlineDevice[]
})

const onlineDocks = reactive({
  data: [] as OnlineDevice[]
})
const actionLoading = reactive({} as Record<string, string>)
const remoteControlState = reactive({
  gatewaySn: '',
  aircraftSn: '',
  connected: false,
  mqttClient: null as UranusMqtt | null
})
const remoteTopicInfo = reactive<DeviceTopicInfo>({
  sn: '',
  pubTopic: '',
  subTopic: ''
})

const deviceInfo = computed(() => store.state.deviceState.deviceInfo)
const dockInfo = computed(() => store.state.deviceState.dockInfo)
const isRemoteControlConnected = computed(() => remoteControlState.connected)
const mqttHooks = useMqtt(remoteTopicInfo)
const {
  handleKeyup,
  handleEmergencyStop: triggerEmergencyStop,
  resetControlState,
} = useManualControl(remoteTopicInfo, isRemoteControlConnected)
const hmsInfo = computed({
  get: () => store.state.hmsInfo,
  set: (val) => {
    return val
  }
})

// Handle server-side DRC disconnect notification (e.g., after autonomous mode
// starts or the dock forcibly exits DRC) and reset UI state automatically.
function onDroneControlWsEvent (payload: any) {
  if (!payload || payload.biz_code !== EBizCode.DrcStatusNotify) return
  const data = payload.data as DrcStatusNotifyMessage
  // result carries the drc_state: 0 = DISCONNECT
  if (data?.result === DrcStateEnum.DISCONNECT && remoteControlState.connected) {
    message.warning('Remote control disconnected by the aircraft.')
    destroyRemoteControlClient()
  }
}

onMounted(() => {
  getOnlineTopo()
  EventBus.on('droneControlWs', onDroneControlWsEvent)
  setTimeout(() => {
    watch(() => store.state.deviceStatusEvent,
      data => {
        getOnlineTopo()
        if (data.deviceOnline.sn) {
          getUnreadHms(data.deviceOnline.sn)
        }
      },
      {
        deep: true
      }
    )
    getOnlineDeviceHms()
  }, 3000)
  const element = document.getElementsByClassName('scrollbar').item(0) as HTMLDivElement
  const parent = element?.parentNode as HTMLDivElement
  scorllHeight.value = parent?.clientHeight - parent?.firstElementChild?.clientHeight
})

function getOnlineTopo () {
  getDeviceTopo(workspaceId.value).then((res) => {
    if (res.code !== 0) {
      return
    }
    onlineDevices.data = []
    onlineDocks.data = []
    res.data.forEach((gateway: any) => {
      const child = gateway.children
      const device: OnlineDevice = {
        model: child?.device_name,
        callsign: child?.nickname,
        sn: child?.device_sn,
        mode: EModeCode.Disconnected,
        gateway: {
          model: gateway?.device_name,
          callsign: gateway?.nickname,
          sn: gateway?.device_sn,
          domain: gateway?.domain
        },
        payload: []
      }
      child?.payloads_list.forEach((payload: any) => {
        device.payload.push({
          index: payload.index,
          model: payload.model,
          payload_name: payload.payload_name,
          payload_sn: payload.payload_sn,
          control_source: payload.control_source,
          payload_index: payload.payload_index
        })
      })
      if (EDeviceTypeName.Dock === gateway.domain) {
        hmsVisible.set(device.sn, false)
        hmsVisible.set(device.gateway.sn, false)
        onlineDocks.data.push(device)
      }
      if (gateway.status && EDeviceTypeName.Gateway === gateway.domain) {
        onlineDevices.data.push(device)
      }
    })
  })
}

function switchVisible (e: any, device: OnlineDevice, isDock: boolean, isClick: boolean) {
  if (!isClick) {
    e.target.style.cursor = 'not-allowed'
    return
  }
  if (device.sn === osdVisible.value.sn) {
    osdVisible.value.visible = !osdVisible.value.visible
  } else {
    osdVisible.value.sn = device.sn
    osdVisible.value.callsign = device.callsign
    osdVisible.value.model = device.model
    osdVisible.value.visible = true
    osdVisible.value.gateway_sn = device.gateway.sn
    osdVisible.value.is_dock = isDock
    osdVisible.value.gateway_callsign = device.gateway.callsign
    osdVisible.value.payloads = device.payload
  }
  store.commit('SET_OSD_VISIBLE_INFO', osdVisible)
}

function getUnreadHms (sn: string) {
  getUnreadDeviceHms(workspaceId.value, sn).then(res => {
    if (res.data.length !== 0) {
      hmsInfo.value[sn] = res.data
    }
  })
  console.info(hmsInfo.value)
}

function getOnlineDeviceHms () {
  const snList = Object.keys(dockInfo.value)
  if (snList.length === 0) {
    return
  }
  snList.forEach(sn => {
    getUnreadHms(sn)
  })
  const deviceSnList = Object.keys(deviceInfo.value)
  if (deviceSnList.length === 0) {
    return
  }
  deviceSnList.forEach(sn => {
    getUnreadHms(sn)
  })
}

function readHms (visiable: boolean, sn: string) {
  if (!visiable) {
    updateDeviceHms(workspaceId.value, sn).then(res => {
      if (res.code === 0) {
        delete hmsInfo.value[sn]
      }
    })
  }
}

function openLivestreamOthers () {
  store.commit('SET_LIVESTREAM_OTHERS_VISIBLE', true)
}

function openLivestreamAgora () {
  store.commit('SET_LIVESTREAM_AGORA_VISIBLE', true)
}

function getBatteryPercent (sn: string) {
  const osd = deviceInfo.value[sn]
  if (!osd?.battery) {
    return '--'
  }
  return `${osd.battery.capacity_percent}%`
}

function formatMetric (value: string | number | undefined, unit: string) {
  if (value === undefined || value === null || value === '') {
    return '--'
  }
  return `${value} ${unit}`
}

function createSeq () {
  return Date.now()
}

function sleep (ms: number) {
  return new Promise(resolve => window.setTimeout(resolve, ms))
}

const OFFICIAL_TAKEOFF_TARGET_HEIGHT = 30
const OFFICIAL_SECURITY_TAKEOFF_HEIGHT = 30
const OFFICIAL_TAKEOFF_MAX_SPEED = 5
const OFFICIAL_RTH_ALTITUDE = 100

function setActionLoading (gatewaySn: string, action?: string) {
  if (!action) {
    delete actionLoading[gatewaySn]
    return
  }
  actionLoading[gatewaySn] = action
}

function isGatewayControllable (device: OnlineDevice) {
  return Boolean(deviceInfo.value[device.sn] && deviceInfo.value[device.sn].mode_code !== EModeCode.Disconnected)
}

function isCurrentRemoteGateway (device: OnlineDevice) {
  return remoteControlState.connected && remoteControlState.gatewaySn === device.gateway.sn
}

function destroyRemoteControlClient () {
  resetControlState()
  remoteControlState.mqttClient?.destroyed()
  remoteControlState.mqttClient = null
  remoteControlState.gatewaySn = ''
  remoteControlState.aircraftSn = ''
  remoteControlState.connected = false
  remoteTopicInfo.sn = ''
  remoteTopicInfo.pubTopic = ''
  remoteTopicInfo.subTopic = ''
  store.commit('SET_MQTT_STATE', null)
  store.commit('SET_CLIENT_ID', '')
}

async function withAircraftAction (
  device: OnlineDevice,
  action: string,
  task: () => Promise<any>,
  successText: string
) {
  if (!isGatewayControllable(device)) {
    message.warning('Aircraft is offline or telemetry is not ready.')
    return
  }
  setActionLoading(device.gateway.sn, action)
  try {
    const res = await task()
    if (res.code === 0) {
      message.success(successText)
    }
  } catch (error: any) {
    message.error(error?.message || 'Flight command failed.')
  } finally {
    setActionLoading(device.gateway.sn)
  }
}

async function connectRemoteControl (device: OnlineDevice) {
  if (!isGatewayControllable(device)) {
    message.warning('Aircraft is offline or telemetry is not ready.')
    return false
  }
  if (isCurrentRemoteGateway(device)) {
    return true
  }
  if (remoteControlState.connected) {
    await disconnectRemoteControl()
  }

  setActionLoading(device.gateway.sn, 'connect')
  try {
    const authRes = await postDrc({})
    if (authRes.code !== 0) {
      return false
    }
    const { address, client_id, username, password } = authRes.data
    const mqttClient = new UranusMqtt(address, {
      clientId: client_id,
      username,
      password,
    })
    mqttClient.initMqtt()
    await mqttClient.waitForConnected()
    store.commit('SET_MQTT_STATE', mqttClient)
    store.commit('SET_CLIENT_ID', client_id)

    const enterRes = await postDrcEnter({
      client_id,
      gateway_sn: device.gateway.sn,
    })
    if (enterRes.code !== 0) {
      mqttClient.destroyed()
      store.commit('SET_MQTT_STATE', null)
      store.commit('SET_CLIENT_ID', '')
      return false
    }

    remoteControlState.gatewaySn = device.gateway.sn
    remoteControlState.aircraftSn = device.sn
    remoteControlState.connected = true
    remoteControlState.mqttClient = mqttClient
    remoteTopicInfo.sn = device.gateway.sn
    remoteTopicInfo.pubTopic = enterRes.data.pub?.[0] || ''
    remoteTopicInfo.subTopic = enterRes.data.sub?.[0] || ''

    // Monitor for unexpected MQTT disconnects and reset UI state automatically.
    mqttClient.on('onStatus', (statusOptions: any) => {
      if (
        (statusOptions.status === 'close' || statusOptions.status === 'error') &&
        remoteControlState.connected &&
        remoteControlState.mqttClient === mqttClient
      ) {
        message.warning('Remote control connection lost.')
        destroyRemoteControlClient()
      }
    })

    const authResult = await postFlightAuth(device.gateway.sn)
    if (authResult.code !== 0) {
      message.warning('Remote control entered, but flight authority was not granted.')
    } else {
      message.success('Remote control is ready.')
    }
    return true
  } finally {
    setActionLoading(device.gateway.sn)
  }
}

async function disconnectRemoteControl () {
  if (!remoteControlState.connected) {
    return
  }
  const gatewaySn = remoteControlState.gatewaySn
  setActionLoading(gatewaySn, 'disconnect')
  try {
    const clientId = store.state.clientId
    if (clientId) {
      await postDrcExit({
        client_id: clientId,
        gateway_sn: gatewaySn,
      })
    }
    destroyRemoteControlClient()
    message.success('Remote control disconnected.')
  } finally {
    setActionLoading(gatewaySn)
  }
}

async function holdVerticalControl (device: OnlineDevice, key: KeyCode, durationMs: number, action: string, successText: string) {
  await withAircraftAction(device, action, async () => {
    if (!isCurrentRemoteGateway(device) || !remoteTopicInfo.pubTopic) {
      throw new Error('Remote control is not connected.')
    }
    handleKeyup(key)
    await sleep(durationMs)
    resetControlState()
    return { code: 0 }
  }, successText)
}

async function publishHover (device: OnlineDevice) {
  await withAircraftAction(device, 'hover', async () => {
    if (!isCurrentRemoteGateway(device) || !remoteTopicInfo.pubTopic) {
      throw new Error('Remote control is not connected.')
    }
    resetControlState()
    mqttHooks.publishMqtt(remoteTopicInfo.pubTopic, {
      seq: createSeq(),
      method: DRC_METHOD.STICK_CONTROL,
      data: {
        roll: 1024,
        pitch: 1024,
        throttle: 1024,
        yaw: 1024,
        gimbal_pitch: 1024
      }
    }, { qos: 0 })
    return { code: 0 }
  }, 'Hover command sent.')
}

async function sendEmergencyStop (device: OnlineDevice) {
  await withAircraftAction(device, 'stop', async () => {
    if (!isCurrentRemoteGateway(device) || !remoteTopicInfo.pubTopic) {
      throw new Error('Remote control is not connected.')
    }
    triggerEmergencyStop()
    return { code: 0 }
  }, 'Emergency stop command sent.')
}

async function handleTakeoff (device: OnlineDevice) {
  const osd = deviceInfo.value[device.sn]
  const latitude = Number(osd?.latitude)
  const longitude = Number(osd?.longitude)
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude) || latitude === 0 || longitude === 0) {
    message.warning('Aircraft latitude and longitude telemetry is not ready.')
    return
  }
  // M4T firmware rejects target==current (zero horizontal distance, 336002 with empty output).
  // Offset target ~16 m north (latitude +0.00015°) so the drone takes off then translates briefly.
  const TARGET_LAT_OFFSET_DEG = 0.00015
  const targetLat = latitude + TARGET_LAT_OFFSET_DEG
  const targetLon = longitude
  const confirmMessage = [
    `Confirm official takeoff_to_point for ${device.callsign}?`,
    `Current: ${latitude}, ${longitude}`,
    `Target: ${targetLat}, ${targetLon} (~16 m north)`,
    `Target height: ${OFFICIAL_TAKEOFF_TARGET_HEIGHT} m`,
    `Security takeoff height: ${OFFICIAL_SECURITY_TAKEOFF_HEIGHT} m`,
    'This is not a 1-2 m stick takeoff test.'
  ].join('\n')
  if (!window.confirm(confirmMessage)) {
    return
  }
  await withAircraftAction(device, 'takeoff', async () => {
    return await postTakeoffToPoint(device.gateway.sn, {
      target_latitude: targetLat,
      target_longitude: targetLon,
      target_height: OFFICIAL_TAKEOFF_TARGET_HEIGHT,
      security_takeoff_height: OFFICIAL_SECURITY_TAKEOFF_HEIGHT,
      max_speed: OFFICIAL_TAKEOFF_MAX_SPEED,
      rc_lost_action: LostControlActionInCommandFLight.HOVER,
      exit_wayline_when_rc_lost: WaylineLostControlActionInCommandFlight.EXEC_LOST_ACTION,
      rth_mode: ERthMode.SETTING,
      rth_altitude: OFFICIAL_RTH_ALTITUDE,
      commander_mode_lost_action: ECommanderModeLostAction.EXEC_LOST_ACTION,
      commander_flight_mode: ECommanderFlightMode.SETTING,
      commander_flight_height: OFFICIAL_TAKEOFF_TARGET_HEIGHT
    })
  }, 'Official takeoff_to_point command sent.')
}

async function handleLanding (device: OnlineDevice) {
  if (!window.confirm(`Confirm remote landing preset for ${device.callsign}?`)) {
    return
  }
  await holdVerticalControl(device, KeyCode.ARROW_DOWN, 2200, 'land', 'Landing stick preset sent.')
}

async function handleAxisControl (device: OnlineDevice, direction: 'up' | 'hover' | 'down') {
  if (direction === 'hover') {
    await publishHover(device)
    return
  }
  await holdVerticalControl(
    device,
    direction === 'up' ? KeyCode.ARROW_UP : KeyCode.ARROW_DOWN,
    800,
    direction,
    `${direction} command sent.`
  )
}

onUnmounted(() => {
  EventBus.off('droneControlWs', onDroneControlWsEvent)
  destroyRemoteControlClient()
})

</script>

<style lang="scss">
.project-tsa-wrapper > :first-child {
  height: 50px;
  line-height: 50px;
  align-items: center;
  border-bottom: 1px solid #4f4f4f;
}
.project-tsa-wrapper {
  height: 100%;
  .scrollbar {
    overflow: auto;
  }
  ::-webkit-scrollbar {
    display: none;
  }
}
.ant-collapse > .ant-collapse-item > .ant-collapse-header {
  color: white;
  border: 0;
  padding-left: 14px;
}

.text-hidden {
  overflow: hidden !important;
  text-overflow: ellipsis !important;
  white-space: nowrap;
  -o-text-overflow: ellipsis;
}
.font-bold {
  font-weight: 700;
}

.battery-slide {
  width: 100%;
  .capacity-percent {
    background: #00ee8b;
  }
  .return-home {
    background: #ff9f0a;
  }
  .landing {
    background: #f5222d;
  }
  .battery {
    background: white;
    border-radius: 1px;
    width: 8px;
    height: 4px;
    margin-top: -3px;
  }
}
.battery-slide > div {
  position: relative;
  margin-top: -2px;
  min-height: 2px;
  border-radius: 2px;
  white-space: nowrap;
}
.aircraft-card {
  background: #3c3c3c;
  width: 250px;
  margin-bottom: 12px;
  border-radius: 2px;
  overflow: hidden;
}
.aircraft-osd-panel,
.aircraft-action-panel {
  margin: 0 8px 8px;
  padding: 8px;
  border-radius: 4px;
  background: #2b2b2b;
}
.aircraft-osd-title {
  margin-bottom: 6px;
  font-size: 12px;
  font-weight: 700;
  color: #d9d9d9;
}
.aircraft-osd-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px;
}
.aircraft-osd-item {
  display: flex;
  flex-direction: column;
  padding: 6px;
  border-radius: 4px;
  background: #353535;
}
.aircraft-osd-item .label {
  color: #8c8c8c;
  font-size: 11px;
}
.aircraft-osd-item .value {
  margin-top: 2px;
  color: #f5f5f5;
  font-weight: 700;
}
.aircraft-osd-empty {
  color: #8c8c8c;
  line-height: 18px;
}
.aircraft-action-tips {
  margin-bottom: 8px;
  color: #faad14;
  line-height: 16px;
}
.aircraft-action-row {
  display: flex;
  gap: 6px;
  margin-bottom: 6px;
}
.aircraft-action-row:last-child {
  margin-bottom: 0;
}
.aircraft-action-btn {
  flex: 1;
}
.disable {
  cursor: not-allowed;
}

.notice-blink {
  background: $success;
  animation: blink 500ms infinite;
}
.caution-blink {
  background: orange;
  animation: blink 500ms infinite;
}
.warn-blink {
  background: red;
  animation: blink 500ms infinite;
}
.notice {
  background: $success;
  overflow: hidden;
  cursor: pointer;
}
.caution {
  background: orange;
  cursor: pointer;
  overflow: hidden;
}
.warn {
  background: red;
  cursor: pointer;
  overflow: hidden;
}
.word-loop {
  white-space: nowrap;
  display: inline-block;
  animation: 10s loop linear infinite normal;
}
@keyframes blink {
  from {
    opacity: 1;
  }
  50% {
    opacity: 0.35;
  }
  to {
    opacity: 1;
  }
}
@keyframes loop {
  0% {
    transform: translateX(20px);
    -webkit-transform: translateX(20px);
  }
  100% {
    transform: translateX(-100%);
    -webkit-transform: translateX(-100%);
  }
}

</style>
