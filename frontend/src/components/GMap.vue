<template>
  <div class="g-map-wrapper">
    <!-- 地图区域 -->
    <div id="g-container" :style="{ width: '100%', height: '100%' }" />
    <!-- 绘制面板 -->
    <div
      class="g-action-panel"
      :style="{ right: drawVisible ? '316px' : '16px' }"
    >
      <div :class="state.currentType === 'pin' ? 'g-action-item selection' : 'g-action-item'" @click="draw('pin', true)">
        <a><a-image :src="pin" :preview="false" /></a>
      </div>
      <div :class="state.currentType === 'polyline' ? 'g-action-item selection' : 'g-action-item'" @click="draw('polyline', true)">
        <a><LineOutlined :rotate="135" class="fz20"/></a>
      </div>
      <div :class="state.currentType === 'polygon' && !state.isFlightArea ? 'g-action-item selection' : 'g-action-item'" @click="draw('polygon', true)">
        <a><BorderOutlined class="fz18" /></a>
      </div>
      <FlightAreaActionIcon class="g-action-item mt10" :class="{'selection': mouseMode && state.isFlightArea}" @select-action="selectFlightAreaAction" @click="selectFlightAreaAction"/>
      <div v-if="mouseMode" class="g-action-item" @click="draw('off', false)">
        <a style="color: red;"><CloseOutlined /></a>
      </div>
    </div>
    <div v-if="isWaylineRoute" class="wayline-map-toolbox">
      <button type="button" :class="{'active': waylineMapLayer === 'standard'}" @click="setWaylineMapLayer('standard')">
        标准
      </button>
      <button type="button" :class="{'active': waylineMapLayer === 'satellite'}" @click="setWaylineMapLayer('satellite')">
        卫星
      </button>
      <button type="button" :class="{'active': rangingToolActive}" @click="toggleRangingTool">
        {{ rangingToolActive ? '停止测距' : '测距' }}
      </button>
    </div>
    <!-- 飞机OSD -->
    <div v-if="osdVisible.visible && !osdVisible.is_dock" v-drag-window class="osd-panel fz12">
      <div class="drag-title pl5 pr5 flex-align-center flex-row flex-justify-between" style="border-bottom: 1px solid #515151; height: 18%;">
        <span>{{ osdVisible.callsign }}</span>
        <span><a class="fz16" style="color: white;" @click="() => osdVisible.visible = false"><CloseOutlined /></a></span>
      </div>
      <div style="height: 82%;">
        <div class="flex-column flex-align-center flex-justify-center" style="margin-top: -5px; padding-top: 25px; float: left; width: 60px; background: #2d2d2d;">
          <a-tooltip :title="osdVisible.model">
            <div style="width: 90%;" class="flex-column flex-align-center flex-justify-center">
              <span><a-image :src="M30" :preview="false"/></span>
              <span>{{ osdVisible.model }}</span>
            </div>
          </a-tooltip>
        </div>
        <div class="osd">
            <a-row>
              <a-col span="16" :style="deviceInfo.device.mode_code === EModeCode.Disconnected ? 'color: red; font-weight: 700;': 'color: rgb(25,190,107)'">{{ EModeCode[deviceInfo.device.mode_code] }}</a-col>
            </a-row>
            <a-row>
              <a-col span="6">
                <a-tooltip title="Signal strength">
                  <span>HD</span>
                  <span class="ml10">{{ deviceInfo.gateway?.transmission_signal_quality }}</span>
                </a-tooltip>
              </a-col>
              <a-col span="6">
                <a-tooltip title="RC Battery Level">
                  <span><ThunderboltOutlined class="fz14"/></span>
                  <span class="ml10">{{ deviceInfo.gateway && deviceInfo.gateway.capacity_percent !== str ? deviceInfo.gateway?.capacity_percent + ' %' : deviceInfo.gateway?.capacity_percent }}</span>
                </a-tooltip>
              </a-col>

              <a-col span="6">
                <a-tooltip title="Drone Battery Level">
                  <span><ThunderboltOutlined class="fz14"/></span>
                  <span class="ml10">{{ deviceInfo.device.battery.capacity_percent !== str ? deviceInfo.device.battery.capacity_percent + ' %' : deviceInfo.device.battery.capacity_percent }}</span>
                </a-tooltip>
              </a-col>
            </a-row>
            <a-row>
              <a-tooltip title="RTK Fixed">
                <a-col span="6" class="flex-row flex-align-center flex-justify-start">
                  <span>Fixed</span>
                  <span class="ml10 circle" :style="deviceInfo.device.position_state.is_fixed === 1 ? 'backgroud: rgb(25,190,107);' : ' background: red;'"></span>
                </a-col>
              </a-tooltip>
              <a-col span="6">
                <a-tooltip title="GPS">
                  <span>GPS</span>
                  <span class="ml10">{{ deviceInfo.device.position_state.gps_number }}</span>
                </a-tooltip>
              </a-col>
              <a-col span="6">
                <a-tooltip title="RTK">
                  <span><TrademarkOutlined class="fz14"/></span>
                  <span class="ml10">{{ deviceInfo.device.position_state.rtk_number }}</span>
                </a-tooltip>
              </a-col>
            </a-row>
            <a-row>
              <a-col span="6">
                <a-tooltip title="Flight Mode">
                  <span><ControlOutlined class="fz16" /></span>
                  <span class="ml10">{{ EGear[deviceInfo.device.gear] }}</span>
                </a-tooltip>
              </a-col>
              <a-col span="6">
                <a-tooltip title="Altitude above sea level">
                  <span>ASL</span>
                  <span class="ml10">{{ deviceInfo.device.height === str ? str : deviceInfo.device.height.toFixed(2) + ' m'}}</span>
                </a-tooltip>
              </a-col>
              <a-col span="6">
                <a-tooltip title="Altitude above takeoff level">
                  <span>ALT</span>
                  <span class="ml10">{{ deviceInfo.device.elevation === str ? str : deviceInfo.device.elevation.toFixed(2) + ' m' }}</span>
                </a-tooltip>
              </a-col>
              <a-col span="6">
                <a-tooltip title="Distance to Home Point">
                  <span>H</span>
                  <span class="ml10">{{ deviceInfo.device.home_distance === str ? str : deviceInfo.device.home_distance.toFixed(2) + ' m' }}</span>
                </a-tooltip>
              </a-col>
            </a-row>
            <a-row>
              <a-col span="6">
                <a-tooltip title="Horizontal Speed">
                  <span>H.S</span>
                  <span class="ml10">{{ deviceInfo.device.horizontal_speed === str ? str : deviceInfo.device.horizontal_speed.toFixed(2) + ' m/s'}}</span>
                </a-tooltip>
              </a-col>
              <a-col span="6">
                <a-tooltip title="Vertical Speed">
                  <span>V.S</span>
                  <span class="ml10">{{ deviceInfo.device.vertical_speed === str ? str : deviceInfo.device.vertical_speed.toFixed(2) + ' m/s'}}</span>
                </a-tooltip>
              </a-col>
              <a-col span="6">
                <a-tooltip title="Wind Speed">
                  <span>W.S</span>
                  <span class="ml10">{{ deviceInfo.device.wind_speed === str ? str : (deviceInfo.device.wind_speed / 10).toFixed(2) + ' m/s'}}</span>
                </a-tooltip>
              </a-col>
            </a-row>
        </div>
      </div>
      <div class="battery-slide" v-if="deviceInfo.device.battery.remain_flight_time !== 0">
        <div style="background: #535759;" class="width-100"></div>
        <div class="capacity-percent" :style="{ width: deviceInfo.device.battery.capacity_percent + '%'}"></div>
        <div class="return-home" :style="{ width: deviceInfo.device.battery.return_home_power + '%'}"></div>
        <div class="landing" :style="{ width: deviceInfo.device.battery.landing_power + '%'}"></div>
        <div class="white-point" :style="{ left: deviceInfo.device.battery.landing_power + '%'}"></div>
        <div class="battery" :style="{ left: deviceInfo.device.battery.capacity_percent + '%' }">
          {{ Math.floor(deviceInfo.device.battery.remain_flight_time / 60) }}:
          {{ 10 > (deviceInfo.device.battery.remain_flight_time % 60) ? '0' : ''}}{{deviceInfo.device.battery.remain_flight_time % 60 }}
        </div>
      </div>
    </div>
    <!-- 机场OSD -->
    <div v-if="osdVisible.visible && osdVisible.is_dock"  v-drag-window class="osd-panel fz12">
      <div class="drag-title fz16 pl5 pr5 flex-align-center flex-row flex-justify-between" style="border-bottom: 1px solid #515151; height: 10%;">
        <span>{{ osdVisible.gateway_callsign }}</span>
      </div>
      <span><a style="color: white; position: absolute; top: 5px; right: 5px;" @click="() => osdVisible.visible = false"><CloseOutlined /></a></span>
        <!-- 机场 -->
      <div class ="flex-display" style="border-bottom: 1px solid #515151;">
        <div class="flex-column flex-align-stretch flex-justify-center" style="width: 60px; background: #2d2d2d;">
          <a-tooltip :title="osdVisible.model">
            <div class="flex-column  flex-align-center flex-justify-center" style="width: 90%;">
              <span><RobotFilled style="font-size: 48px;"/></span>
              <span class="mt10">Dock</span>
            </div>
          </a-tooltip>
        </div>
        <div class="osd flex-1" style="flex: 1">
            <a-row>
              <a-col span="16" :style="deviceInfo.dock.basic_osd?.mode_code === EDockModeCode.Disconnected ? 'color: red; font-weight: 700;': 'color: rgb(25,190,107)'">
                {{ EDockModeCode[deviceInfo.dock.basic_osd?.mode_code] }}</a-col>
            </a-row>
            <a-row>
              <a-col span="12">
                <a-tooltip title="Accumulated Running Time">
                  <span><HistoryOutlined /></span>
                  <span class="ml10">
                    <span v-if="deviceInfo.dock.work_osd?.acc_time >= 2592000"> {{ Math.floor(deviceInfo.dock.work_osd?.acc_time / 2592000) }}m </span>
                    <span v-if="(deviceInfo.dock.work_osd?.acc_time % 2592000) >= 86400"> {{ Math.floor((deviceInfo.dock.work_osd?.acc_time % 2592000) / 86400) }}d </span>
                    <span v-if="(deviceInfo.dock.work_osd?.acc_time % 2592000 % 86400) >= 3600"> {{ Math.floor((deviceInfo.dock.work_osd?.acc_time % 2592000 % 86400) / 3600) }}h </span>
                    <span v-if="(deviceInfo.dock.work_osd?.acc_time % 2592000 % 86400 % 3600) >= 60"> {{ Math.floor((deviceInfo.dock.work_osd?.acc_time % 2592000 % 86400 % 3600) / 60) }}min </span>
                    <span>{{ Math.floor(deviceInfo.dock.work_osd?.acc_time % 2592000 % 86400 % 3600 % 60) }} s</span>
                  </span>
                </a-tooltip>
              </a-col>
              <a-col span="12">
                <a-tooltip title="Activation time">
                  <span><FieldTimeOutlined /></span>
                  <span class="ml10">{{ new Date((deviceInfo.dock.work_osd?.activation_time ?? 0) * 1000).toLocaleString() }}
                  </span>
                </a-tooltip>
              </a-col>
            </a-row>
            <a-row>
              <a-col span="6">
                <a-tooltip title="Network State">
                  <span :style="qualityStyle">
                    <span v-if="deviceInfo.dock.basic_osd?.network_state?.type === NetworkStateTypeEnum.FOUR_G"><SignalFilled /></span>
                    <span v-else><GlobalOutlined /></span>
                  </span>
                  <span class="ml10" >{{ deviceInfo.dock.basic_osd?.network_state?.rate }} kb/s</span>
                </a-tooltip>
              </a-col>
              <a-col span="6">
                <a-tooltip title="The total number of times the dock has performed missions.">
                  <span><CarryOutOutlined /></span>
                  <span class="ml10" >{{ deviceInfo.dock.work_osd?.job_number }} </span>
                </a-tooltip>
              </a-col>
              <a-col span="6">
                <a-tooltip title="Media File Remain Upload">
                  <span><CloudUploadOutlined class="fz14"/></span>
                  <span class="ml10">{{ deviceInfo.dock.link_osd?.media_file_detail?.remain_upload }}</span>
                </a-tooltip>
              </a-col>
              <a-col span="6">
                <a-tooltip>
                  <template #title>
                    <p>total: {{ deviceInfo.dock.basic_osd?.storage?.total }}</p>
                    <p>used: {{ deviceInfo.dock.basic_osd?.storage?.used  }}</p>
                  </template>
                  <span><FolderOpenOutlined /></span>
                  <span class="ml10" v-if="deviceInfo.dock.basic_osd?.storage?.total > 0">
                    <a-progress type="circle" :width="20" :percent="deviceInfo.dock.basic_osd?.storage?.used * 100/ deviceInfo.dock.basic_osd?.storage?.total"
                      :strokeWidth="20" :showInfo="false" :strokeColor="deviceInfo.dock.basic_osd?.storage?.used * 100 / deviceInfo.dock.basic_osd?.storage?.total > 80 ? 'red' : '#00ee8b' "/>
                  </span>
                </a-tooltip>
              </a-col>
            </a-row>
            <a-row>
              <a-col span="6">
                <a-tooltip title="Wind Speed">
                  <span>W.S</span>
                  <span class="ml10">{{ (deviceInfo.dock.basic_osd?.wind_speed ?? str) + ' m/s'}}</span>
                </a-tooltip>
              </a-col>
              <a-col span="6">
                <a-tooltip title="Rainfall">
                  <span>🌧</span>
                  <span class="ml10">{{ RainfallEnum[deviceInfo.dock.basic_osd?.rainfall] }}</span>
                </a-tooltip>
              </a-col>
              <a-col span="6">
                <a-tooltip title="Environment Temperature">
                  <span>°C</span>
                  <span class="ml10">{{ deviceInfo.dock.basic_osd?.environment_temperature }}</span>
                </a-tooltip>
              </a-col>
              <a-col span="6">
                <a-tooltip title="Dock Temperature">
                  <span>°C</span>
                  <span class="ml10">{{ deviceInfo.dock.basic_osd?.temperature }}</span>
                </a-tooltip>
              </a-col>
            </a-row>
            <a-row>
              <a-col span="6">
                <a-tooltip title="Dock Humidity">
                  <span>💦</span>
                  <span class="ml10">{{ deviceInfo.dock.basic_osd?.humidity }}</span>
                </a-tooltip>
              </a-col>
              <a-col span="6">
                <a-tooltip title="Working Voltage">
                  <span style="border: 1px solid; border-radius: 50%; width: 18px; height: 18px; line-height: 16px; text-align: center; float: left;">V</span>
                  <span class="ml10">{{ (deviceInfo.dock.work_osd?.working_voltage ?? str) + ' mV' }}</span>
                </a-tooltip>
              </a-col>
              <a-col span="6">
                <a-tooltip title="Working Current">
                  <span style="border: 1px solid; border-radius: 50%; width: 18px; height: 18px; line-height: 15px; text-align: center; float: left;" >A</span>
                  <span class="ml10">{{ (deviceInfo.dock.work_osd?.working_current ?? str) + ' mA' }}</span>
                </a-tooltip>
              </a-col>
              <a-col span="6">
                <a-tooltip title="Drone in dock">
                  <span><RocketOutlined /></span>
                  <span class="ml10">{{ deviceInfo.dock.basic_osd?.drone_in_dock }}</span>
                </a-tooltip>
              </a-col>
            </a-row>
            <a-row class="p5">
              <a-col span="24">
                <a-button type="primary" :disabled="dockControlPanelVisible" size="small" @click="setDockControlPanelVisible(true)">
                  Actions
                </a-button>
              </a-col>
            </a-row>
            <!-- 机场控制面板 -->
            <DockControlPanel v-if="dockControlPanelVisible" :sn="osdVisible.gateway_sn"  :deviceInfo="deviceInfo" @close-control-panel="onCloseControlPanel">
            </DockControlPanel>
        </div>
      </div>
      <!--  飞机-->
      <div class ="flex-display">
        <div class="flex-column flex-align-stretch flex-justify-center" style="width: 60px;  background: #2d2d2d;">
          <a-tooltip :title="osdVisible.model">
            <div style="width: 90%;" class="flex-column flex-align-center flex-justify-center">
              <span><a-image :src="M30" :preview="false"/></span>
              <span>M30</span>
            </div>
          </a-tooltip>
        </div>
        <div class="osd flex-1">
            <a-row>
              <a-col span="16" :style="!deviceInfo.device || deviceInfo.device?.mode_code === EModeCode.Disconnected ? 'color: red; font-weight: 700;': 'color: rgb(25,190,107)'">
                {{ !deviceInfo.device ? EModeCode[EModeCode.Disconnected] : EModeCode[deviceInfo.device?.mode_code] }}</a-col>
            </a-row>
            <a-row>
              <a-col span="6">
                <a-tooltip title="Upward Quality">
                  <span><SignalFilled /><ArrowUpOutlined style="font-size: 9px; vertical-align: top;" /></span>
                  <span class="ml10">{{ deviceInfo.dock.link_osd?.sdr?.up_quality }}</span>
                </a-tooltip>
              </a-col>
              <a-col span="6">
                <a-tooltip title="Downward Quality">
                  <span><SignalFilled /><ArrowDownOutlined style="font-size: 9px; vertical-align: top;" /></span>
                  <span class="ml10">{{ deviceInfo.dock.link_osd?.sdr?.down_quality }}</span>
                </a-tooltip>
              </a-col>
              <a-col span="6">
                <a-tooltip title="Drone Battery Level">
                  <span><ThunderboltOutlined class="fz14"/></span>
                  <span class="ml10">{{ deviceInfo.device && deviceInfo.device.battery.capacity_percent !== str ? deviceInfo.device?.battery.capacity_percent + ' %' : str }}</span>
                </a-tooltip>
              </a-col>
              <a-col span="6">
                <a-tooltip>
                  <template #title>
                    <p>total: {{ deviceInfo.device?.storage?.total }}</p>
                    <p>used: {{ deviceInfo.device?.storage?.used  }}</p>
                  </template>
                  <span><FolderOpenOutlined /></span>
                  <span class="ml10" v-if="deviceInfo.device?.storage?.total > 0">
                    <a-progress type="circle" :width="20" :percent="deviceInfo.device?.storage?.used * 100/ deviceInfo.device?.storage?.total"
                      :strokeWidth="20" :showInfo="false" :strokeColor="deviceInfo.device?.storage?.used * 100 / deviceInfo.device?.storage?.total > 80 ? 'red' : '#00ee8b' "/>
                  </span>
                </a-tooltip>
              </a-col>
            </a-row>
            <a-row>
              <a-tooltip title="RTK Fixed">
                <a-col span="6" class="flex-row flex-align-center flex-justify-start">
                  <span>Fixed</span>
                  <span class="ml10 circle" :style="deviceInfo.device?.position_state.is_fixed === 1 ? 'backgroud: rgb(25,190,107);' : ' background: red;'"></span>
                </a-col>
              </a-tooltip>
              <a-col span="6">
                <a-tooltip title="GPS">
                  <span>GPS</span>
                  <span class="ml10">{{ deviceInfo.device ? deviceInfo.device.position_state.gps_number : str }}</span>
                </a-tooltip>
              </a-col>
              <a-col span="6">
                <a-tooltip title="RTK">
                  <span><TrademarkOutlined class="fz14"/></span>
                  <span class="ml10">{{ deviceInfo.device ? deviceInfo.device.position_state.rtk_number : str }}</span>
                </a-tooltip>
              </a-col>
            </a-row>
            <a-row>
              <a-col span="6">
                <a-tooltip title="Flight Mode">
                  <span><ControlOutlined class="fz16" /></span>
                  <span class="ml10">{{ deviceInfo.device ? EGear[deviceInfo.device?.gear] : str }}</span>
                </a-tooltip>
              </a-col>
              <a-col span="6">
                <a-tooltip title="Altitude above sea level">
                  <span>ASL</span>
                  <span class="ml10">{{ !deviceInfo.device || deviceInfo.device.height === str ? str : deviceInfo.device?.height.toFixed(2) + ' m'}}</span>
                </a-tooltip>
              </a-col>
              <a-col span="6">
                <a-tooltip title="Altitude above takeoff level">
                  <span>ALT</span>
                  <span class="ml10">{{ !deviceInfo.device || deviceInfo.device.elevation === str ? str : deviceInfo.device?.elevation.toFixed(2) + ' m' }}</span>
                </a-tooltip>
              </a-col>
              <a-col span="6">
                <a-tooltip title="Distance to Home Point">
                  <span style="border: 1px solid; border-radius: 50%; width: 18px; height: 18px; line-height: 15px; text-align: center;  display: block; float: left;" >H</span>
                  <span class="ml10">{{ !deviceInfo.device || deviceInfo.device.home_distance === str ? str : deviceInfo.device?.home_distance.toFixed(2) + ' m' }}</span>
                </a-tooltip>
              </a-col>
            </a-row>
            <a-row>
              <a-col span="6">
                <a-tooltip title="Horizontal Speed">
                  <span>H.S</span>
                  <span class="ml10">{{ !deviceInfo.device || deviceInfo.device?.horizontal_speed === str ? str : deviceInfo.device?.horizontal_speed.toFixed(2) + ' m/s'}}</span>
                </a-tooltip>
              </a-col>
              <a-col span="6">
                <a-tooltip title="Vertical Speed">
                  <span>V.S</span>
                  <span class="ml10">{{ !deviceInfo.device || deviceInfo.device.vertical_speed === str ? str : deviceInfo.device?.vertical_speed.toFixed(2) + ' m/s'}}</span>
                </a-tooltip>
              </a-col>
              <a-col span="6">
                <a-tooltip title="Wind Speed">
                  <span>W.S</span>
                  <span class="ml10">{{ !deviceInfo.device || deviceInfo.device.wind_speed === str ? str : (deviceInfo.device?.wind_speed / 10).toFixed(2) + ' m/s'}}</span>
                </a-tooltip>
              </a-col>
            </a-row>
        </div>
      </div>
      <div class="battery-slide" v-if="deviceInfo.device && deviceInfo.device.battery.remain_flight_time !== 0" style="border: 1px solid red">
        <div style="background: #535759;" class="width-100"></div>
        <div class="capacity-percent" :style="{ width: deviceInfo.device.battery.capacity_percent + '%'}"></div>
        <div class="return-home" :style="{ width: deviceInfo.device.battery.return_home_power + '%'}"></div>
        <div class="landing" :style="{ width: deviceInfo.device.battery.landing_power + '%'}"></div>
        <div class="white-point" :style="{ left: deviceInfo.device.battery.landing_power + '%'}"></div>
        <div class="battery" :style="{ left: deviceInfo.device.battery.capacity_percent + '%' }">
          {{ Math.floor(deviceInfo.device.battery.remain_flight_time / 60) }}:
          {{ 10 > (deviceInfo.device.battery.remain_flight_time % 60) ? '0' : ''}}{{deviceInfo.device.battery.remain_flight_time % 60 }}
        </div>
      </div>
      <!-- 飞行指令 -->
      <DroneControlPanel :sn="osdVisible.gateway_sn" :deviceInfo="deviceInfo" :payloads="osdVisible.payloads"></DroneControlPanel>
    </div>
    <!-- liveview -->
    <div class="liveview" v-if="livestreamOthersVisible" v-drag-window  >
      <div style="height: 40px; width: 100%" class="drag-title"></div>
      <a style="position: absolute; right: 10px; top: 10px; font-size: 16px; color: white;" @click="closeLivestreamOthers"><CloseOutlined /></a>
      <LivestreamOthers />
    </div>
    <a-tooltip title="切换到飞机位置">
      <button
        class="aircraft-follow-control"
        :disabled="!hasFlightPosition"
        @click="locateAircraftPosition">
        <AimOutlined />
      </button>
    </a-tooltip>
  </div>
</template>

<script lang="ts">
import { computed, defineComponent, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import {
  generateLineContent,
  generatePointContent,
  generatePolyContent
} from '../utils/map-layer-utils'
import { postElementsReq } from '/@/api/layer'
import { MapDoodleType, MapElementEnum } from '/@/constants/map'
import { useGMapManage } from '/@/hooks/use-g-map'
import { useGMapCover } from '/@/hooks/use-g-map-cover'
import { useMouseTool } from '/@/hooks/use-mouse-tool'
import { getApp, getRoot } from '/@/root'
import { useMyStore } from '/@/store'
import { GeojsonCoordinate } from '/@/types/map'
import { MapDoodleEnum } from '/@/types/map-enum'
import { PostElementsBody } from '/@/types/mapLayer'
import { uuidv4 } from '/@/utils/uuid'
import { gcj02towgs84, wgs84togcj02 } from '/@/vendors/coordtransform'
import { deviceTsaUpdate } from '/@/hooks/use-g-map-tsa'
import {
  DeviceOsd, DeviceStatus, DockOsd, EGear, EModeCode, GatewayOsd, EDockModeCode,
  NetworkStateQualityEnum, NetworkStateTypeEnum, RainfallEnum, DroneInDockEnum
} from '/@/types/device'
import pin from '/@/assets/icons/pin-2d8cf0.svg'
import M30 from '/@/assets/icons/m30.png'
import {
  BorderOutlined, LineOutlined, CloseOutlined, ControlOutlined, TrademarkOutlined, ArrowDownOutlined,
  ThunderboltOutlined, SignalFilled, GlobalOutlined, HistoryOutlined, CloudUploadOutlined, RocketOutlined,
  FieldTimeOutlined, CloudOutlined, CloudFilled, FolderOpenOutlined, RobotFilled, ArrowUpOutlined, CarryOutOutlined,
  AimOutlined
} from '@ant-design/icons-vue'
import { EDeviceTypeName, ERouterName } from '../types'
import DockControlPanel from './g-map/DockControlPanel.vue'
import { useDockControl } from './g-map/use-dock-control'
import DroneControlPanel from './g-map/DroneControlPanel.vue'
import { useConnectMqtt } from './g-map/use-connect-mqtt'
import LivestreamOthers from './livestream-others.vue'
import FlightAreaActionIcon from './flight-area/FlightAreaActionIcon.vue'
import { EFlightAreaType } from '../types/flight-area'
import { useFlightArea } from './flight-area/use-flight-area'
import { useFlightAreaDroneLocationEvent } from './flight-area/use-flight-area-drone-location-event'
import { addWaypointGcj, getPlanningStateRaw, selectWaypoint, setFlightPositionFromWgs } from '/@/hooks/use-wayline-planning'

export default defineComponent({
  components: {
    BorderOutlined,
    LineOutlined,
    CloseOutlined,
    ControlOutlined,
    TrademarkOutlined,
    ThunderboltOutlined,
    SignalFilled,
    GlobalOutlined,
    HistoryOutlined,
    CloudUploadOutlined,
    FieldTimeOutlined,
    CloudOutlined,
    CloudFilled,
    FolderOpenOutlined,
    RobotFilled,
    ArrowUpOutlined,
    ArrowDownOutlined,
    DockControlPanel,
    DroneControlPanel,
    CarryOutOutlined,
    RocketOutlined,
    AimOutlined,
    LivestreamOthers,
    FlightAreaActionIcon,
  },
  name: 'GMap',
  props: {},
  setup () {
    const useMouseToolHook = useMouseTool()
    const useGMapManageHook = useGMapManage()
    const deviceTsaUpdateHook = deviceTsaUpdate()
    const root = getRoot()
    const planningState = getPlanningStateRaw()

    const mouseMode = ref(false)
    const waylineMapLayer = ref<'standard' | 'satellite'>('standard')
    const rangingToolActive = ref(false)
    const isWaylineRoute = computed(() => root.$route.name === ERouterName.WAYLINE)
    const hasFlightPosition = computed(() => !!planningState.flightPosition)
    const store = useMyStore()
    const state = reactive({
      currentType: '',
      coverIndex: 0,
      isFlightArea: false,
    })
    const str: string = '--'
    const deviceInfo = reactive({
      gateway: {
        capacity_percent: str,
        transmission_signal_quality: str,
      } as GatewayOsd,
      dock: {

      } as DockOsd,
      device: {
        gear: -1,
        mode_code: EModeCode.Disconnected,
        height: str,
        home_distance: str,
        horizontal_speed: str,
        vertical_speed: str,
        wind_speed: str,
        wind_direction: str,
        elevation: str,
        position_state: {
          gps_number: str,
          is_fixed: 0,
          rtk_number: str
        },
        battery: {
          capacity_percent: str,
          landing_power: str,
          remain_flight_time: 0,
          return_home_power: str,
        },
        latitude: 0,
        longitude: 0,
      } as DeviceOsd
    })
    const shareId = computed(() => {
      return store.state.layerBaseInfo.share
    })
    const defaultId = computed(() => {
      return store.state.layerBaseInfo.default
    })
    const drawVisible = computed(() => {
      return store.state.drawVisible
    })
    const livestreamOthersVisible = computed(() => {
      return store.state.livestreamOthersVisible
    })
    const osdVisible = computed(() => {
      return store.state.osdVisible
    })
    const qualityStyle = computed(() => {
      if (deviceInfo.dock.basic_osd?.network_state?.type === NetworkStateTypeEnum.ETHERNET ||
        (deviceInfo.dock.basic_osd?.network_state?.quality || 0) > NetworkStateQualityEnum.FAIR) {
        return 'color: #00ee8b'
      }
      if ((deviceInfo.dock.basic_osd?.network_state?.quality || 0) === NetworkStateQualityEnum.FAIR) {
        return 'color: yellow'
      }
      return 'color: red'
    })
    watch(() => store.state.deviceStatusEvent,
      data => {
        if (Object.keys(data.deviceOnline).length !== 0) {
          deviceTsaUpdateHook.initMarker(data.deviceOnline.domain, data.deviceOnline.device_callsign, data.deviceOnline.sn)
          store.state.deviceStatusEvent.deviceOnline = {} as DeviceStatus
        }
        if (Object.keys(data.deviceOffline).length !== 0) {
          deviceTsaUpdateHook.removeMarker(data.deviceOffline.sn)
          if ((data.deviceOffline.sn === osdVisible.value.sn) || (osdVisible.value.is_dock && data.deviceOffline.sn === osdVisible.value.gateway_sn)) {
            osdVisible.value.visible = false
            store.commit('SET_OSD_VISIBLE_INFO', osdVisible)
          }
          store.state.deviceStatusEvent.deviceOffline = {}
        }
      },
      {
        deep: true
      }
    )

    watch(() => store.state.deviceState, data => {
      if (data.currentType === EDeviceTypeName.Gateway && data.gatewayInfo[data.currentSn]) {
        const coordinate = wgs84togcj02(data.gatewayInfo[data.currentSn].longitude, data.gatewayInfo[data.currentSn].latitude)
        deviceTsaUpdateHook.moveTo(data.currentSn, coordinate[0], coordinate[1])
        if (osdVisible.value.visible && osdVisible.value.gateway_sn !== '') {
          deviceInfo.gateway = data.gatewayInfo[osdVisible.value.gateway_sn]
        }
      }
      if (data.currentType === EDeviceTypeName.Aircraft && data.deviceInfo[data.currentSn]) {
        const osd = data.deviceInfo[data.currentSn]
        const coordinate = wgs84togcj02(osd.longitude, osd.latitude)
        deviceTsaUpdateHook.moveTo(data.currentSn, coordinate[0], coordinate[1])
        updateFlightPositionFromOsd(data.currentSn, osd, coordinate[0], coordinate[1])
        if (osdVisible.value.visible && osdVisible.value.sn !== '') {
          deviceInfo.device = data.deviceInfo[osdVisible.value.sn]
        }
      }
      if (data.currentType === EDeviceTypeName.Dock && data.dockInfo[data.currentSn]) {
        const coordinate = wgs84togcj02(data.dockInfo[data.currentSn].basic_osd?.longitude, data.dockInfo[data.currentSn].basic_osd?.latitude)
        deviceTsaUpdateHook.initMarker(EDeviceTypeName.Dock, EDeviceTypeName[EDeviceTypeName.Dock], data.currentSn, coordinate[0], coordinate[1])
        if (osdVisible.value.visible && osdVisible.value.is_dock && osdVisible.value.gateway_sn !== '') {
          deviceInfo.dock = data.dockInfo[osdVisible.value.gateway_sn]
          deviceInfo.device = data.deviceInfo[deviceInfo.dock.basic_osd.sub_device?.device_sn ?? osdVisible.value.sn]
        }
      }
    }, {
      deep: true
    })

    watch(
      () => store.state.wsEvent,
      newData => {
        const useGMapCoverHook = useGMapCover()
        const event = newData
        let exist = false
        if (Object.keys(event.mapElementCreat).length !== 0) {
          console.log(event.mapElementCreat)
          const ele = event.mapElementCreat
          store.state.Layers.forEach(layer => {
            layer.elements.forEach(e => {
              if (e.id === ele.id) {
                exist = true
                console.log('true')
              }
            })
          })
          if (exist === false) {
            setLayers({
              id: ele.id,
              name: ele.name,
              resource: ele.resource
            })

            updateCoordinates('wgs84-gcj02', ele)
            const data = { id: ele.id, name: ele.name }
            if (MapElementEnum.PIN === ele.resource?.type) {
              useGMapCoverHook.init2DPin(
                ele.name,
                ele.resource.content.geometry.coordinates,
                ele.resource.content.properties.color,
                data
              )
            } else if (MapElementEnum.LINE === ele.resource?.type) {
              useGMapCoverHook.initPolyline(
                ele.name,
                ele.resource.content.geometry.coordinates,
                ele.resource.content.properties.color,
                data)
            } else if (MapElementEnum.POLY === ele.resource?.type) {
              useGMapCoverHook.initPolygon(
                ele.name,
                ele.resource.content.geometry.coordinates,
                ele.resource.content.properties.color,
                data)
            }
          }

          store.state.wsEvent.mapElementCreat = {}
        }
        if (Object.keys(event.mapElementUpdate).length !== 0) {
          console.log(event.mapElementUpdate)
          console.log('该功能还未实现，请开发商自己增加')
          store.state.wsEvent.mapElementUpdate = {}
        }
        if (Object.keys(event.mapElementDelete).length !== 0) {
          console.log(event.mapElementDelete)
          console.log('该功能还未实现，请开发商自己增加')
          store.state.wsEvent.mapElementDelete = {}
        }
      },
      {
        deep: true
      }
    )

    function draw (type: MapDoodleType, bool: boolean, flightAreaType?: EFlightAreaType) {
      state.currentType = type
      mouseMode.value = bool
      state.isFlightArea = !!flightAreaType
      useMouseToolHook.mouseTool(type, getDrawCallback, flightAreaType)
    }

    // dock 控制面板
    const {
      dockControlPanelVisible,
      setDockControlPanelVisible,
      onCloseControlPanel,
    } = useDockControl()

    // 连接或断开drc
    useConnectMqtt()

    onMounted(() => {
      const app = getApp()
      useGMapManageHook.globalPropertiesConfig(app)
      rebuildPlanningOverlays()
      if (planningState.active) {
        bindPlanningClick()
      }
    })

    onUnmounted(() => {
      unbindPlanningClick()
      clearPlanningOverlays()
      clearFlightPositionOverlay()
      stopRangingTool()
      rangingTool = null
      if (satelliteLayer) {
        satelliteLayer.setMap(null)
        satelliteLayer = null
      }
      if (roadNetLayer) {
        roadNetLayer.setMap(null)
        roadNetLayer = null
      }
    })

    const { getDrawFlightAreaCallback, onFlightAreaDroneLocationWs } = useFlightArea()
    useFlightAreaDroneLocationEvent(onFlightAreaDroneLocationWs)

    function selectFlightAreaAction ({ type, isCircle }: { type: EFlightAreaType, isCircle: boolean }) {
      draw(isCircle ? MapDoodleEnum.CIRCLE : MapDoodleEnum.POLYGON, true, type)
    }

    // ---------- Wayline planning (click-to-fly) wiring ----------
    // When planning mode is active we register a map click handler that records
    // the clicked point as a waypoint; we also maintain AMap markers + a
    // polyline visualising the current waypoint list.
    const renderPlanningWaypoints = computed(() => {
      return planningState.previewWaypoints.length > 0 ? planningState.previewWaypoints : planningState.waypoints
    })
    const planningMarkers: any[] = []
    let planningPolyline: any = null
    let flightPositionMarker: any = null
    let flightTrackPolyline: any = null
    let standardLayer: any = null
    let satelliteLayer: any = null
    let roadNetLayer: any = null
    let waylineLayerDefaulted = false
    let rangingTool: any = null
    let flightTrackAircraftSn = ''
    const flightTrackPath: any[] = []
    let planningClickBound = false
    // 进入页面请求居中飞机时，如果此刻还没有飞机位置，先挂起，等位置到达后居中一次。
    let pendingAircraftRecenter = false

    function onPlanningMapClick (e: any) {
      if (!planningState.active) return
      const lng = e?.lnglat?.getLng?.()
      const lat = e?.lnglat?.getLat?.()
      if (!Number.isFinite(lng) || !Number.isFinite(lat)) return
      addWaypointGcj(lng, lat)
    }

    function clearPlanningOverlays () {
      const map = root?.$map
      planningMarkers.forEach(m => map?.remove(m))
      planningMarkers.length = 0
      if (planningPolyline) {
        map?.remove(planningPolyline)
        planningPolyline = null
      }
    }

    function clearFlightPositionOverlay () {
      const map = root?.$map
      if (flightPositionMarker) {
        map?.remove(flightPositionMarker)
        flightPositionMarker = null
      }
      if (flightTrackPolyline) {
        map?.remove(flightTrackPolyline)
        flightTrackPolyline = null
      }
      flightTrackAircraftSn = ''
      flightTrackPath.length = 0
    }

    function waypointMarkerContent (idx: number, id: string) {
      const isStart = idx === 0
      const isSelected = planningState.selectedWaypointId === id
      const classes = [
        'wayline-planning-marker',
        isStart ? 'wayline-planning-marker--start' : '',
        isSelected ? 'wayline-planning-marker--selected' : '',
      ].filter(Boolean).join(' ')
      return `<div class="${classes}"><span>${idx + 1}</span></div>`
    }

    function rebuildPlanningOverlays () {
      const AMap = root?.$aMap
      const map = root?.$map
      if (!AMap || !map) return
      clearPlanningOverlays()
      const waypoints = renderPlanningWaypoints.value
      if (waypoints.length === 0) return
      waypoints.forEach((wp, idx) => {
        const marker = new AMap.Marker({
          position: [wp.gcjLng, wp.gcjLat],
          content: waypointMarkerContent(idx, wp.id),
          anchor: 'bottom-center',
          zIndex: planningState.selectedWaypointId === wp.id ? 130 : 110,
          extData: { waylinePlanningId: wp.id },
        })
        marker.on('click', () => selectWaypoint(wp.id))
        map.add(marker)
        planningMarkers.push(marker)
      })
      if (waypoints.length >= 2) {
        planningPolyline = new AMap.Polyline({
          path: waypoints.map(w => [w.gcjLng, w.gcjLat]),
          strokeColor: '#00e5ff',
          strokeOpacity: 0.92,
          strokeWeight: 4,
          strokeStyle: 'solid',
          showDir: true,
        })
        map.add(planningPolyline)
      }
      fitPlanningPreviewToMap()
      updateFlightPositionOverlay()
    }

    function flightPositionContent (label: string) {
      const progress = label ? `<em>${label}</em>` : ''
      return `<div class="flight-position-marker"><span>✈️</span>${progress}</div>`
    }

    function setAircraftView (position = planningState.flightPosition) {
      const map = root?.$map
      if (!map || !position) return
      const currentZoom = typeof map.getZoom === 'function' ? Number(map.getZoom()) : 17
      const zoom = Number.isFinite(currentZoom) ? Math.max(currentZoom, 17) : 17
      map.setZoomAndCenter(zoom, [position.gcjLng, position.gcjLat])
    }

    function locateAircraftPosition () {
      setAircraftView()
    }

    function ensureStandardLayer () {
      const AMap = root?.$aMap
      if (!AMap || standardLayer) return standardLayer
      if (typeof AMap.createDefaultLayer === 'function') {
        standardLayer = AMap.createDefaultLayer()
      }
      return standardLayer
    }

    function ensureSatelliteLayer () {
      const AMap = root?.$aMap
      if (!AMap || satelliteLayer) return satelliteLayer
      satelliteLayer = new AMap.TileLayer.Satellite()
      return satelliteLayer
    }

    function ensureRoadNetLayer () {
      const AMap = root?.$aMap
      if (!AMap || roadNetLayer) return roadNetLayer
      roadNetLayer = new AMap.TileLayer.RoadNet()
      return roadNetLayer
    }

    function setWaylineMapLayer (layer: 'standard' | 'satellite') {
      const map = root?.$map
      if (!map) return
      const standard = ensureStandardLayer()
      const satellite = ensureSatelliteLayer()
      if (!satellite) return
      const roadNet = ensureRoadNetLayer()
      waylineMapLayer.value = layer
      if (typeof map.setLayers === 'function' && standard) {
        if (layer === 'standard') {
          map.setLayers([standard])
        } else {
          // 卫星影像叠加路网标注（司空2 风格）
          map.setLayers([standard, satellite, roadNet].filter(Boolean))
        }
        return
      }
      if (layer === 'satellite') {
        satellite.setMap(map)
        if (roadNet) roadNet.setMap(map)
      } else {
        satellite.setMap(null)
        if (roadNet) roadNet.setMap(null)
      }
    }

    // 航线页进入时默认卫星图（每个会话首次进入应用一次；其他页面不受影响）
    function applyWaylineDefaultLayer () {
      if (!isWaylineRoute.value || waylineLayerDefaulted) return
      if (!root?.$map) {
        window.setTimeout(applyWaylineDefaultLayer, 500)
        return
      }
      waylineLayerDefaulted = true
      if (waylineMapLayer.value !== 'satellite') {
        setWaylineMapLayer('satellite')
      }
    }
    watch(isWaylineRoute, () => applyWaylineDefaultLayer(), { immediate: true })

    function stopRangingTool () {
      if (rangingTool) {
        rangingTool.turnOff()
      }
      rangingToolActive.value = false
    }

    function toggleRangingTool () {
      const AMap = root?.$aMap
      const map = root?.$map
      if (!AMap || !map) return
      if (!rangingTool) {
        rangingTool = new AMap.RangingTool(map)
      }
      if (rangingToolActive.value) {
        stopRangingTool()
        return
      }
      if (mouseMode.value) draw('off', false)
      rangingTool.turnOn()
      rangingToolActive.value = true
    }

    watch(() => planningState.active, active => {
      if (active) stopRangingTool()
    })

    function updateFlightPositionFromOsd (sn: string, osd: any, gcjLng: number, gcjLat: number) {
      const trackingSn = planningState.flightPosition?.aircraftSn || planningState.aircraftSn
      if (!trackingSn) return
      if (trackingSn && sn !== trackingSn) return
      if (!Number.isFinite(gcjLng) || !Number.isFinite(gcjLat) || gcjLng === 0 || gcjLat === 0) return
      setFlightPositionFromWgs(sn, osd?.longitude, osd?.latitude, {
        height: Number(osd?.height),
        updatedAt: Date.now(),
        currentWaypointIndex: planningState.flightPosition?.currentWaypointIndex,
        totalWaypoints: planningState.flightPosition?.totalWaypoints,
      })
    }

    function updateFlightPositionOverlay () {
      const AMap = root?.$aMap
      const map = root?.$map
      const position = planningState.flightPosition
      if (!position) {
        clearFlightPositionOverlay()
        return
      }
      if (!AMap || !map) return
      const lngLat = [position.gcjLng, position.gcjLat]
      const lastTrackPoint = flightTrackPath[flightTrackPath.length - 1]
      const jumpLng = lastTrackPoint ? Math.abs(Number(lastTrackPoint[0]) - position.gcjLng) : 0
      const jumpLat = lastTrackPoint ? Math.abs(Number(lastTrackPoint[1]) - position.gcjLat) : 0
      if (flightTrackAircraftSn !== position.aircraftSn || jumpLng > 0.003 || jumpLat > 0.003) {
        flightTrackPath.length = 0
        flightTrackAircraftSn = position.aircraftSn
        if (flightTrackPolyline) {
          map.remove(flightTrackPolyline)
          flightTrackPolyline = null
        }
      }
      const label = position.currentWaypointIndex != null && position.totalWaypoints != null
        ? `${position.currentWaypointIndex + 1}/${position.totalWaypoints}`
        : ''
      if (!flightPositionMarker) {
        flightPositionMarker = new AMap.Marker({
          position: lngLat,
          content: flightPositionContent(label),
          anchor: 'center',
          zIndex: 120,
        })
        map.add(flightPositionMarker)
      } else {
        flightPositionMarker.setPosition(lngLat)
        flightPositionMarker.setContent(flightPositionContent(label))
      }
      flightTrackPath.push(lngLat)
      if (flightTrackPath.length > 600) flightTrackPath.shift()
      if (!flightTrackPolyline) {
        flightTrackPolyline = new AMap.Polyline({
          path: flightTrackPath,
          strokeColor: '#13c2c2',
          strokeWeight: 4,
          strokeOpacity: 0.85,
        })
        map.add(flightTrackPolyline)
      } else {
        flightTrackPolyline.setPath(flightTrackPath)
      }
      // 进入页面时请求过居中、但当时还没有飞机位置：现在位置到了，居中一次。
      if (pendingAircraftRecenter) {
        pendingAircraftRecenter = false
        setAircraftView(position)
      }
    }

    function fitPlanningPreviewToMap () {
      const map = root?.$map
      if (!map || planningState.waypoints.length > 0 || planningState.previewWaypoints.length === 0) return
      const overlays = planningPolyline ? [...planningMarkers, planningPolyline] : [...planningMarkers]
      if (overlays.length === 0 || typeof map.setFitView !== 'function') return
      map.setFitView(overlays, false, [80, 80, 80, 80], 17)
    }

    function bindPlanningClick () {
      const map = root?.$map
      if (!map || planningClickBound) return
      map.on('click', onPlanningMapClick)
      planningClickBound = true
    }

    function unbindPlanningClick () {
      const map = root?.$map
      if (!map || !planningClickBound) return
      map.off('click', onPlanningMapClick)
      planningClickBound = false
    }

    watch(() => planningState.active, (active) => {
      if (active) {
        // The native draw tool also claims map clicks; disable it so the two
        // modes don't compete for the same input.
        if (mouseMode.value) draw('off', false)
        bindPlanningClick()
      } else {
        unbindPlanningClick()
      }
    })

    watch(
      () => `${planningState.selectedWaypointId}|${renderPlanningWaypoints.value.map(w => `${w.id}:${w.gcjLng}:${w.gcjLat}:${w.height}`).join('|')}`,
      () => rebuildPlanningOverlays()
    )

    watch(
      () => planningState.flightPosition
        ? `${planningState.flightPosition.aircraftSn}:${planningState.flightPosition.gcjLng}:${planningState.flightPosition.gcjLat}:${planningState.flightPosition.currentWaypointIndex}:${planningState.flightPosition.updatedAt}`
        : '',
      () => updateFlightPositionOverlay()
    )

    // 进入航线页面时 wayline.vue 会自增 recenterAircraftToken：
    // 若此刻已有飞机位置则立即居中，否则挂起，等位置到达后由 updateFlightPositionOverlay 居中一次。
    watch(() => planningState.recenterAircraftToken, () => {
      if (planningState.flightPosition) {
        setAircraftView(planningState.flightPosition)
      } else {
        pendingAircraftRecenter = true
      }
    })

    function getDrawCallback ({ obj }: { obj : any }) {
      if (state.isFlightArea) {
        getDrawFlightAreaCallback(obj)
        return
      }
      switch (state.currentType) {
        case MapDoodleEnum.PIN:
          postPinPositionResource(obj)
          break
        case MapDoodleEnum.POLYLINE:
          postPolylineResource(obj)
          break
        case MapDoodleEnum.POLYGON:
          postPolygonResource(obj)
          break
        default:
          break
      }
    }
    async function postPinPositionResource (obj) {
      const req = getPinPositionResource(obj)
      setLayers(req)
      const coordinates = req.resource.content.geometry.coordinates
      updateCoordinates('gcj02-wgs84', req);
      (req.resource.content.geometry.coordinates as GeojsonCoordinate).push((coordinates as GeojsonCoordinate)[2])
      const result = await postElementsReq(shareId.value, req)
      obj.setExtData({ id: req.id, name: req.name })
      store.state.coverMap[req.id] = [obj]
    }
    async function postPolylineResource (obj) {
      const req = getPolylineResource(obj)
      setLayers(req)
      updateCoordinates('gcj02-wgs84', req)
      const result = await postElementsReq(shareId.value, req)
      obj.setExtData({ id: req.id, name: req.name })
      store.state.coverMap[req.id] = [obj]
    }
    async function postPolygonResource (obj) {
      const req = getPoygonResource(obj)
      setLayers(req)
      updateCoordinates('gcj02-wgs84', req)
      const result = await postElementsReq(shareId.value, req)
      obj.setExtData({ id: req.id, name: req.name })
      store.state.coverMap[req.id] = [obj]
    }

    function getPinPositionResource (obj) {
      const position = obj.getPosition()
      const resource = generatePointContent(position)
      const name = obj._originOpts.title
      const id = uuidv4()
      return {
        id,
        name,
        resource
      }
    }
    function getPolylineResource (obj) {
      const path = obj.getPath()
      const resource = generateLineContent(path)
      const { name, id } = getBaseInfo(obj._opts)
      return {
        id,
        name,
        resource
      }
    }
    function getPoygonResource (obj) {
      const path = obj.getPath()
      const resource = generatePolyContent(path)
      const { name, id } = getBaseInfo(obj._opts)
      return {
        id,
        name,
        resource
      }
    }
    function getBaseInfo (obj) {
      const name = obj.title
      const id = uuidv4()
      return { name, id }
    }
    function setLayers (resource: PostElementsBody) {
      const layers = store.state.Layers
      const layer = layers.find(item => item.id.includes(shareId.value))
      // layer.id = 'private_layer' + uuidv4()
      // layer?.elements.push(resource)
      if (layer?.elements) {
        ;(layer?.elements as any[]).push(resource)
      }
      console.log('layers', layers)
      store.commit('SET_LAYER_INFO', layers)
    }
    function closeLivestreamOthers () {
      store.commit('SET_LIVESTREAM_OTHERS_VISIBLE', false)
    }
    function updateCoordinates (transformType: string, element: any) {
      const geoType = element.resource?.content.geometry.type
      const type = element.resource?.type as number
      if (element.resource) {
        if (MapElementEnum.PIN === type) {
          const coordinates = element.resource?.content.geometry
            .coordinates as GeojsonCoordinate
          if (transformType === 'wgs84-gcj02') {
            const transResult = wgs84togcj02(
              coordinates[0],
              coordinates[1]
            ) as GeojsonCoordinate
            element.resource.content.geometry.coordinates = transResult
          } else if (transformType === 'gcj02-wgs84') {
            const transResult = gcj02towgs84(
              coordinates[0],
              coordinates[1]
            ) as GeojsonCoordinate
            element.resource.content.geometry.coordinates = transResult
          }
        } else if (MapElementEnum.LINE === type) {
          const coordinates = element.resource?.content.geometry
            .coordinates as GeojsonCoordinate[]
          if (transformType === 'wgs84-gcj02') {
            coordinates.forEach((coordinate, i, arr) => {
              arr[i] = wgs84togcj02(
                coordinate[0],
                coordinate[1]
              ) as GeojsonCoordinate
            })
          } else if (transformType === 'gcj02-wgs84') {
            coordinates.forEach((coordinate, i, arr) => {
              arr[i] = gcj02towgs84(
                coordinate[0],
                coordinate[1]
              ) as GeojsonCoordinate
            })
          }
          element.resource.content.geometry.coordinates = coordinates
        } else if (MapElementEnum.POLY === type) {
          const coordinates = element.resource?.content.geometry
            .coordinates[0] as GeojsonCoordinate[]
          if (transformType === 'wgs84-gcj02') {
            coordinates.forEach((coordinate, i, arr) => {
              arr[i] = wgs84togcj02(
                coordinate[0],
                coordinate[1]
              ) as GeojsonCoordinate
            })
          } else if (transformType === 'gcj02-wgs84') {
            coordinates.forEach((coordinate, i, arr) => {
              arr[i] = gcj02towgs84(
                coordinate[0],
                coordinate[1]
              ) as GeojsonCoordinate
            })
          }
          element.resource.content.geometry.coordinates = [coordinates]
        }
      }
    }
    return {
      draw,
      mouseMode,
      drawVisible,
      livestreamOthersVisible,
      osdVisible,
      pin,
      state,
      M30,
      deviceInfo,
      EGear,
      EModeCode,
      str,
      EDockModeCode,
      dockControlPanelVisible,
      setDockControlPanelVisible,
      onCloseControlPanel,
      NetworkStateTypeEnum,
      NetworkStateQualityEnum,
      RainfallEnum,
      DroneInDockEnum,
      closeLivestreamOthers,
      qualityStyle,
      selectFlightAreaAction,
      hasFlightPosition,
      locateAircraftPosition,
      isWaylineRoute,
      waylineMapLayer,
      rangingToolActive,
      setWaylineMapLayer,
      toggleRangingTool,
    }
  }
})
</script>

<style lang="scss" scoped>

.g-map-wrapper {
  height: 100%;
  width: 100%;

  .g-action-panel {
    position: absolute;
    top: 16px;
    right: 16px;
    .g-action-item {
      width: 28px;
      height: 28px;
      background: white;
      color: $primary;
      border-radius: 2px;
      line-height: 28px;
      text-align: center;
      margin-bottom: 2px;
    }
    .g-action-item:hover {
      border: 1px solid $primary;
      border-radius: 2px;
    }
  }
  .selection {
    border: 1px solid $primary;
    border-radius: 2px;
  }

  .wayline-map-toolbox {
    position: absolute;
    top: 16px;
    right: 64px;
    z-index: 2;
    display: flex;
    align-items: center;
    gap: 4px;
    padding: 4px;
    border: 1px solid rgba(255, 255, 255, 0.18);
    border-radius: 6px;
    background: rgba(31, 31, 31, 0.76);
    box-shadow: 0 8px 22px rgba(0, 0, 0, 0.24);

    button {
      min-width: 44px;
      height: 28px;
      padding: 0 8px;
      border: 0;
      border-radius: 4px;
      background: transparent;
      color: rgba(255, 255, 255, 0.72);
      font-size: 12px;
      cursor: pointer;
    }

    button:hover,
    button.active {
      background: #1890ff;
      color: #fff;
    }
  }

  &:deep(.wayline-planning-marker) {
    position: relative;
    width: 26px;
    height: 30px;
    transform: translateY(1px);
  }

  &:deep(.wayline-planning-marker span) {
    position: relative;
    z-index: 1;
    display: flex;
    width: 24px;
    height: 24px;
    align-items: center;
    justify-content: center;
    border: 2px solid #fff;
    border-radius: 50%;
    background: #00bcd4;
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
    color: #fff;
    font-size: 12px;
    font-weight: 700;
  }

  &:deep(.wayline-planning-marker::after) {
    content: '';
    position: absolute;
    left: 9px;
    bottom: 2px;
    width: 8px;
    height: 8px;
    transform: rotate(45deg);
    border-right: 2px solid #fff;
    border-bottom: 2px solid #fff;
    background: #00bcd4;
  }

  &:deep(.wayline-planning-marker--start span),
  &:deep(.wayline-planning-marker--start::after) {
    background: #21c45d;
  }

  &:deep(.wayline-planning-marker--selected span) {
    border-color: #ffd666;
    background: #1677ff;
    transform: scale(1.12);
  }

  &:deep(.wayline-planning-marker--selected::after) {
    border-color: #ffd666;
    background: #1677ff;
  }

  // antd button 光晕
  &:deep(.ant-btn){
    &::after {
      display: none;
    }
  }
}
.osd-panel {
  position: absolute;
  margin-left: 10px;
  left: 0;
  top: 10px;
  width: 480px;
  background: #000;
  color: #fff;
  border-radius: 2px;
  opacity: 0.8;
}
.osd > div:not(.dock-control-panel) {
  margin-top: 5px;
  padding-left: 5px;
}

.circle {
  border-radius: 50%;
  width: 10px;
  height: 10px;
}

.battery-slide {
  .capacity-percent {
    background: #00ee8b;
  }
  .return-home {
    background: #ff9f0a;
  }
  .landing {
    background: #f5222d;
  }
  .white-point {
    width: 4px;
    height: 4px;
    border-radius: 50%;
    background: white;
    bottom: -0.5px;
  }
  .battery {
    background: #141414;
    color: #00ee8b;
    margin-top: -10px;
    height: 20px;
    width: auto;
    border-left: 1px solid #00ee8b;
    padding: 0 5px;
  }
}
.battery-slide > div {
  position: absolute;
  min-height: 2px;
  border-radius: 2px;
}

.liveview {
  position: absolute;
  color: #fff;
  z-index: 1;
  left: 0;
  margin-left: 10px;
  top: 10px;
  text-align: center;
  width: 800px;
  height: 720px;
  background: #232323;
}
.aircraft-follow-control {
  position: absolute;
  right: 18px;
  bottom: 82px;
  z-index: 2;
  width: 38px;
  height: 38px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  background: #fff;
  color: #1f1f1f;
  cursor: pointer;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.24);
}
.aircraft-follow-control.active {
  color: #fff;
  background: #1677ff;
  border-color: #1677ff;
}
.aircraft-follow-control:disabled {
  color: #bfbfbf;
  cursor: not-allowed;
  background: #f5f5f5;
}
:deep(.flight-position-marker) {
  position: relative;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: #13c2c2;
  border: 3px solid #fff;
  box-shadow: 0 0 0 3px rgba(19, 194, 194, 0.28), 0 2px 8px rgba(0, 0, 0, 0.35);
  color: #fff;
  font-size: 18px;
  line-height: 28px;
  text-align: center;
}
:deep(.flight-position-marker em) {
  position: absolute;
  right: -12px;
  bottom: -7px;
  min-width: 22px;
  height: 16px;
  padding: 0 4px;
  border-radius: 8px;
  background: #1f1f1f;
  color: #fff;
  font-size: 10px;
  font-style: normal;
  font-weight: 700;
  line-height: 16px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.32);
}
</style>
