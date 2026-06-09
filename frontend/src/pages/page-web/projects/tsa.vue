<template>
  <div class="project-tsa-wrapper ">
    <div>
      <a-row>
        <a-col :span="1"></a-col>
        <a-col :span="11">当前用户</a-col>
        <a-col :span="11" align="right" style="font-weight: 700">{{ username }}</a-col>
        <a-col :span="1"></a-col>
      </a-row>
    </div>
    <div class="scrollbar" :style="{ height: scorllHeight + 'px'}">
      <a-collapse :bordered="false" expandIconPosition="right" accordion style="background: #232323;">
        <a-collapse-panel key="fc100-delivery" header="投放设备" style="border-bottom: 1px solid #4f4f4f;">
          <div class="fc100-cloud-panel">
            <div class="fc100-cloud-header">
              <div class="fc100-cloud-title">FC100 云端设备</div>
              <a-button
                size="small"
                type="primary"
                :loading="fc100DeliveryFormState.deviceLoading"
                @click="handleFc100RefreshDevices">
                刷新设备
              </a-button>
            </div>
        <div v-if="fc100DeliveryFormState.devices.length === 0" class="fc100-cloud-empty">
          暂无 FC100 云端设备
        </div>
        <div v-else class="fc100-device-list">
          <div
            v-if="selectedFc100DeliveryDevice"
            :key="selectedFc100DeliveryDevice.deviceSn"
            class="fc100-device-item"
            :class="{ 'fc100-device-item--active': true }"
            @click="handleFc100SelectDevice(selectedFc100DeliveryDevice.deviceSn)">
            <div class="fc100-device-main">
              <div class="fc100-device-sn">{{ formatFc100DeviceModel(selectedFc100DeliveryDevice) }}</div>
              <div class="fc100-device-meta">
                {{ selectedFc100DeliveryDevice.deviceSn }} · {{ selectedFc100DeliveryDevice.bindStatus || '--' }}
              </div>
            </div>
            <a-dropdown
              v-if="selectableFc100DeliveryDevices.length > 0"
              class="fc100-device-card-dropdown delivery-device-card-dropdown"
              :trigger="['click']">
              <button class="fc100-device-card-dropdown__trigger" type="button" @click.stop>
                <DownOutlined />
              </button>
              <template #overlay>
                <a-menu @click="({ key }) => handleFc100SelectDevice(String(key))">
                  <a-menu-item
                    v-for="device in selectableFc100DeliveryDevices"
                    :key="device.deviceSn">
                    <div class="fc100-device-menu-item">
                      <span>{{ formatFc100DeviceModel(device) }}</span>
                      <small>{{ device.deviceSn }} · {{ formatDeviceOnlineLabel(device) }}</small>
                    </div>
                  </a-menu-item>
                </a-menu>
              </template>
            </a-dropdown>
          </div>
        </div>
        <div v-if="fc100DeliveryFormState.selectedDeviceProps" class="fc100-device-props">
          <div class="fc100-device-prop">
            <span>连接状态</span>
            <strong
              class="fc100-link-status"
              :class="getFc100ConnectionStatusClass(fc100DeliveryFormState.selectedDeviceProps.onlineStatus)">
              {{ formatFc100ConnectionLabel(fc100DeliveryFormState.selectedDeviceProps.onlineStatus) }}
            </strong>
          </div>
          <div class="fc100-device-prop">
            <span>电量</span>
            <strong>{{ formatFc100Value(fc100DeliveryFormState.selectedDeviceProps.batteryPercent, '%') }}</strong>
          </div>
          <div class="fc100-device-prop">
            <span>RTK</span>
            <strong>{{ fc100DeliveryFormState.selectedDeviceProps.rtkStatus || '--' }}</strong>
          </div>
          <div class="fc100-device-prop">
            <span>飞行模式</span>
            <strong>{{ formatFc100Value(fc100DeliveryFormState.selectedDeviceProps.aircraftMode) }}</strong>
          </div>
          <div class="fc100-device-prop">
            <span>飞行中</span>
            <strong>{{ formatFc100Boolean(fc100DeliveryFormState.selectedDeviceProps.flying) }}</strong>
          </div>
          <div class="fc100-device-prop">
            <span>经度</span>
            <strong>{{ formatFc100Value(fc100DeliveryFormState.selectedDeviceProps.longitude) }}</strong>
          </div>
          <div class="fc100-device-prop">
            <span>纬度</span>
            <strong>{{ formatFc100Value(fc100DeliveryFormState.selectedDeviceProps.latitude) }}</strong>
          </div>
          <div class="fc100-device-prop">
            <span>高度</span>
            <strong>{{ formatFc100Value(fc100DeliveryFormState.selectedDeviceProps.altitude, ' m') }}</strong>
          </div>
          <div class="fc100-device-prop">
            <span>水平速度</span>
            <strong>{{ formatFc100Value(fc100DeliveryFormState.selectedDeviceProps.horizontalSpeed, ' m/s') }}</strong>
          </div>
          <div class="fc100-device-prop">
            <span>垂直速度</span>
            <strong>{{ formatFc100Value(fc100DeliveryFormState.selectedDeviceProps.verticalSpeed, ' m/s') }}</strong>
          </div>
          <div class="fc100-device-prop">
            <span>返航距离</span>
            <strong>{{ formatFc100Value(fc100DeliveryFormState.selectedDeviceProps.homeDistance, ' m') }}</strong>
          </div>
          <div class="fc100-device-prop">
            <span>风速</span>
            <strong>{{ formatFc100Value(fc100DeliveryFormState.selectedDeviceProps.windSpeed, ' m/s') }}</strong>
          </div>
          <div class="fc100-device-prop fc100-device-prop--wide">
            <span>更新时间</span>
            <strong>{{ formatFc100Time(fc100DeliveryFormState.selectedDeviceProps.osdTimestamp) }}</strong>
          </div>
        </div>
          </div>
        </a-collapse-panel>
      </a-collapse>
      <a-collapse :bordered="false" expandIconPosition="right" accordion style="background: #232323;">
        <a-collapse-panel :key="EDeviceTypeName.Dock" header="机场设备" style="border-bottom: 1px solid #4f4f4f;">
          <div v-if="onlineDocks.data.length === 0" style="height: 150px; color: white;">
            <a-empty :image="noData" :image-style="{ height: '60px' }" />
          </div>
          <div v-else class="fz12" style="color: white;">
            <div v-for="dock in onlineDocks.data" :key="dock.sn" style="background: #3c3c3c; height: 90px; width: 250px; margin-bottom: 10px;">
              <div style="border-radius: 2px; height: 100%; width: 100%;" class="flex-row flex-justify-between flex-align-center">
                <div style="float: left; padding: 0px 5px 8px 8px; width: 88%">
                  <div style="width: 80%; height: 30px; line-height: 30px; font-size: 16px;">
                    <a-tooltip :title="`${dock.gateway.callsign} - ${dock.callsign ?? 'No Drone'}`">
                      <div class="text-hidden" style="max-width: 200px;">{{ dock.gateway.callsign }} - {{ dock.callsign ?? '未挂载飞机' }}</div>
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
        <a-collapse-panel :key="EDeviceTypeName.Aircraft" header="监测设备" style="border-bottom: 1px solid #4f4f4f;">
          <div v-if="onlineDevices.data.length === 0" style="height: 150px; color: white;">
            <a-empty :image="noData" :image-style="{ height: '60px' }" />
          </div>
          <div v-else class="fz12" style="color: white;">
            <div v-for="device in selectedMonitoringDevices" :key="device.sn" class="aircraft-card">
              <div class="fc100-device-list monitoring-device-list">
                <div
                  class="fc100-device-item fc100-device-item--active"
                  @click="handleMonitoringSelectDevice(device.sn)">
                  <div class="fc100-device-main">
                    <div class="fc100-device-sn">{{ formatMonitoringDeviceModel(device) }}</div>
                    <div class="fc100-device-meta">
                      {{ device.sn }} · {{ formatDeviceOnlineLabel(device) }}
                    </div>
                  </div>
                  <a-dropdown
                    v-if="selectableMonitoringDevices.length > 0"
                    class="fc100-device-card-dropdown monitoring-device-card-dropdown"
                    :trigger="['click']">
                    <button class="fc100-device-card-dropdown__trigger" type="button" @click.stop>
                      <DownOutlined />
                    </button>
                    <template #overlay>
                      <a-menu @click="({ key }) => handleMonitoringSelectDevice(String(key))">
                        <a-menu-item
                          v-for="option in selectableMonitoringDevices"
                          :key="option.sn">
                          <div class="fc100-device-menu-item">
                            <span>{{ formatMonitoringDeviceModel(option) }}</span>
                            <small>{{ option.sn }} · {{ formatDeviceOnlineLabel(option) }}</small>
                          </div>
                        </a-menu-item>
                      </a-menu>
                    </template>
                  </a-dropdown>
                </div>
              </div>
              <div class="fc100-device-props monitoring-device-props">
                <div class="fc100-device-prop">
                  <span>连接状态</span>
                  <strong
                    class="fc100-link-status"
                    :class="getMonitoringConnectionStatusClass(device)">
                    {{ formatMonitoringConnectionLabel(device) }}
                  </strong>
                </div>
                <div class="fc100-device-prop">
                  <span>电量</span>
                  <strong>{{ getBatteryPercent(device.sn) }}</strong>
                </div>
                <div class="fc100-device-prop">
                  <span>RTK</span>
                  <strong>{{ formatMonitoringRtkStatus(device.sn) }}</strong>
                </div>
                <div class="fc100-device-prop">
                  <span>飞行模式</span>
                  <strong>{{ formatMonitoringFlightMode(device.sn) }}</strong>
                </div>
                <div class="fc100-device-prop">
                  <span>飞行中</span>
                  <strong>{{ formatMonitoringFlying(device.sn) }}</strong>
                </div>
                <div class="fc100-device-prop">
                  <span>经度</span>
                  <strong>{{ formatMonitoringOsdValue(device.sn, 'longitude') }}</strong>
                </div>
                <div class="fc100-device-prop">
                  <span>纬度</span>
                  <strong>{{ formatMonitoringOsdValue(device.sn, 'latitude') }}</strong>
                </div>
                <div class="fc100-device-prop">
                  <span>高度</span>
                  <strong>{{ formatMonitoringOsdValue(device.sn, 'height', ' m') }}</strong>
                </div>
                <div class="fc100-device-prop">
                  <span>水平速度</span>
                  <strong>{{ formatMonitoringOsdValue(device.sn, 'horizontal_speed', ' m/s') }}</strong>
                </div>
                <div class="fc100-device-prop">
                  <span>垂直速度</span>
                  <strong>{{ formatMonitoringOsdValue(device.sn, 'vertical_speed', ' m/s') }}</strong>
                </div>
                <div class="fc100-device-prop">
                  <span>返航距离</span>
                  <strong>{{ formatMonitoringOsdValue(device.sn, 'home_distance', ' m') }}</strong>
                </div>
                <div class="fc100-device-prop">
                  <span>风速</span>
                  <strong>{{ formatMonitoringOsdValue(device.sn, 'wind_speed', ' m/s') }}</strong>
                </div>
                <div class="fc100-device-prop fc100-device-prop--wide">
                  <span>更新时间</span>
                  <strong>{{ formatMonitoringUpdateTime(device.sn) }}</strong>
                </div>
              </div>
              <div class="aircraft-action-panel">
                <div class="aircraft-osd-title">飞行控制</div>
                <div class="aircraft-action-tips">
                  {{ hasActiveDrcControl(device) && !hasMsdkCommandCapability(device, 'takeoff')
                    ? 'MSDK 控制通道已就绪；起飞/航点飞行执行器尚未接入，当前不会发送起飞指令。'
                    : hasActiveDrcControl(device) && hasMsdkCommandCapability(device, 'takeoff') && !hasMsdkCommandCapability(device, 'flyToPoint')
                    ? 'MSDK 基础飞控已接入：可执行原生起飞和返航；航点飞行暂未接入。'
                    : hasActiveDrcControl(device)
                    ? 'MSDK 控制通道已就绪，可执行已接入的遥控和飞行指令。'
                    : hasRemoteSession(device) && !remoteControlState.cloudControlAuthorized
                      ? 'MSDK 控制通道仍在，但飞行授权已释放，请重新进入控制。'
                      : hasRemoteSession(device)
                      ? 'MSDK 控制通道仍在，但遥控输入当前不可用。'
                      : '请先进入遥控模式，再发送官方起飞和其他飞行指令。' }}
                </div>
                <div class="aircraft-action-section">
                  <div class="aircraft-action-section-title">远程控制</div>
                  <div class="aircraft-action-grid aircraft-action-grid--2">
                    <a-button
                      size="small"
                      type="primary"
                      class="aircraft-action-btn aircraft-action-btn--primary"
                      :loading="actionLoading[device.gateway.sn] === 'connect'"
                      :disabled="hasRemoteSession(device) && remoteControlState.cloudControlAuthorized"
                      @click="connectRemoteControl(device)">
                      {{ hasRemoteSession(device) && !remoteControlState.cloudControlAuthorized ? '重新进入控制' : '进入控制' }}
                    </a-button>
                    <a-button
                      size="small"
                      class="aircraft-action-btn"
                      :loading="actionLoading[device.gateway.sn] === 'disconnect'"
                      :disabled="!hasRemoteSession(device)"
                      @click="disconnectRemoteControl('user_click_exit')">
                      退出遥控
                    </a-button>
                  </div>
                </div>

                <div class="aircraft-action-section">
                  <div class="aircraft-action-section-title">基础飞行</div>
                  <div class="aircraft-action-grid aircraft-action-grid--3">
                    <a-button
                      size="small"
                      type="primary"
                      class="aircraft-action-btn aircraft-action-btn--primary"
                      :loading="actionLoading[device.gateway.sn] === 'takeoff'"
                      :disabled="!canTakeoff(device)"
                      @click="handleTakeoff(device)">
                      起飞
                    </a-button>
                    <a-button
                      size="small"
                      class="aircraft-action-btn"
                      :loading="actionLoading[device.gateway.sn] === 'land'"
                      :disabled="!canLand(device)"
                      @click="handleLanding(device)">
                      降落
                    </a-button>
                    <a-button
                      size="small"
                      class="aircraft-action-btn"
                      :loading="actionLoading[device.gateway.sn] === 'hover'"
                      :disabled="!canHover(device)"
                      @click="handleAxisControl(device, 'hover')">
                      悬停
                    </a-button>
                  </div>
                </div>

                <div class="aircraft-action-section">
                  <div class="aircraft-action-section-title">位移控制</div>
                  <div class="aircraft-action-grid aircraft-action-grid--3">
                    <a-popover
                      trigger="click"
                      placement="left"
                      :visible="axisDistanceFormState.visible && axisDistanceFormState.gatewaySn === device.gateway.sn && axisDistanceFormState.direction === 'up'"
                      @visibleChange="(v) => { if (!v) closeAxisDistanceControl() }">
                      <template #content>
                        <div style="width: 220px;">
                          <div style="margin-bottom: 6px; font-weight: 700;">上升距离</div>
                          <div style="margin-bottom: 4px;">距离（米）</div>
                          <a-input-number
                            v-model:value="axisDistanceFormState.distanceMeters"
                            :min="AXIS_DISTANCE_MIN_METERS"
                            :max="AXIS_DISTANCE_MAX_METERS"
                            :step="1"
                            style="width: 100%;" />
                          <div style="margin-top: 8px; display: flex; gap: 6px; justify-content: flex-end;">
                            <a-button size="small" @click="closeAxisDistanceControl">取消</a-button>
                            <a-button size="small" type="primary" @click="submitAxisDistanceControl">发送</a-button>
                          </div>
                        </div>
                      </template>
                      <a-button
                        size="small"
                        class="aircraft-action-btn"
                        :loading="actionLoading[device.gateway.sn] === 'up'"
                        :disabled="!canVirtualStick(device)"
                        @click="openAxisDistanceControl(device, 'up')">
                        <span class="aircraft-action-btn-inner"><ArrowUpOutlined /><span>上升</span></span>
                      </a-button>
                    </a-popover>
                    <a-popover
                      trigger="click"
                      placement="left"
                      :visible="axisDistanceFormState.visible && axisDistanceFormState.gatewaySn === device.gateway.sn && axisDistanceFormState.direction === 'north'"
                      @visibleChange="(v) => { if (!v) closeAxisDistanceControl() }">
                      <template #content>
                        <div style="width: 220px;">
                          <div style="margin-bottom: 6px; font-weight: 700;">向前移动距离</div>
                          <div style="margin-bottom: 4px;">距离（米）</div>
                          <a-input-number
                            v-model:value="axisDistanceFormState.distanceMeters"
                            :min="AXIS_DISTANCE_MIN_METERS"
                            :max="AXIS_DISTANCE_MAX_METERS"
                            :step="1"
                            style="width: 100%;" />
                          <div style="margin-top: 8px; display: flex; gap: 6px; justify-content: flex-end;">
                            <a-button size="small" @click="closeAxisDistanceControl">取消</a-button>
                            <a-button size="small" type="primary" @click="submitAxisDistanceControl">发送</a-button>
                          </div>
                        </div>
                      </template>
                      <a-button
                        size="small"
                        class="aircraft-action-btn"
                        :loading="actionLoading[device.gateway.sn] === 'north'"
                        :disabled="!canVirtualStick(device)"
                        @click="openAxisDistanceControl(device, 'north')">
                        <span class="aircraft-action-btn-inner"><UpOutlined /><span>向前</span></span>
                      </a-button>
                    </a-popover>
                    <a-popover
                      trigger="click"
                      placement="left"
                      :visible="axisDistanceFormState.visible && axisDistanceFormState.gatewaySn === device.gateway.sn && axisDistanceFormState.direction === 'down'"
                      @visibleChange="(v) => { if (!v) closeAxisDistanceControl() }">
                      <template #content>
                        <div style="width: 220px;">
                          <div style="margin-bottom: 6px; font-weight: 700;">下降距离</div>
                          <div style="margin-bottom: 4px;">距离（米）</div>
                          <a-input-number
                            v-model:value="axisDistanceFormState.distanceMeters"
                            :min="AXIS_DISTANCE_MIN_METERS"
                            :max="AXIS_DISTANCE_MAX_METERS"
                            :step="1"
                            style="width: 100%;" />
                          <div style="margin-top: 8px; display: flex; gap: 6px; justify-content: flex-end;">
                            <a-button size="small" @click="closeAxisDistanceControl">取消</a-button>
                            <a-button size="small" type="primary" @click="submitAxisDistanceControl">发送</a-button>
                          </div>
                        </div>
                      </template>
                      <a-button
                        size="small"
                        class="aircraft-action-btn"
                        :loading="actionLoading[device.gateway.sn] === 'down'"
                        :disabled="!canVirtualStick(device)"
                        @click="openAxisDistanceControl(device, 'down')">
                        <span class="aircraft-action-btn-inner"><ArrowDownOutlined /><span>下降</span></span>
                      </a-button>
                    </a-popover>
                    <a-popover
                      trigger="click"
                      placement="left"
                      :visible="axisDistanceFormState.visible && axisDistanceFormState.gatewaySn === device.gateway.sn && axisDistanceFormState.direction === 'west'"
                      @visibleChange="(v) => { if (!v) closeAxisDistanceControl() }">
                      <template #content>
                        <div style="width: 220px;">
                          <div style="margin-bottom: 6px; font-weight: 700;">向左移动距离</div>
                          <div style="margin-bottom: 4px;">距离（米）</div>
                          <a-input-number
                            v-model:value="axisDistanceFormState.distanceMeters"
                            :min="AXIS_DISTANCE_MIN_METERS"
                            :max="AXIS_DISTANCE_MAX_METERS"
                            :step="1"
                            style="width: 100%;" />
                          <div style="margin-top: 8px; display: flex; gap: 6px; justify-content: flex-end;">
                            <a-button size="small" @click="closeAxisDistanceControl">取消</a-button>
                            <a-button size="small" type="primary" @click="submitAxisDistanceControl">发送</a-button>
                          </div>
                        </div>
                      </template>
                      <a-button
                        size="small"
                        class="aircraft-action-btn"
                        :loading="actionLoading[device.gateway.sn] === 'west'"
                        :disabled="!canVirtualStick(device)"
                        @click="openAxisDistanceControl(device, 'west')">
                        <span class="aircraft-action-btn-inner"><LeftOutlined /><span>向左</span></span>
                      </a-button>
                    </a-popover>
                    <a-popover
                      trigger="click"
                      placement="left"
                      :visible="axisDistanceFormState.visible && axisDistanceFormState.gatewaySn === device.gateway.sn && axisDistanceFormState.direction === 'south'"
                      @visibleChange="(v) => { if (!v) closeAxisDistanceControl() }">
                      <template #content>
                        <div style="width: 220px;">
                          <div style="margin-bottom: 6px; font-weight: 700;">向后移动距离</div>
                          <div style="margin-bottom: 4px;">距离（米）</div>
                          <a-input-number
                            v-model:value="axisDistanceFormState.distanceMeters"
                            :min="AXIS_DISTANCE_MIN_METERS"
                            :max="AXIS_DISTANCE_MAX_METERS"
                            :step="1"
                            style="width: 100%;" />
                          <div style="margin-top: 8px; display: flex; gap: 6px; justify-content: flex-end;">
                            <a-button size="small" @click="closeAxisDistanceControl">取消</a-button>
                            <a-button size="small" type="primary" @click="submitAxisDistanceControl">发送</a-button>
                          </div>
                        </div>
                      </template>
                      <a-button
                        size="small"
                        class="aircraft-action-btn"
                        :loading="actionLoading[device.gateway.sn] === 'south'"
                        :disabled="!canVirtualStick(device)"
                        @click="openAxisDistanceControl(device, 'south')">
                        <span class="aircraft-action-btn-inner"><DownOutlined /><span>向后</span></span>
                      </a-button>
                    </a-popover>
                    <a-popover
                      trigger="click"
                      placement="left"
                      :visible="axisDistanceFormState.visible && axisDistanceFormState.gatewaySn === device.gateway.sn && axisDistanceFormState.direction === 'east'"
                      @visibleChange="(v) => { if (!v) closeAxisDistanceControl() }">
                      <template #content>
                        <div style="width: 220px;">
                          <div style="margin-bottom: 6px; font-weight: 700;">向右移动距离</div>
                          <div style="margin-bottom: 4px;">距离（米）</div>
                          <a-input-number
                            v-model:value="axisDistanceFormState.distanceMeters"
                            :min="AXIS_DISTANCE_MIN_METERS"
                            :max="AXIS_DISTANCE_MAX_METERS"
                            :step="1"
                            style="width: 100%;" />
                          <div style="margin-top: 8px; display: flex; gap: 6px; justify-content: flex-end;">
                            <a-button size="small" @click="closeAxisDistanceControl">取消</a-button>
                            <a-button size="small" type="primary" @click="submitAxisDistanceControl">发送</a-button>
                          </div>
                        </div>
                      </template>
                      <a-button
                        size="small"
                        class="aircraft-action-btn"
                        :loading="actionLoading[device.gateway.sn] === 'east'"
                        :disabled="!canVirtualStick(device)"
                        @click="openAxisDistanceControl(device, 'east')">
                        <span class="aircraft-action-btn-inner"><RightOutlined /><span>向右</span></span>
                      </a-button>
                    </a-popover>
                  </div>
                </div>

                <div class="aircraft-action-section">
                  <div class="aircraft-action-section-title">任务控制</div>
                  <div class="aircraft-action-grid aircraft-action-grid--2">
                    <a-button
                      size="small"
                      danger
                      class="aircraft-action-btn aircraft-action-btn--danger aircraft-action-btn--danger-strong"
                      :loading="actionLoading[device.gateway.sn] === 'stop'"
                      :disabled="!canEmergencyStop(device)"
                      @click="sendEmergencyStop(device)">
                      急停
                    </a-button>
                    <a-button
                      size="small"
                      danger
                      class="aircraft-action-btn aircraft-action-btn--danger aircraft-action-btn--danger-strong"
                      :loading="actionLoading[device.gateway.sn] === 'flyStop'"
                      :disabled="!canEmergencyStop(device)"
                      @click="handleStopFlyToPoint(device)">
                      停止飞行
                    </a-button>
                    <a-button
                      size="small"
                      danger
                      class="aircraft-action-btn aircraft-action-btn--danger"
                      :loading="actionLoading[device.gateway.sn] === 'returnHome'"
                      :disabled="!canReturnHome(device)"
                      @click="handleReturnHome(device)">
                      返航
                    </a-button>
                    <a-button
                      size="small"
                      class="aircraft-action-btn"
                      :loading="actionLoading[device.gateway.sn] === 'cancelReturnHome'"
                      :disabled="!canCancelReturnHome(device)"
                      @click="handleCancelReturnHome(device)">
                      取消返航
                    </a-button>
                    <a-popover
                      trigger="click"
                      placement="left"
                      :visible="flyToPointFormState.visible && flyToPointFormState.gatewaySn === device.gateway.sn"
                      @visibleChange="(v) => { if (!v) closeFlyToPointManual() }">
                      <template #content>
                        <div style="width: 220px;">
                          <div style="margin-bottom: 6px; font-weight: 700;">手动飞向目标点</div>
                          <div style="margin-bottom: 4px;">纬度</div>
                          <a-input-number
                            v-model:value="flyToPointFormState.latitude"
                            :step="0.00001"
                            style="width: 100%;" />
                          <div style="margin: 6px 0 4px;">经度</div>
                          <a-input-number
                            v-model:value="flyToPointFormState.longitude"
                            :step="0.00001"
                            style="width: 100%;" />
                          <div style="margin: 6px 0 4px;">高度（米，相对起飞点）</div>
                          <a-input-number
                            v-model:value="flyToPointFormState.height"
                            :min="MIN_AIRBORNE_HEIGHT_M"
                            :step="1"
                            style="width: 100%;" />
                          <div style="margin: 6px 0 4px;">最大速度（米/秒，2-15）</div>
                          <a-input-number
                            v-model:value="flyToPointFormState.maxSpeed"
                            :min="2"
                            :max="15"
                            :step="1"
                            style="width: 100%;" />
                          <div style="margin-top: 8px; display: flex; gap: 6px; justify-content: flex-end;">
                            <a-button size="small" @click="closeFlyToPointManual">取消</a-button>
                            <a-button size="small" type="primary" @click="submitFlyToPointManual">发送</a-button>
                          </div>
                        </div>
                      </template>
                      <a-button
                        size="small"
                      class="aircraft-action-btn"
                      :loading="actionLoading[device.gateway.sn] === 'flyManual'"
                      :disabled="!canFlyToPoint(device)"
                      @click="openFlyToPointManual(device)">
                      飞向目标点
                    </a-button>
                  </a-popover>
                  </div>
                </div>

                <div class="aircraft-action-section fc100-delivery-section">
                  <div class="aircraft-action-section-title">FC100 火情任务</div>
                  <div class="fc100-delivery-form">
                    <a-input
                      v-model:value="fc100DeliveryFormState.missionNo"
                      size="small"
                      placeholder="任务编号"
                      class="fc100-delivery-input" />
                  </div>
                  <div class="aircraft-action-grid aircraft-action-grid--2">
                    <a-button
                      size="small"
                      type="primary"
                      class="aircraft-action-btn aircraft-action-btn--primary"
                      :loading="actionLoading[device.gateway.sn] === 'fc100CreateTask'"
                      :disabled="!fc100DeliveryFormState.missionNo"
                      @click="handleFc100CreateTask(device)">
                      创建任务
                    </a-button>
                    <a-button
                      size="small"
                      type="primary"
                      class="aircraft-action-btn aircraft-action-btn--primary"
                      :loading="actionLoading[device.gateway.sn] === 'fc100StartTask'"
                      :disabled="!fc100DeliveryFormState.missionNo"
                      @click="handleFc100StartTask(device)">
                      开始航线
                    </a-button>
                    <a-button
                      size="small"
                      class="aircraft-action-btn"
                      :loading="actionLoading[device.gateway.sn] === 'fc100TaskStatus'"
                      :disabled="!fc100DeliveryFormState.missionNo"
                      @click="handleFc100TaskStatus(device)">
                      任务状态
                    </a-button>
                    <a-button
                      size="small"
                      type="primary"
                      class="aircraft-action-btn aircraft-action-btn--primary"
                      :loading="actionLoading[device.gateway.sn] === 'fc100ReleaseHook'"
                      :disabled="!fc100DeliveryFormState.missionNo"
                      @click="handleFc100ReleaseHook(device)">
                      开钩投放
                    </a-button>
                    <a-button
                      size="small"
                      danger
                      class="aircraft-action-btn aircraft-action-btn--danger aircraft-action-btn--danger-strong"
                      :loading="actionLoading[device.gateway.sn] === 'fc100EmergencyStop'"
                      :disabled="!fc100DeliveryFormState.missionNo"
                      @click="handleFc100EmergencyStop(device)">
                      FC100 急停
                    </a-button>
                    <a-button
                      size="small"
                      danger
                      class="aircraft-action-btn aircraft-action-btn--danger"
                      :loading="actionLoading[device.gateway.sn] === 'fc100ReturnHome'"
                      :disabled="!fc100DeliveryFormState.missionNo"
                      @click="handleFc100ReturnHome(device)">
                      FC100 返航
                    </a-button>
                    <a-button
                      size="small"
                      class="aircraft-action-btn"
                      :loading="actionLoading[device.gateway.sn] === 'fc100Land'"
                      :disabled="!fc100DeliveryFormState.missionNo"
                      @click="handleFc100Land(device)">
                      FC100 降落
                    </a-button>
                    <a-button
                      size="small"
                      class="aircraft-action-btn"
                      :loading="actionLoading[device.gateway.sn] === 'fc100CommandStatus'"
                      :disabled="!fc100DeliveryFormState.missionNo"
                      @click="handleFc100CommandStatus(device)">
                      命令状态
                    </a-button>
                  </div>
                  <div v-if="fc100DeliveryFormState.lastResult" class="fc100-delivery-result">
                    {{ fc100DeliveryFormState.lastResult }}
                  </div>
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
import { computed, h, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { EDeviceTypeName, ELocalStorageKey, EBizCode } from '/@/types'
import noData from '/@/assets/icons/no-data.png'
import { OnlineDevice, EModeCode, OSDVisible, EDockModeCode, DeviceOsd, DrcStateEnum } from '/@/types/device'
import { useMyStore } from '/@/store'
import { getUnreadDeviceHms, updateDeviceHms } from '/@/api/manage'
import { listMsdkDevices, sendMsdkCommand, type MsdkDeviceState } from '/@/api/msdk-device'
import { deliveryApi, type DeliveryDeviceDTO, type DeliveryDeviceProperties } from '/@/api/fire/delivery'
import {
  RocketOutlined,
  EyeInvisibleOutlined,
  EyeOutlined,
  RobotOutlined,
  DoubleRightOutlined,
  ArrowUpOutlined,
  ArrowDownOutlined,
  LeftOutlined,
  RightOutlined,
  UpOutlined,
  DownOutlined,
} from '@ant-design/icons-vue'
import { EHmsLevel } from '/@/types/enums'
import { message, Modal } from 'ant-design-vue'
import { DeviceTopicInfo } from '/@/components/g-map/use-mqtt'
import {
  CloudControlAuthMessage,
  FlyToPointMessage,
  TakeoffToPointMessage,
  DrcModeExitNotifyMessage,
  DrcStatusNotifyMessage,
} from '/@/types/drone-control'
import {
  DRC_DISCONNECT_GRACE_MS,
  getDrcMqttDisconnectDecision
} from './drc-connection-policy.mjs'
import {
  CLOUD_CONTROL_AUTH_RELEASE_GRACE_MS,
  getCloudControlAuthTransitionDecision
} from './remote-control-auth-transition-policy.mjs'
import { getRemoteReconnectDecision } from './remote-control-reconnect-policy.mjs'
import {
  DRC_LINK_STATE,
  getDrcWsEventDecision
} from './drc-ws-event-policy.mjs'
import { getCloudControlAuthState } from './cloud-control-auth-policy.mjs'
import { buildRemoteSessionDisconnectAudit } from './remote-session-debug.mjs'
import {
  AXIS_DISTANCE_MIN_METERS,
  AXIS_DISTANCE_MAX_METERS,
  getVerticalCommandDurationMs
} from './axis-displacement-policy.mjs'
import {
  isDeviceOnline,
  pickSelectedDeviceSn,
  sortDevicesOnlineFirst,
} from './device-selection-policy.mjs'
import {
  OFFICIAL_TAKEOFF_TARGET_HEIGHT,
  OFFICIAL_TAKEOFF_STAGE2_STABILIZATION_MS,
  buildOfficialTakeoffPlan,
  isOfficialTakeoffProgressFailureStatus,
  isOfficialTakeoffProgressSuccessStatus,
} from './official-takeoff-flow.mjs'
import { deviceTsaUpdate } from '/@/hooks/use-g-map-tsa'

enum KeyCode {
  ARROW_UP = 'ArrowUp',
  ARROW_DOWN = 'ArrowDown',
  KEY_W = 'KeyW',
  KEY_A = 'KeyA',
  KEY_S = 'KeyS',
  KEY_D = 'KeyD',
}

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
const selectedMonitoringDeviceSn = ref('')
const msdkOnlineDevices = computed<MsdkDeviceState[]>(() => {
  return Object.values(store.state.msdkDeviceState.devices || {}) as MsdkDeviceState[]
})
const sortedMonitoringDevices = computed<OnlineDevice[]>(() => {
  return sortDevicesOnlineFirst(onlineDevices.data) as OnlineDevice[]
})
function isMonitoringAircraftDevice (device: OnlineDevice) {
  const model = String(device.model || '').toLowerCase()
  const callsign = String(device.callsign || '').toLowerCase()
  return !model.includes('rc plus') && !callsign.includes('rc plus')
}
const selectableMonitoringDevices = computed<OnlineDevice[]>(() => {
  return sortDevicesOnlineFirst(sortedMonitoringDevices.value.filter(isMonitoringAircraftDevice)) as OnlineDevice[]
})
const selectedMonitoringDevices = computed<OnlineDevice[]>(() => {
  const selected = selectableMonitoringDevices.value.find(device => device.sn === selectedMonitoringDeviceSn.value)
  return selected ? [selected] : selectableMonitoringDevices.value.slice(0, 1)
})
const tsaMap = deviceTsaUpdate()
let msdkDeviceTimer: ReturnType<typeof window.setInterval> | undefined
const actionLoading = reactive({} as Record<string, string>)
const fc100DeliveryFormState = reactive({
  missionNo: '',
  lastResult: '',
  devices: [] as DeliveryDeviceDTO[],
  selectedDeviceSn: '',
  selectedDeviceProps: null as DeliveryDeviceProperties | null,
  deviceLoading: false,
  propsLoading: false,
})
const sortedFc100DeliveryDevices = computed<DeliveryDeviceDTO[]>(() => {
  return sortDevicesOnlineFirst(fc100DeliveryFormState.devices) as DeliveryDeviceDTO[]
})
function isFc100AircraftDevice (device: DeliveryDeviceDTO) {
  const bindStatus = String(device.bindStatus || '').toLowerCase()
  const deviceType = String(device.deviceType || '').toLowerCase()
  return bindStatus !== 'rc' && deviceType !== 'rc'
}
const selectableFc100DeliveryDevices = computed<DeliveryDeviceDTO[]>(() => {
  return sortDevicesOnlineFirst(fc100DeliveryFormState.devices.filter(isFc100AircraftDevice)) as DeliveryDeviceDTO[]
})
const selectedFc100DeliveryDevice = computed<DeliveryDeviceDTO | undefined>(() => {
  return selectableFc100DeliveryDevices.value.find(device => device.deviceSn === fc100DeliveryFormState.selectedDeviceSn) ||
    selectableFc100DeliveryDevices.value[0]
})
const officialTakeoffFlow = reactive({
  gatewaySn: '',
  aircraftSn: '',
  phase: 'idle' as 'idle' | 'climbing_stage1' | 'arming_stage2_south' | 'flying_stage2_south' | 'flying_stage2_north' | 'completed' | 'failed',
  originLatitude: null as number | null,
  originLongitude: null as number | null,
  originAbsoluteHeight: null as number | null,
  startedAt: 0,
})
const remoteControlState = reactive({
  gatewaySn: '',
  aircraftSn: '',
  connected: false,
  cloudControlAuthorized: false,
  drcLinkState: DRC_LINK_STATE.DISCONNECT,
  joystickAvailable: false,
  mqttClient: null as any
})
const drcDisconnectState = reactive({
  pending: false,
  timer: null as ReturnType<typeof setTimeout> | null,
})
const authReleaseState = reactive({
  pending: false,
  reconnecting: false,
  timer: null as ReturnType<typeof setTimeout> | null,
})
const remoteTopicInfo = reactive<DeviceTopicInfo>({
  sn: '',
  pubTopic: '',
  subTopic: ''
})

const deviceInfo = computed(() => store.state.deviceState.deviceInfo)
const dockInfo = computed(() => store.state.deviceState.dockInfo)
const isRemoteControlConnected = computed(() => (
  remoteControlState.connected &&
  remoteControlState.cloudControlAuthorized &&
  remoteControlState.drcLinkState === DRC_LINK_STATE.CONNECT &&
  remoteControlState.joystickAvailable
))
function resetControlState () {
}
const hmsInfo = computed({
  get: () => store.state.hmsInfo,
  set: (val) => {
    return val
  }
})

// DJI runtime exposes authorization, link state, and joystick availability as independent
// signals. These events must not directly destroy the whole MSDK control session unless
// the transport itself actually breaks.
function onDroneControlWsEvent (payload: any) {
  if (!payload) return
  if (payload.biz_code === EBizCode.TakeoffToPointProgress) {
    const data = payload.data as TakeoffToPointMessage
    if (data?.sn !== officialTakeoffFlow.gatewaySn) return
    if (officialTakeoffFlow.phase !== 'climbing_stage1') return
    if (isOfficialTakeoffProgressFailureStatus(data?.status)) {
      resetOfficialTakeoffFlow()
      message.warning(`官方起飞第一阶段失败：${data?.message || data?.status || '未知错误'}`)
      return
    }
    if (!isOfficialTakeoffProgressSuccessStatus(data?.status)) {
      return
    }
    const device = onlineDevices.data.find(d => d.gateway.sn === officialTakeoffFlow.gatewaySn)
    if (!device) {
      resetOfficialTakeoffFlow()
      message.warning('官方起飞第二阶段未执行：目标飞机已离线。')
      return
    }
    scheduleOfficialTakeoffStage2South(device)
    return
  }
  if (payload.biz_code === EBizCode.FlyToPointProgress) {
    const data = payload.data as FlyToPointMessage
    if (data?.sn !== officialTakeoffFlow.gatewaySn) return
    if (officialTakeoffFlow.phase !== 'flying_stage2_south' && officialTakeoffFlow.phase !== 'flying_stage2_north') return
    if (isOfficialTakeoffProgressFailureStatus(data?.status)) {
      resetOfficialTakeoffFlow()
      message.warning(`官方起飞后续航段失败：${data?.message || data?.status || '未知错误'}`)
      return
    }
    if (!isOfficialTakeoffProgressSuccessStatus(data?.status)) {
      return
    }
    const device = onlineDevices.data.find(d => d.gateway.sn === officialTakeoffFlow.gatewaySn)
    if (!device) {
      resetOfficialTakeoffFlow()
      message.warning('官方起飞后续航段未完成：目标飞机已离线。')
      return
    }
    if (officialTakeoffFlow.phase === 'flying_stage2_south') {
      dispatchOfficialTakeoffStage2North(device).catch(() => {})
      return
    }
    resetOfficialTakeoffFlow()
    message.success('官方起飞流程完成：已返回起点。')
    return
  }
  if (payload.biz_code === EBizCode.CloudControlAuthUpdate) {
    const sn = payload.data?.sn
    if (sn !== remoteControlState.gatewaySn) return
    const authData = payload.data?.host as CloudControlAuthMessage | undefined
    const nextAuthState = getCloudControlAuthState(authData)
    const transition = getCloudControlAuthTransitionDecision({
      currentAuthorized: remoteControlState.cloudControlAuthorized,
      nextAuthorized: nextAuthState.authorized,
      remoteConnected: remoteControlState.connected,
      reconnecting: authReleaseState.reconnecting,
      officialTakeoffLocked: false,
    })

    if (transition.notice === 'defer_release') {
      scheduleCloudControlReleaseNotice()
      return
    }

    const authChanged = remoteControlState.cloudControlAuthorized !== transition.nextAuthorized
    remoteControlState.cloudControlAuthorized = transition.nextAuthorized

    if (transition.nextAuthorized) {
      const recoveredFromPending = authReleaseState.pending
      clearPendingCloudControlReleaseNotice()
      finishCloudControlReconnectAttempt()
      if (authChanged || recoveredFromPending) {
        message.success('MSDK 控制授权已恢复。')
      }
      return
    }

    clearPendingCloudControlReleaseNotice()
    finishCloudControlReconnectAttempt()
    if (authChanged) {
      message.warning('MSDK 控制授权已释放，请重新进入控制。')
    }
    return
  }
  if (!remoteControlState.connected) return
  if (payload.biz_code === EBizCode.JoystickInvalidNotify) {
    const data = payload.data as DrcModeExitNotifyMessage
    const decision = getDrcWsEventDecision({
      bizCode: payload.biz_code,
      remoteConnected: remoteControlState.connected,
      result: data?.result,
      officialTakeoffLocked: false,
    })
    if (decision.updateJoystickAvailable != null) {
      remoteControlState.joystickAvailable = decision.updateJoystickAvailable
    }
    message.info(data?.message || '摇杆控制当前不可用。')
    return
  }
  if (payload.biz_code === EBizCode.DrcStatusNotify) {
    const data = payload.data as DrcStatusNotifyMessage
    const decision = getDrcWsEventDecision({
      bizCode: payload.biz_code,
      remoteConnected: remoteControlState.connected,
      drcState: data?.drcState,
      result: data?.result,
      officialTakeoffLocked: false,
    })
    if (decision.updateDrcLinkState != null) {
      remoteControlState.drcLinkState = decision.updateDrcLinkState
    }
    if (decision.updateJoystickAvailable != null) {
      remoteControlState.joystickAvailable = decision.updateJoystickAvailable
    }
    if (data?.drcState === DrcStateEnum.DISCONNECT) {
      message.warning(data.message || 'MSDK 控制通道未连接，已暂停遥控输入。')
    } else if (data?.drcState === DrcStateEnum.CONNECTED) {
      message.success('MSDK 控制通道已连接。')
    }
  }
}

onMounted(() => {
  refreshMsdkDevices()
  handleFc100RefreshDevices().catch(() => {})
  msdkDeviceTimer = window.setInterval(refreshMsdkDevices, 2000)
  setTimeout(() => {
    watch(() => store.state.deviceStatusEvent,
      data => {
        refreshMsdkDevices()
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

function toOnlineDeviceFromMsdk (device: MsdkDeviceState): OnlineDevice {
  const model = normalizeMonitoringModelName(device.model)
  return {
    model,
    callsign: model || device.aircraftSn,
    sn: device.aircraftSn,
    mode: device.online ? EModeCode.Manual : EModeCode.Disconnected,
    gateway: {
      model: 'RC Plus MSDK Agent',
      callsign: device.gatewaySn || 'MSDK Agent',
      sn: device.gatewaySn || device.aircraftSn,
      domain: String(EDeviceTypeName.Gateway)
    },
    payload: []
  }
}

function toDeviceOsdFromMsdk (device: MsdkDeviceState): DeviceOsd {
  const batteryPercent = device.batteryPercent ?? 0
  return {
    longitude: Number(device.longitude ?? 0),
    latitude: Number(device.latitude ?? 0),
    gear: -1,
    mode_code: device.online ? EModeCode.Manual : EModeCode.Disconnected,
    height: String(device.height ?? 0),
    home_distance: String(device.homeDistance ?? 0),
    horizontal_speed: String(device.horizontalSpeed ?? 0),
    vertical_speed: String(device.verticalSpeed ?? 0),
    wind_speed: String(device.windSpeed ?? 0),
    wind_direction: '--',
    elevation: String(device.elevation ?? 0),
    position_state: {
      gps_number: String(device.gpsCount ?? '--'),
      is_fixed: device.positionFixed ? 1 : 0,
      rtk_number: String(device.rtkCount ?? '--')
    },
    battery: {
      capacity_percent: String(batteryPercent),
      landing_power: '0',
      remain_flight_time: 0,
      return_home_power: '0'
    }
  }
}

function syncMsdkDevicesToPage () {
  onlineDevices.data = sortDevicesOnlineFirst(msdkOnlineDevices.value.map(toOnlineDeviceFromMsdk)) as OnlineDevice[]
  selectedMonitoringDeviceSn.value = pickSelectedDeviceSn(selectableMonitoringDevices.value, selectedMonitoringDeviceSn.value, device => device.sn)
  onlineDocks.data = []
  msdkOnlineDevices.value.forEach(device => {
    if (!device.aircraftSn) return
    store.commit('SET_DEVICE_INFO', {
      sn: device.aircraftSn,
      host: toDeviceOsdFromMsdk(device)
    })
    if (device.longitude && device.latitude) {
      tsaMap.moveTo(device.aircraftSn, device.longitude, device.latitude, EDeviceTypeName.Aircraft, device.model || device.aircraftSn)
    }
  })
}

function handleMonitoringSelectDevice (deviceSn: string) {
  selectedMonitoringDeviceSn.value = deviceSn
}

async function refreshMsdkDevices () {
  const res = await listMsdkDevices()
  if (res.code !== 0) {
    return
  }
  store.commit('SET_MSDK_DEVICE_STATE', res.data || [])
  syncMsdkDevicesToPage()
}

watch(msdkOnlineDevices, (devices) => {
  for (const device of devices) {
    if (!device.aircraftSn || !device.longitude || !device.latitude) {
      continue
    }
    tsaMap.moveTo(device.aircraftSn, device.longitude, device.latitude, EDeviceTypeName.Aircraft, device.model || device.aircraftSn)
  }
}, { deep: true })

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

function getBatteryPercent (sn: string) {
  const osd = deviceInfo.value[sn]
  if (!osd?.battery) {
    return '--'
  }
  return `${osd.battery.capacity_percent}%`
}

function createSeq () {
  return Date.now()
}

function sleep (ms: number) {
  return new Promise(resolve => window.setTimeout(resolve, ms))
}

function canOfficialTakeoff (device: OnlineDevice) {
  return isCurrentRemoteGateway(device) && hasMsdkCommandCapability(device, 'takeoff') && hasMsdkCommandCapability(device, 'flyToPoint')
}

function canTakeoff (device: OnlineDevice) {
  return isCurrentRemoteGateway(device) && hasMsdkCommandCapability(device, 'takeoff')
}

function canLand (device: OnlineDevice) {
  return isCurrentRemoteGateway(device) && hasMsdkCommandCapability(device, 'land')
}

function canHover (device: OnlineDevice) {
  return isCurrentRemoteGateway(device) && hasMsdkCommandCapability(device, 'hover')
}

function canVirtualStick (device: OnlineDevice) {
  return isCurrentRemoteGateway(device) && hasMsdkCommandCapability(device, 'virtualStick')
}

function canEmergencyStop (device: OnlineDevice) {
  return isCurrentRemoteGateway(device) && hasMsdkCommandCapability(device, 'emergencyStop')
}

function clearPendingDrcDisconnectNotice () {
  if (drcDisconnectState.timer) {
    clearTimeout(drcDisconnectState.timer)
    drcDisconnectState.timer = null
  }
  drcDisconnectState.pending = false
}

function clearPendingCloudControlReleaseNotice () {
  if (authReleaseState.timer) {
    clearTimeout(authReleaseState.timer)
    authReleaseState.timer = null
  }
  authReleaseState.pending = false
}

function finishCloudControlReconnectAttempt () {
  authReleaseState.reconnecting = false
}

function scheduleDrcDisconnect (mqttClient: any) {
  if (drcDisconnectState.pending) return
  drcDisconnectState.pending = true
  message.warning(`遥控链路波动，等待 ${DRC_DISCONNECT_GRACE_MS / 1000} 秒内自动恢复...`)
  drcDisconnectState.timer = setTimeout(() => {
    drcDisconnectState.timer = null
    if (!remoteControlState.connected || remoteControlState.mqttClient !== mqttClient) {
      drcDisconnectState.pending = false
      return
    }
    drcDisconnectState.pending = false
    message.warning('遥控链路已断开。')
    destroyRemoteControlClient()
  }, DRC_DISCONNECT_GRACE_MS)
}

function scheduleCloudControlReleaseNotice () {
  if (authReleaseState.pending) return
  authReleaseState.pending = true
  message.warning(`MSDK 控制授权波动，等待 ${CLOUD_CONTROL_AUTH_RELEASE_GRACE_MS / 1000} 秒内自动恢复...`)
  authReleaseState.timer = setTimeout(() => {
    authReleaseState.timer = null
    authReleaseState.pending = false
    authReleaseState.reconnecting = false
    if (!remoteControlState.connected) {
      return
    }
    remoteControlState.cloudControlAuthorized = false
    message.warning('MSDK 控制授权已释放，请重新进入控制。')
  }, CLOUD_CONTROL_AUTH_RELEASE_GRACE_MS)
}

function renderTsaModalSummary (
  items: Array<{ label: string, value: string }>,
  className = 'tsa-modal-summary'
) {
  return h('div', { class: className },
    items.map((item) => h('div', { class: 'tsa-modal-summary-item' }, [
      h('div', { class: 'tsa-modal-summary-label' }, item.label),
      h('div', { class: 'tsa-modal-summary-value' }, item.value),
    ]))
  )
}

function renderTsaModalBlock (
  className: string,
  title: string,
  items: string[]
) {
  if (items.length === 0) {
    return null
  }

  return h('div', { class: className }, [
    h('div', { class: `${className}__title` }, title),
    h('div', { class: `${className}__body` }, items.map((item) => h('div', { class: `${className}__item` }, item))),
  ])
}

function renderTsaModalContent (options: {
  summary: Array<{ label: string, value: string }>
  notice: string[]
  warning: string[]
  subtitle?: string
  summaryClass?: string
  noticeClass?: string
  warningClass?: string
}) {
  const summaryClass = options.summaryClass ?? 'tsa-modal-summary'
  const noticeClass = options.noticeClass ?? 'tsa-modal-notice'
  const warningClass = options.warningClass ?? 'tsa-modal-warning'
  const children = [] as any[]
  if (options.subtitle) {
    children.push(h('div', { class: 'tsa-modal-subtitle' }, options.subtitle))
  }
  if (options.summary.length > 0) {
    children.push(renderTsaModalSummary(options.summary, summaryClass))
  }
  const noticeBlock = renderTsaModalBlock(noticeClass, '执行说明', options.notice)
  const warningBlock = renderTsaModalBlock(warningClass, '风险提示', options.warning)

  if (noticeBlock) {
    children.push(noticeBlock)
  }
  if (warningBlock) {
    children.push(warningBlock)
  }

  return h('div', { class: 'tsa-modal-content' }, children)
}

function confirmTsaModal (options: {
  title: string
  gatewaySn?: string
  action?: string
  subtitle?: string
  summary?: Array<{ label: string, value: string }>
  notice?: string[]
  warning?: string[]
  okText?: string
  cancelText?: string
  tone?: 'confirm' | 'warning' | 'danger'
}) {
  return new Promise<boolean>((resolve) => {
    const gatewaySn = options.gatewaySn
    const action = options.action
    if (gatewaySn && action && actionLoading[gatewaySn] === action) {
      resolve(false)
      return
    }
    if (gatewaySn && action) {
      setActionLoading(gatewaySn, action)
    }

    const clearPendingAction = () => {
      if (gatewaySn && actionLoading[gatewaySn] === action) {
        setActionLoading(gatewaySn)
      }
    }

    Modal.confirm({
      className: `tsa-modal-skin tsa-modal-skin--${options.tone ?? 'confirm'}`,
      width: 720,
      title: options.title,
      content: renderTsaModalContent({
        subtitle: options.subtitle,
        summary: options.summary ?? [],
        notice: options.notice ?? [],
        warning: options.warning ?? [],
      }),
      okText: options.okText ?? '确定',
      cancelText: options.cancelText ?? '取消',
      onOk () {
        clearPendingAction()
        resolve(true)
      },
      onCancel () {
        clearPendingAction()
        resolve(false)
      },
    })
  })
}

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

function hasMsdkCommandCapability (device: OnlineDevice, capability: string) {
  const msdkDevice = store.state.msdkDeviceState.devices?.[device.sn] as MsdkDeviceState | undefined
  return msdkDevice?.capabilities?.[capability] === true
}

function hasRemoteSession (device: OnlineDevice) {
  return remoteControlState.connected && remoteControlState.gatewaySn === device.gateway.sn
}

function hasActiveDrcControl (device: OnlineDevice) {
  return hasRemoteSession(device) && isRemoteControlConnected.value
}

function isCurrentRemoteGateway (device: OnlineDevice) {
  return hasActiveDrcControl(device)
}

function destroyRemoteControlClient () {
  clearPendingDrcDisconnectNotice()
  clearPendingCloudControlReleaseNotice()
  finishCloudControlReconnectAttempt()
  resetControlState()
  remoteControlState.mqttClient?.destroyed()
  remoteControlState.mqttClient = null
  remoteControlState.gatewaySn = ''
  remoteControlState.aircraftSn = ''
  remoteControlState.connected = false
  remoteControlState.cloudControlAuthorized = false
  remoteControlState.drcLinkState = DRC_LINK_STATE.DISCONNECT
  remoteControlState.joystickAvailable = false
  remoteTopicInfo.sn = ''
  remoteTopicInfo.pubTopic = ''
  remoteTopicInfo.subTopic = ''
  store.commit('SET_MQTT_STATE', null)
  store.commit('SET_CLIENT_ID', '')
}

function logRemoteSessionDisconnect (source: string) {
  const audit = buildRemoteSessionDisconnectAudit({
    source,
    gatewaySn: remoteControlState.gatewaySn,
    aircraftSn: remoteControlState.aircraftSn,
    clientId: store.state.clientId,
    officialTakeoffPhase: 'idle',
    cloudControlAuthorized: remoteControlState.cloudControlAuthorized,
    drcLinkState: remoteControlState.drcLinkState,
    joystickAvailable: remoteControlState.joystickAvailable,
  })
  console.info('[RemoteSessionDisconnect]', audit)
}

async function withAircraftAction (
  device: OnlineDevice,
  action: string,
  task: () => Promise<any>,
  successText: string,
  options: { successTiming?: 'after_success' | 'immediate' } = {}
) {
  if (!isGatewayControllable(device)) {
    message.warning('飞机离线，或遥测数据尚未就绪。')
    return
  }
  setActionLoading(device.gateway.sn, action)
  try {
    if (options.successTiming === 'immediate') {
      message.success(successText)
    }
    const res = await task()
    if (res.code === 0 && options.successTiming !== 'immediate') {
      message.success(successText)
    }
  } catch (error: any) {
    message.error(error?.message || '飞行指令执行失败。')
  } finally {
    setActionLoading(device.gateway.sn)
  }
}

function formatFc100Value (value: number | null | undefined, unit = '') {
  if (value === null || value === undefined) return '--'
  if (typeof value === 'number' && Number.isFinite(value)) {
    return `${Number.isInteger(value) ? value : value.toFixed(6).replace(/0+$/, '').replace(/\.$/, '')}${unit}`
  }
  return '--'
}

function formatFc100DeviceModel (device: DeliveryDeviceDTO) {
  const modelKey = String(device.deviceType || '').toLowerCase()
  const modelClass = String(device.bindStatus || '').toLowerCase()
  if (modelKey.includes('fc100') || modelClass === 'drone') return 'DJI FlyCart 100'
  if (modelClass === 'rc') return 'DJI RC Plus'
  return device.deviceType || '--'
}

function normalizeMonitoringModelName (model?: string) {
  const value = String(model || '').trim()
  if (!value || /^msdk aircraft$/i.test(value)) return ''
  if (/matrice[_\s-]*4t/i.test(value)) return 'Matrice 4T'
  if (/m4t/i.test(value)) return 'Matrice 4T'
  return value
}

function formatMonitoringDeviceModel (device: OnlineDevice) {
  return normalizeMonitoringModelName(device.model) || device.callsign || device.sn
}

function getMonitoringConnectionStatusClass (device: OnlineDevice) {
  return isDeviceOnline(device) ? 'fc100-link-status--online' : 'fc100-link-status--offline'
}

function formatMonitoringConnectionLabel (device: OnlineDevice) {
  return isDeviceOnline(device) ? '已连接' : '离线'
}

function formatMonitoringRtkStatus (sn: string) {
  const state = deviceInfo.value[sn]?.position_state
  if (!state) return '--'
  return `RTK ${state.rtk_number ?? '--'} / GPS ${state.gps_number ?? '--'}`
}

function formatMonitoringFlightMode (sn: string) {
  const modeCode = deviceInfo.value[sn]?.mode_code
  if (modeCode === undefined || modeCode === null) return EModeCode[EModeCode.Disconnected]
  return EModeCode[modeCode] || '--'
}

function formatMonitoringFlying (sn: string) {
  const modeCode = deviceInfo.value[sn]?.mode_code
  if (modeCode === undefined || modeCode === null || modeCode === EModeCode.Disconnected) return '否'
  return modeCode === EModeCode.Manual ? '否' : '是'
}

type MonitoringOsdNumericKey = 'longitude' | 'latitude' | 'height' | 'horizontal_speed' | 'vertical_speed' | 'home_distance' | 'wind_speed'

function formatMonitoringOsdValue (sn: string, key: MonitoringOsdNumericKey, unit = '') {
  const value = deviceInfo.value[sn]?.[key]
  if (value === undefined || value === null || value === '') return '--'
  const numericValue = Number(value)
  if (!Number.isFinite(numericValue)) return '--'
  return formatFc100Value(numericValue, unit)
}

function formatMonitoringUpdateTime (sn: string) {
  const device = msdkOnlineDevices.value.find(item => item.aircraftSn === sn)
  return formatFc100Time(device?.updatedAt)
}

function getFc100ConnectionStatusClass (value: boolean | null | undefined) {
  if (value === true) return 'fc100-link-status--online'
  return 'fc100-link-status--offline'
}

function formatFc100ConnectionLabel (value: boolean | null | undefined) {
  if (value === true) return '已连接'
  return '离线'
}

function formatDeviceOnlineLabel (device: any) {
  return isDeviceOnline(device) ? '在线' : '离线'
}

function formatFc100Boolean (value: boolean | null | undefined) {
  if (value === true) return '是'
  if (value === false) return '否'
  return '--'
}

function formatFc100Time (value: number | null | undefined) {
  if (!value) return '--'
  return new Date(value).toLocaleString()
}

function getFc100ErrorText (error: any, fallback: string) {
  return error?.response?.data?.data?.displayMessage ||
    error?.response?.data?.data?.apiMessage ||
    error?.response?.data?.message ||
    error?.response?.data?.result?.message ||
    error?.message ||
    fallback
}

function unwrapFc100Response (res: any) {
  return res?.data && typeof res.data.code !== 'undefined' ? res.data : res
}

function getFc100OperationDisplayText (payload: any, fallback: string) {
  if (!payload) return fallback
  return payload.displayMessage ||
    payload.apiMessage ||
    payload.reason ||
    payload.message ||
    fallback
}

function getFc100BodyDisplayText (body: any, fallback: string) {
  return body?.data?.displayMessage ||
    body?.data?.apiMessage ||
    body?.data?.reason ||
    body?.data?.message ||
    body?.displayMessage ||
    body?.apiMessage ||
    body?.reason ||
    body?.message ||
    fallback
}

function getFc100OperationPayload (body: any) {
  return body?.data !== undefined ? body.data : body
}

function isFc100OperationAccepted (payload: any) {
  return payload?.accepted !== false
}

async function handleFc100RefreshDevices () {
  fc100DeliveryFormState.deviceLoading = true
  try {
    const res = await deliveryApi.listDevices(workspaceId.value)
    const body = unwrapFc100Response(res)
    if (!Array.isArray(body?.data)) {
      const failText = `FC100 设备列表获取失败：${getFc100BodyDisplayText(body, '未返回设备列表')}`
      fc100DeliveryFormState.lastResult = failText
      message.warning(failText)
      return
    }
    fc100DeliveryFormState.devices = sortDevicesOnlineFirst(body.data || []) as DeliveryDeviceDTO[]
    if (!fc100DeliveryFormState.devices.length) {
      fc100DeliveryFormState.lastResult = 'FC100 设备列表为空：请确认飞机/遥控器已绑定到当前 FC100 workspace/group。'
    }
    if (!fc100DeliveryFormState.devices.length) {
      fc100DeliveryFormState.selectedDeviceSn = ''
      fc100DeliveryFormState.selectedDeviceProps = null
      return
    }
    const nextSelectedDeviceSn = pickSelectedDeviceSn(
      selectableFc100DeliveryDevices.value,
      fc100DeliveryFormState.selectedDeviceSn,
      device => device.deviceSn
    )
    if (nextSelectedDeviceSn) {
      await handleFc100SelectDevice(nextSelectedDeviceSn)
    }
  } catch (error: any) {
    const failText = `FC100 设备列表获取失败：${getFc100ErrorText(error, '接口调用失败')}`
    fc100DeliveryFormState.lastResult = failText
    message.error(failText)
  } finally {
    fc100DeliveryFormState.deviceLoading = false
  }
}

async function handleFc100SelectDevice (deviceSn: string) {
  if (!deviceSn) return
  fc100DeliveryFormState.selectedDeviceSn = deviceSn
  fc100DeliveryFormState.propsLoading = true
  try {
    const res = await deliveryApi.deviceProps(deviceSn)
    const body = unwrapFc100Response(res)
    if (!body?.data || typeof body.data !== 'object') {
      const failText = `FC100 设备物模型获取失败：${getFc100BodyDisplayText(body, '未返回设备物模型')}`
      fc100DeliveryFormState.lastResult = failText
      message.warning(failText)
      return
    }
    fc100DeliveryFormState.selectedDeviceProps = body.data || null
  } catch (error: any) {
    const failText = `FC100 设备物模型获取失败：${getFc100ErrorText(error, '接口调用失败')}`
    fc100DeliveryFormState.lastResult = failText
    message.error(failText)
  } finally {
    fc100DeliveryFormState.propsLoading = false
  }
}

function getFc100MissionNo () {
  return fc100DeliveryFormState.missionNo.trim()
}

function getFc100OperatorId () {
  return username.value || 'web-operator'
}

async function withFc100DeliveryAction (
  device: OnlineDevice,
  action: string,
  task: (missionNo: string) => Promise<any>,
  successText: string,
  options: { resultText?: (res: any) => string } = {}
) {
  const missionNo = getFc100MissionNo()
  if (!missionNo) {
    message.warning('请先输入火情任务编号。')
    return
  }
  setActionLoading(device.gateway.sn, action)
  try {
    const res = await task(missionNo)
    const body = unwrapFc100Response(res)
    const payload = getFc100OperationPayload(body)
    const text = options.resultText ? options.resultText(body) : getFc100OperationDisplayText(payload, successText)
    fc100DeliveryFormState.lastResult = text
    if (isFc100OperationAccepted(payload)) {
      message.success(text)
    } else {
      message.warning(text)
    }
    return body
  } catch (error: any) {
    const failText = getFc100ErrorText(error, 'FC100 接口调用失败。')
    fc100DeliveryFormState.lastResult = failText
    message.error(failText)
  } finally {
    setActionLoading(device.gateway.sn)
  }
}

async function handleFc100CreateTask (device: OnlineDevice) {
  await withFc100DeliveryAction(device, 'fc100CreateTask', async (missionNo) => {
    return await deliveryApi.createTask(missionNo, {
      operatorId: getFc100OperatorId(),
      taskName: `火情任务-${missionNo}`,
      remark: `前端验证 FC100 航线任务 ${missionNo}`,
    })
  }, 'FC100 航线任务已创建。')
}

async function handleFc100StartTask (device: OnlineDevice) {
  await withFc100DeliveryAction(device, 'fc100StartTask', async (missionNo) => {
    return await deliveryApi.startTask(missionNo, {
      operatorId: getFc100OperatorId(),
    })
  }, 'FC100 开始航线指令已发送。')
}

async function handleFc100TaskStatus (device: OnlineDevice) {
  await withFc100DeliveryAction(device, 'fc100TaskStatus', async (missionNo) => {
    return await deliveryApi.status(missionNo)
  }, 'FC100 任务状态已刷新。', {
    resultText: (res) => {
      const data = res?.data
      if (!data) return '任务状态：暂无数据'
      if (data.displayMessage) return data.displayMessage
      const progress = data.progressPercent === null || data.progressPercent === undefined ? '--' : `${data.progressPercent}%`
      return `任务状态：${data.status || '--'}；阶段：${data.phase || '--'}；进度：${progress}；${data.message || ''}`
    },
  })
}

async function confirmFc100DangerAction (device: OnlineDevice, action: string, label: string) {
  return await confirmTsaModal({
    title: `确认对任务 ${getFc100MissionNo()} 执行 ${label} 吗？`,
    gatewaySn: device.gateway.sn,
    action,
    subtitle: `${device.callsign} · FC100 Delivery Sync`,
    summary: [
      { label: '任务编号', value: getFc100MissionNo() || '--' },
      { label: '目标飞机', value: device.callsign },
      { label: '控制动作', value: label },
    ],
    notice: [
      '该动作会调用 FC100 云端互联远程命令接口。',
    ],
    warning: [
      '请确认现场飞行状态后再执行。',
    ],
    okText: `确认${label}`,
    tone: 'danger',
  })
}

async function handleFc100EmergencyStop (device: OnlineDevice) {
  if (!await confirmFc100DangerAction(device, 'fc100EmergencyStop', '急停')) return
  await withFc100DeliveryAction(device, 'fc100EmergencyStop', async (missionNo) => {
    return await deliveryApi.emergencyStop(missionNo, {
      operatorId: getFc100OperatorId(),
    })
  }, 'FC100 急停指令已发送。')
}

async function handleFc100ReturnHome (device: OnlineDevice) {
  if (!await confirmFc100DangerAction(device, 'fc100ReturnHome', '返航')) return
  await withFc100DeliveryAction(device, 'fc100ReturnHome', async (missionNo) => {
    return await deliveryApi.returnHome(missionNo, {
      operatorId: getFc100OperatorId(),
    })
  }, 'FC100 返航指令已发送。')
}

async function handleFc100ReleaseHook (device: OnlineDevice) {
  if (!await confirmFc100DangerAction(device, 'fc100ReleaseHook', '开钩投放')) return
  await withFc100DeliveryAction(device, 'fc100ReleaseHook', async (missionNo) => {
    return await deliveryApi.releaseHook(missionNo, {
      operatorId: getFc100OperatorId(),
    })
  }, 'FC100 开钩投放指令已发送。')
}

async function handleFc100Land (device: OnlineDevice) {
  if (!await confirmFc100DangerAction(device, 'fc100Land', '降落')) return
  await withFc100DeliveryAction(device, 'fc100Land', async (missionNo) => {
    return await deliveryApi.land(missionNo, {
      operatorId: getFc100OperatorId(),
    })
  }, 'FC100 降落指令已发送。')
}

async function handleFc100CommandStatus (device: OnlineDevice) {
  await withFc100DeliveryAction(device, 'fc100CommandStatus', async (missionNo) => {
    return await deliveryApi.commandStatus(missionNo)
  }, 'FC100 命令状态已刷新。', {
    resultText: (res) => `命令状态：${JSON.stringify(res?.data?.services || {})}`,
  })
}

async function connectRemoteControl (device: OnlineDevice) {
  if (!isGatewayControllable(device)) {
    message.warning('飞机离线，或遥测数据尚未就绪。')
    return false
  }
  if (hasRemoteSession(device) && remoteControlState.cloudControlAuthorized) {
    return true
  }
  setActionLoading(device.gateway.sn, 'connect')
  try {
    remoteControlState.gatewaySn = device.gateway.sn
    remoteControlState.aircraftSn = device.sn
    remoteControlState.connected = true
    remoteControlState.cloudControlAuthorized = true
    remoteControlState.drcLinkState = DRC_LINK_STATE.CONNECT
    remoteControlState.joystickAvailable = true
    remoteControlState.mqttClient = null
    remoteTopicInfo.sn = device.gateway.sn
    remoteTopicInfo.pubTopic = ''
    remoteTopicInfo.subTopic = ''
    message.success('MSDK 控制通道已就绪。')
    return true
  } finally {
    setActionLoading(device.gateway.sn)
  }
}

async function disconnectRemoteControl (source = 'unknown') {
  if (!remoteControlState.connected) {
    return
  }
  const gatewaySn = remoteControlState.gatewaySn
  setActionLoading(gatewaySn, 'disconnect')
  try {
    logRemoteSessionDisconnect(source)
    destroyRemoteControlClient()
    message.success('已退出 MSDK 控制通道。')
  } finally {
    setActionLoading(gatewaySn)
  }
}

async function holdVerticalControl (device: OnlineDevice, key: KeyCode, durationMs: number, action: string, successText: string) {
  await withAircraftAction(device, action, async () => {
    if (!canVirtualStick(device)) {
      throw new Error('MSDK 虚拟摇杆执行器未就绪。')
    }
    return await sendMsdkCommand(device.sn, 'virtual_stick', {
      key,
      duration_ms: durationMs
    })
  }, successText, { successTiming: 'immediate' })
}

async function holdDirectionalControl (device: OnlineDevice, key: KeyCode, durationMs: number, action: string, successText: string) {
  await withAircraftAction(device, action, async () => {
    if (!canVirtualStick(device)) {
      throw new Error('MSDK 虚拟摇杆执行器未就绪。')
    }
    return await sendMsdkCommand(device.sn, 'virtual_stick', {
      key,
      duration_ms: durationMs
    })
  }, successText, { successTiming: 'immediate' })
}

async function publishHover (device: OnlineDevice) {
  await withAircraftAction(device, 'hover', async () => {
    if (!canHover(device)) {
      throw new Error('MSDK 悬停执行器未就绪。')
    }
    return await sendMsdkCommand(device.sn, 'hover')
  }, '悬停指令已发送。')
}

async function sendEmergencyStop (device: OnlineDevice) {
  await withAircraftAction(device, 'stop', async () => {
    if (!canEmergencyStop(device)) {
      throw new Error('MSDK 急停执行器未就绪。')
    }
    return await sendMsdkCommand(device.sn, 'emergency_stop')
  }, '急停指令已发送。')
}

function resetOfficialTakeoffFlow () {
  officialTakeoffFlow.gatewaySn = ''
  officialTakeoffFlow.aircraftSn = ''
  officialTakeoffFlow.phase = 'idle'
  officialTakeoffFlow.originLatitude = null
  officialTakeoffFlow.originLongitude = null
  officialTakeoffFlow.originAbsoluteHeight = null
  officialTakeoffFlow.startedAt = 0
}

async function failOfficialTakeoff (reason: string) {
  try {
    const device = onlineDevices.data.find(d => d.gateway.sn === officialTakeoffFlow.gatewaySn)
    if (device && hasRemoteSession(device)) {
      await publishHover(device)
    }
  } catch {
    // Keep the original failure reason; hover is best-effort only.
  }
  officialTakeoffFlow.phase = 'failed'
  message.warning(reason)
}

function scheduleOfficialTakeoffStage2South (device: OnlineDevice) {
  officialTakeoffFlow.phase = 'arming_stage2_south'
  window.setTimeout(() => {
    if (officialTakeoffFlow.phase !== 'arming_stage2_south' || officialTakeoffFlow.gatewaySn !== device.gateway.sn) {
      return
    }
    dispatchOfficialTakeoffStage2South(device).catch((error: any) => {
      resetOfficialTakeoffFlow()
      message.warning(error?.message || '官方起飞第二阶段未执行：发送向南航段失败。')
    })
  }, OFFICIAL_TAKEOFF_STAGE2_STABILIZATION_MS)
}

async function dispatchOfficialTakeoffStage2South (device: OnlineDevice) {
  const plan = buildOfficialTakeoffPlan({
    latitude: officialTakeoffFlow.originLatitude,
    longitude: officialTakeoffFlow.originLongitude,
    absoluteHeight: officialTakeoffFlow.originAbsoluteHeight,
  })
  officialTakeoffFlow.phase = 'flying_stage2_south'
  await withAircraftAction(device, 'takeoff', async () => {
    return await sendMsdkCommand(device.sn, 'fly_to_point', {
      speed: plan.stage2South.maxSpeed,
      latitude: plan.stage2South.targetLatitude,
      longitude: plan.stage2South.targetLongitude,
      height: plan.stage2South.targetHeight
    })
  }, '官方起飞第二阶段已发送：向南约 200 米。')
}

async function dispatchOfficialTakeoffStage2North (device: OnlineDevice) {
  const plan = buildOfficialTakeoffPlan({
    latitude: officialTakeoffFlow.originLatitude,
    longitude: officialTakeoffFlow.originLongitude,
    absoluteHeight: officialTakeoffFlow.originAbsoluteHeight,
  })
  officialTakeoffFlow.phase = 'flying_stage2_north'
  await withAircraftAction(device, 'takeoff', async () => {
    return await sendMsdkCommand(device.sn, 'fly_to_point', {
      speed: plan.stage2North.maxSpeed,
      latitude: plan.stage2North.targetLatitude,
      longitude: plan.stage2North.targetLongitude,
      height: plan.stage2North.targetHeight
    })
  }, '官方起飞第三阶段已发送：向北返回起点。')
}

async function handleTakeoff (device: OnlineDevice) {
  if (!canTakeoff(device)) {
    message.warning('MSDK 起飞执行器尚未就绪。')
    return
  }
  const confirmed = await confirmTsaModal({
    title: `确认对 ${device.callsign} 执行 MSDK 原生起飞吗？`,
    gatewaySn: device.gateway.sn,
    action: 'takeoff',
    subtitle: `${device.callsign} · 当前 MSDK 控制通道保持中`,
    summary: [
      { label: '目标设备', value: device.callsign },
      { label: '控制动作', value: 'MSDK KeyStartTakeoff' },
    ],
    notice: [
      '该动作只发送 DJI 飞控原生起飞指令，不执行航点位移。',
      '起飞后的高度和悬停策略由 DJI 飞控按当前安全策略执行。',
    ],
    warning: [
      '请确认现场环境、桨叶和返航点状态安全后再执行。',
    ],
    okText: '确认起飞',
    tone: 'confirm',
  })
  if (!confirmed) return
  await withAircraftAction(device, 'takeoff', async () => {
    return await sendMsdkCommand(device.sn, 'takeoff')
  }, 'MSDK 原生起飞指令已发送。')
}

async function handleLanding (device: OnlineDevice) {
  if (!canLand(device)) {
    message.warning('MSDK 降落执行器尚未就绪。')
    return
  }
  const confirmed = await confirmTsaModal({
    title: `确认对 ${device.callsign} 执行 MSDK 原生降落吗？`,
    gatewaySn: device.gateway.sn,
    action: 'land',
    subtitle: `${device.callsign} · 当前 MSDK 控制通道保持中`,
    summary: [
      { label: '目标设备', value: device.callsign },
      { label: '控制动作', value: 'MSDK KeyStartAutoLanding' },
    ],
    notice: [
      '该动作会发送 DJI 飞控原生自动降落指令。',
      '降落保护和确认逻辑由当前 DJI 飞控安全策略处理。',
    ],
    warning: [
      '确认后飞机会进入自动降落流程，请确保当前区域适合降落。',
    ],
    okText: '确认降落',
    tone: 'warning',
  })
  if (!confirmed) return
  await withAircraftAction(device, 'land', async () => {
    return await sendMsdkCommand(device.sn, 'land')
  }, 'MSDK 原生降落指令已发送。')
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
    `${direction === 'up' ? '上升' : '下降'}指令已发送。`
  )
}

// ---------- fly_to_point (RC Plus 2 + MSDK control feasibility probe, see WORK_RECORD.md §8) ----------

// ~20 m north of current OSD position. 0.00018° of latitude ≈ 20 m anywhere on Earth.
const FLY_FORWARD_LAT_OFFSET_DEG = 0.00018
// Aircraft must be visibly airborne before allowing autonomous displacement commands.
const MIN_AIRBORNE_HEIGHT_M = 3

// Shared popover state: only one remote-controlled aircraft can have flight commands active at a
// time, so a single reactive is sufficient even though the template lists multiple aircraft.
const flyToPointFormState = reactive({
  visible: false,
  gatewaySn: '',
  aircraftSn: '',
  latitude: null as number | null,
  longitude: null as number | null,
  height: null as number | null,
  maxSpeed: 5 as number,
})

const axisDistanceFormState = reactive({
  visible: false,
  gatewaySn: '',
  aircraftSn: '',
  direction: 'up' as 'up' | 'down' | 'west' | 'east' | 'north' | 'south',
  distanceMeters: 5 as number,
})

function isAircraftAirborne (device: OnlineDevice) {
  const osd = deviceInfo.value[device.sn]
  if (!osd) return false
  if (osd.mode_code === EModeCode.Disconnected) return false
  const h = Number(osd.height)
  return Number.isFinite(h) && h >= MIN_AIRBORNE_HEIGHT_M
}

function canFlyToPoint (device: OnlineDevice) {
  return isCurrentRemoteGateway(device) && isAircraftAirborne(device)
}

function canReturnHome (device: OnlineDevice) {
  return isCurrentRemoteGateway(device) && hasMsdkCommandCapability(device, 'returnHome') && isAircraftAirborne(device)
}

function canCancelReturnHome (device: OnlineDevice) {
  return isCurrentRemoteGateway(device) && hasMsdkCommandCapability(device, 'returnHome')
}

async function handleReturnHome (device: OnlineDevice) {
  if (!canReturnHome(device)) {
    message.warning(`飞机需已起飞且高度不低于 ${MIN_AIRBORNE_HEIGHT_M} 米，并已进入 MSDK 控制通道。`)
    return
  }
  const osd = deviceInfo.value[device.sn]
  const height = Number(osd?.height)
  const homeDistance = Number(osd?.home_distance)
  const battery = osd?.battery?.capacity_percent
  const confirmed = await confirmTsaModal({
    title: `确认让 ${device.callsign} 执行返航吗？`,
    gatewaySn: device.gateway.sn,
    action: 'returnHome',
    subtitle: `${device.callsign} · 自主返航动作`,
    summary: [
      { label: '当前高度', value: Number.isFinite(height) ? `${height} 米` : '--' },
      { label: '返航点距离', value: Number.isFinite(homeDistance) ? `${homeDistance} 米` : '--' },
      { label: '当前电量', value: battery != null && battery !== '' ? `${battery}%` : '--' },
    ],
    notice: [
      '返航过程中飞机可能会先调整高度，再返回返航点。',
    ],
    warning: [
      '确认后将发送返航指令，飞机会按照当前安全策略自主返回。',
    ],
    okText: '确认返航',
    tone: 'danger',
  })
  if (!confirmed) {
    return
  }
  await withAircraftAction(device, 'returnHome', async () => {
    return await sendMsdkCommand(device.sn, 'return_home')
  }, '返航指令已发送。')
}

async function handleCancelReturnHome (device: OnlineDevice) {
  if (!isCurrentRemoteGateway(device)) {
    message.warning('遥控链路未连接。')
    return
  }
  const confirmed = await confirmTsaModal({
    title: `确认取消 ${device.callsign} 的返航吗？`,
    gatewaySn: device.gateway.sn,
    action: 'cancelReturnHome',
    subtitle: `${device.callsign} · 中止返航`,
    summary: [
      { label: '目标设备', value: device.callsign },
      { label: '控制动作', value: '取消返航' },
    ],
    notice: [
      '将停止当前返航流程，并恢复可用的人工控制。',
    ],
    warning: [
      '仅在确实需要中止返航时执行。',
    ],
    okText: '确认取消',
    tone: 'warning',
  })
  if (!confirmed) {
    return
  }
  await withAircraftAction(device, 'cancelReturnHome', async () => {
    return await sendMsdkCommand(device.sn, 'cancel_return_home')
  }, '取消返航指令已发送。')
}

function openFlyToPointManual (device: OnlineDevice) {
  const osd = deviceInfo.value[device.sn]
  flyToPointFormState.gatewaySn = device.gateway.sn
  flyToPointFormState.aircraftSn = device.sn
  // Prefill with current OSD position as a convenience; user must edit to create real distance.
  flyToPointFormState.latitude = osd?.latitude != null ? Number(osd.latitude) : null
  flyToPointFormState.longitude = osd?.longitude != null ? Number(osd.longitude) : null
  flyToPointFormState.height = osd?.height != null ? Number(osd.height) : null
  flyToPointFormState.maxSpeed = 5
  flyToPointFormState.visible = true
}

function closeFlyToPointManual () {
  flyToPointFormState.visible = false
}

function getAxisDirectionLabel (direction: 'up' | 'down' | 'west' | 'east' | 'north' | 'south') {
  switch (direction) {
    case 'up': return '上升'
    case 'down': return '下降'
    case 'west': return '向左'
    case 'east': return '向右'
    case 'north': return '向前'
    case 'south': return '向后'
  }
}

function openAxisDistanceControl (device: OnlineDevice, direction: 'up' | 'down' | 'west' | 'east' | 'north' | 'south') {
  axisDistanceFormState.gatewaySn = device.gateway.sn
  axisDistanceFormState.aircraftSn = device.sn
  axisDistanceFormState.direction = direction
  axisDistanceFormState.distanceMeters = 5
  axisDistanceFormState.visible = true
}

function closeAxisDistanceControl () {
  axisDistanceFormState.visible = false
}

async function submitAxisDistanceControl () {
  const device = onlineDevices.data.find(d => d.gateway.sn === axisDistanceFormState.gatewaySn)
  if (!device) {
    message.error('目标飞机已离线。')
    closeAxisDistanceControl()
    return
  }
  const meters = Number(axisDistanceFormState.distanceMeters)
  if (!Number.isFinite(meters) || meters < AXIS_DISTANCE_MIN_METERS || meters > AXIS_DISTANCE_MAX_METERS) {
    message.warning(`距离必须在 ${AXIS_DISTANCE_MIN_METERS}-${AXIS_DISTANCE_MAX_METERS} 米之间。`)
    return
  }
  const direction = axisDistanceFormState.direction
  closeAxisDistanceControl()

  if (direction === 'up' || direction === 'down') {
    if (!canVirtualStick(device)) {
      message.warning('MSDK 虚拟摇杆执行器未就绪。')
      return
    }
    await holdVerticalControl(
      device,
      direction === 'up' ? KeyCode.ARROW_UP : KeyCode.ARROW_DOWN,
      getVerticalCommandDurationMs(meters),
      direction,
      `${getAxisDirectionLabel(direction)} ${meters} 米指令已发送。`
    )
    return
  }

  if (!canVirtualStick(device)) {
    message.warning('MSDK 虚拟摇杆执行器未就绪。')
    return
  }
  const key =
    direction === 'north'
      ? KeyCode.KEY_W
      : direction === 'south'
        ? KeyCode.KEY_S
        : direction === 'west'
          ? KeyCode.KEY_A
          : KeyCode.KEY_D
  await holdDirectionalControl(
    device,
    key,
    getVerticalCommandDurationMs(meters),
    direction,
    `${getAxisDirectionLabel(direction)} ${meters} 米指令已发送。`
  )
}

async function handleFlyForwardTest (device: OnlineDevice) {
  if (!canFlyToPoint(device)) {
    message.warning(`飞机需已起飞且高度不低于 ${MIN_AIRBORNE_HEIGHT_M} 米，并已进入 MSDK 控制通道。`)
    return
  }
  const osd = deviceInfo.value[device.sn]
  const latitude = Number(osd.latitude)
  const longitude = Number(osd.longitude)
  const height = Number(osd.height)
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude) || !Number.isFinite(height)) {
    message.warning('飞机 OSD 遥测尚未就绪。')
    return
  }
  const targetLat = latitude + FLY_FORWARD_LAT_OFFSET_DEG
  const targetLon = longitude
  const targetHeight = height
  const confirmed = await confirmTsaModal({
    title: `确认对 ${device.callsign} 执行 fly_to_point 前飞约 20 米测试吗？`,
    gatewaySn: device.gateway.sn,
    action: 'flyForward',
    subtitle: `${device.callsign} · fly_to_point 测试动作`,
    summary: [
      { label: '当前位置', value: `${latitude}, ${longitude}, 高度=${height} 米` },
      { label: '目标位置', value: `${targetLat}, ${targetLon}, 高度=${targetHeight} 米` },
      { label: '最大速度', value: '5 米/秒' },
    ],
    notice: [
      '飞机将以当前高度向北平移约 20 米。',
    ],
    warning: [
      '这是一个前飞测试动作，请确认前方空间和高度余量充足。',
    ],
    okText: '发送测试',
    tone: 'confirm',
  })
  if (!confirmed) return
  await withAircraftAction(device, 'flyForward', async () => {
    return await sendMsdkCommand(device.sn, 'fly_to_point', {
      speed: 5,
      latitude: targetLat,
      longitude: targetLon,
      height: targetHeight
    })
  }, '前飞 20 米指令已发送。')
}

async function submitFlyToPointManual () {
  const device = onlineDevices.data.find(d => d.gateway.sn === flyToPointFormState.gatewaySn)
  if (!device) {
    message.error('目标飞机已离线。')
    closeFlyToPointManual()
    return
  }
  if (!canFlyToPoint(device)) {
    message.warning(`飞机需已起飞且高度不低于 ${MIN_AIRBORNE_HEIGHT_M} 米，并已进入 MSDK 控制通道。`)
    return
  }
  const { latitude, longitude, height, maxSpeed } = flyToPointFormState
  if (latitude == null || longitude == null || height == null) {
    message.warning('纬度、经度和高度均为必填项。')
    return
  }
  if (!Number.isFinite(latitude) || latitude < -90 || latitude > 90) {
    message.warning('纬度超出范围 [-90, 90]。')
    return
  }
  if (!Number.isFinite(longitude) || longitude < -180 || longitude > 180) {
    message.warning('经度超出范围 [-180, 180]。')
    return
  }
  if (!Number.isFinite(height) || height < MIN_AIRBORNE_HEIGHT_M) {
    message.warning(`目标高度必须不低于 ${MIN_AIRBORNE_HEIGHT_M} 米。`)
    return
  }
  const safeMaxSpeed = Number.isFinite(maxSpeed) && maxSpeed > 0 ? Math.min(maxSpeed, 15) : 5
  closeFlyToPointManual()
  await withAircraftAction(device, 'flyManual', async () => {
    return await sendMsdkCommand(device.sn, 'fly_to_point', {
      speed: safeMaxSpeed,
      latitude,
      longitude,
      height
    })
  }, '手动目标点指令已发送。')
}

async function handleStopFlyToPoint (device: OnlineDevice) {
  if (!canEmergencyStop(device)) {
    message.warning('MSDK 急停执行器未就绪。')
    return
  }
  await withAircraftAction(device, 'flyStop', async () => {
    return await sendMsdkCommand(device.sn, 'stop_fly_to_point')
  }, '停止飞向目标点指令已发送。')
}

onUnmounted(() => {
  if (msdkDeviceTimer) {
    window.clearInterval(msdkDeviceTimer)
    msdkDeviceTimer = undefined
  }
  resetOfficialTakeoffFlow()
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
  width: 100%;
  max-width: 292px;
  margin-bottom: 12px;
  border-radius: 2px;
  overflow: hidden;
  box-sizing: border-box;
}
.fc100-cloud-panel {
  margin: 0 0 10px;
  padding: 10px;
  background: #303030;
  border-bottom: 1px solid #4f4f4f;
  color: #f5f5f5;
}
.fc100-cloud-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}
.fc100-cloud-title {
  min-width: 0;
  font-size: 13px;
  font-weight: 700;
}
.fc100-cloud-empty {
  height: 40px;
  display: flex;
  align-items: center;
  color: #8c8c8c;
}
.fc100-device-list {
  display: grid;
  gap: 6px;
}
.fc100-device-item {
  min-width: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px;
  border-radius: 4px;
  background: #3a3a3a;
  border: 1px solid rgba(255, 255, 255, 0.06);
  cursor: pointer;
}
.fc100-device-item--active {
  border-color: rgba(42, 161, 101, 0.8);
  background: #354239;
}
.fc100-device-main {
  min-width: 0;
  flex: 1;
}
.fc100-device-sn {
  max-width: 100%;
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #ffffff;
  font-size: 12px;
  font-weight: 700;
}
.fc100-device-meta {
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #a6a6a6;
  font-size: 11px;
}
.fc100-device-card-dropdown {
  flex: 0 0 auto;
}
.fc100-device-card-dropdown__trigger {
  width: 28px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 4px;
  color: #f5f5f5;
  background: transparent;
  cursor: pointer;
}
.fc100-device-card-dropdown__trigger:hover {
  background: rgba(255, 255, 255, 0.08);
}
.fc100-device-menu-item {
  width: 220px;
  max-width: 220px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.fc100-device-menu-item span {
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.fc100-device-menu-item span {
  color: #1f1f1f;
  font-weight: 600;
}
.fc100-device-menu-item small {
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #8c8c8c;
}
.fc100-device-props {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px;
  margin-top: 8px;
}
.fc100-device-prop {
  min-width: 0;
  padding: 6px;
  border-radius: 4px;
  background: #252525;
}
.fc100-device-prop--wide {
  grid-column: 1 / -1;
}
.fc100-device-prop span,
.fc100-device-prop strong {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.fc100-device-prop span {
  color: #8c8c8c;
  font-size: 11px;
}
.fc100-device-prop strong {
  margin-top: 2px;
  color: #f5f5f5;
  font-size: 12px;
}
.fc100-link-status {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: fit-content;
  min-width: 44px;
  padding: 1px 6px;
  border-radius: 2px;
  font-size: 11px;
  line-height: 16px;
}
.fc100-link-status--online {
  color: #33d17a;
  background: rgba(51, 209, 122, 0.14);
  border: 1px solid rgba(51, 209, 122, 0.48);
}
.fc100-link-status--offline {
  color: #a6a6a6;
  background: rgba(166, 166, 166, 0.12);
  border: 1px solid rgba(166, 166, 166, 0.28);
}
.fc100-direct-wayline-panel {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid rgba(255, 255, 255, 0.1);
}
.fc100-direct-wayline-target {
  margin: 4px 0 8px;
  color: #a6a6a6;
  font-size: 11px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.fc100-preflight-panel {
  margin-bottom: 8px;
  padding: 7px 8px;
  border-radius: 4px;
  background: #252525;
}
.fc100-preflight-title {
  margin-bottom: 4px;
  color: #f5f5f5;
  font-size: 12px;
  font-weight: 700;
}
.fc100-preflight-pending,
.fc100-preflight-ok,
.fc100-preflight-warning {
  font-size: 11px;
  line-height: 1.5;
}
.fc100-preflight-pending {
  color: #a6a6a6;
}
.fc100-preflight-ok {
  color: #49aa19;
}
.fc100-preflight-warning {
  color: #ff7875;
}
.fc100-wayline-picker {
  margin-bottom: 8px;
  padding: 8px;
  border-radius: 4px;
  background: #252525;
}
.fc100-wayline-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
  color: #f5f5f5;
  font-size: 12px;
  font-weight: 700;
}
.fc100-wayline-empty {
  color: #8c8c8c;
  font-size: 11px;
}
.fc100-wayline-list {
  display: grid;
  gap: 6px;
}
.fc100-wayline-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 7px;
  border-radius: 4px;
  background: #333333;
  border: 1px solid rgba(255, 255, 255, 0.06);
  cursor: pointer;
}
.fc100-wayline-item--active {
  border-color: rgba(22, 119, 255, 0.85);
  background: #2b3a4d;
}
.fc100-wayline-main {
  min-width: 0;
}
.fc100-wayline-name,
.fc100-wayline-meta {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.fc100-wayline-name {
  color: #ffffff;
  font-size: 12px;
  font-weight: 700;
}
.fc100-wayline-meta {
  margin-top: 2px;
  color: #a6a6a6;
  font-size: 11px;
}
.fc100-direct-wayline-input {
  width: 100%;
  margin-bottom: 8px;
}
.fc100-direct-wayline-upload {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.fc100-direct-wayline-file {
  min-width: 0;
  color: #d9d9d9;
  font-size: 11px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.fc100-direct-wayline-task {
  margin-top: 8px;
  padding: 6px 8px;
  border-radius: 4px;
  background: #202020;
  color: #d9d9d9;
  font-size: 11px;
  word-break: break-all;
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
  margin-bottom: 10px;
  color: #faad14;
  line-height: 16px;
}
.aircraft-action-section {
  margin-bottom: 10px;
  padding: 10px;
  border-radius: 10px;
  background: linear-gradient(180deg, rgba(72, 72, 72, 0.55), rgba(42, 42, 42, 0.95));
  border: 1px solid rgba(255, 255, 255, 0.06);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.04);
}
.aircraft-action-section:last-child {
  margin-bottom: 0;
}
.aircraft-action-section-title {
  margin-bottom: 4px;
  color: #f5f5f5;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.04em;
}
.aircraft-action-grid {
  display: grid;
  gap: 6px;
}
.aircraft-action-grid--2 {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}
.aircraft-action-grid--3 {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}
.aircraft-action-btn {
  width: 100%;
  min-width: 0;
}
.aircraft-action-panel :deep(.ant-btn) {
  white-space: normal;
  height: 38px;
  padding: 0 6px;
  font-size: 14px;
  border-radius: 8px;
  border-color: rgba(255, 255, 255, 0.08);
  background: #3a3a3a;
  color: #f0f0f0;
  font-weight: 600;
  box-shadow: none;
  transition: all 0.2s ease;
}
.aircraft-action-panel :deep(.ant-btn:hover:not([disabled])),
.aircraft-action-panel :deep(.ant-btn:focus:not([disabled])) {
  color: #ffffff;
  border-color: rgba(255, 255, 255, 0.18);
  background: #464646;
}
.aircraft-action-panel :deep(.ant-btn[disabled]) {
  color: rgba(255, 255, 255, 0.38);
  background: #353535;
  border-color: rgba(255, 255, 255, 0.05);
}
.aircraft-action-btn-inner {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  width: 100%;
  min-width: 0;
  font-size: inherit;
  line-height: 1.2;
}
.aircraft-action-btn-inner > span:last-child {
  overflow: hidden;
  text-overflow: ellipsis;
}
.aircraft-action-panel :deep(.ant-btn-primary),
.aircraft-action-btn--primary {
  border-color: #1f7a4f !important;
  background: linear-gradient(180deg, #2aa165, #1d7c4d) !important;
  color: #f7fff9 !important;
}
.aircraft-action-panel :deep(.ant-btn-primary:hover:not([disabled])),
.aircraft-action-panel :deep(.ant-btn-primary:focus:not([disabled])) {
  border-color: #31b472 !important;
  background: linear-gradient(180deg, #31b472, #238c59) !important;
}
.aircraft-action-panel :deep(.ant-btn-dangerous),
.aircraft-action-btn--danger {
  border-color: rgba(255, 115, 64, 0.76) !important;
  background: linear-gradient(180deg, #ff7a45, #ef5b2a) !important;
  color: #fff7f2 !important;
}
.aircraft-action-btn--danger-strong {
  border-color: rgba(255, 78, 53, 0.9) !important;
  background: linear-gradient(180deg, #ff5a36, #ff2d20) !important;
  color: #fff8f6 !important;
  box-shadow: 0 0 0 1px rgba(255, 96, 64, 0.12), inset 0 1px 0 rgba(255, 255, 255, 0.16);
}
.aircraft-action-panel :deep(.ant-popover-open) {
  width: 100%;
}
.fc100-delivery-form {
  margin-bottom: 8px;
}
.fc100-delivery-input {
  width: 100%;
}
.fc100-delivery-result {
  margin-top: 8px;
  padding: 6px 8px;
  border-radius: 4px;
  background: #202020;
  color: #d9d9d9;
  line-height: 18px;
  word-break: break-all;
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

.tsa-modal-skin .ant-modal-content {
  border: 1px solid #d9e4ef;
  border-radius: 18px;
  background: linear-gradient(180deg, #ffffff 0%, #f5f8fc 100%);
  box-shadow: 0 22px 60px rgba(15, 23, 42, 0.18);
  overflow: hidden;
}

.tsa-modal-skin .ant-modal-header {
  margin-bottom: 0;
  padding: 18px 22px 10px;
  border-bottom: 1px solid rgba(15, 23, 42, 0.08);
  background: transparent;
}

.tsa-modal-skin .ant-modal-title,
.tsa-modal-skin .ant-modal-confirm-title {
  color: #102033;
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 0.01em;
}

.tsa-modal-skin .ant-modal-body,
.tsa-modal-skin .ant-modal-confirm-body {
  padding: 18px 22px 20px;
}

.tsa-modal-skin .ant-modal-confirm-content {
  margin-top: 0;
  color: #314155;
}

.tsa-modal-skin .ant-modal-confirm-btns {
  margin-top: 14px;
}

.tsa-modal-skin .ant-modal-confirm-btns .ant-btn-primary {
  border-color: #16794c;
  background: linear-gradient(180deg, #2ea567 0%, #1e7e51 100%);
  color: #f7fff9;
}

.tsa-modal-skin .ant-modal-confirm-btns .ant-btn-primary:hover,
.tsa-modal-skin .ant-modal-confirm-btns .ant-btn-primary:focus {
  border-color: #31b776;
  background: linear-gradient(180deg, #31b776 0%, #22895a 100%);
  color: #ffffff;
}

.tsa-modal-content {
  display: flex;
  flex-direction: column;
  gap: 14px;
  color: #274055;
  font-family: "SFMono-Regular", Consolas, "Liberation Mono", Menlo, monospace;
}

.tsa-modal-subtitle {
  color: #62748a;
  font-size: 13px;
  line-height: 1.5;
}

.tsa-modal-summary {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

@media (max-width: 640px) {
  .tsa-modal-summary {
    grid-template-columns: 1fr;
  }
}

.tsa-modal-summary-item,
.tsa-modal-notice,
.tsa-modal-warning {
  border-radius: 12px;
  border: 1px solid #dce6f1;
  background: rgba(255, 255, 255, 0.92);
}

.tsa-modal-summary-item {
  padding: 10px 12px;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.85);
}

.tsa-modal-summary-label {
  color: #6b7f95;
  font-size: 11px;
  letter-spacing: 0.05em;
  text-transform: uppercase;
}

.tsa-modal-summary-value {
  margin-top: 4px;
  color: #102033;
  font-size: 14px;
  font-weight: 700;
  word-break: break-word;
}

.tsa-modal-notice,
.tsa-modal-warning {
  padding: 12px 14px;
}

.tsa-modal-notice {
  border-color: rgba(47, 128, 237, 0.24);
  background: linear-gradient(180deg, #f8fbff 0%, #eef5ff 100%);
}

.tsa-modal-warning {
  border-color: rgba(245, 166, 35, 0.28);
  background: linear-gradient(180deg, #fffaf2 0%, #fff3e3 100%);
}

.tsa-modal-notice__title,
.tsa-modal-warning__title {
  margin-bottom: 8px;
  color: #1e2f43;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

.tsa-modal-notice__body,
.tsa-modal-warning__body {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.tsa-modal-notice__item,
.tsa-modal-warning__item {
  position: relative;
  padding-left: 14px;
  color: #314155;
  line-height: 1.5;
  word-break: break-word;
}

.tsa-modal-notice__item::before,
.tsa-modal-warning__item::before {
  position: absolute;
  left: 0;
  top: 0.65em;
  width: 6px;
  height: 6px;
  border-radius: 999px;
  content: '';
  transform: translateY(-50%);
}

.tsa-modal-notice__item::before {
  background: #2f80ed;
}

.tsa-modal-warning__item::before {
  background: #f5a623;
}

.tsa-modal-skin--confirm .ant-modal-confirm-btns .ant-btn-primary {
  border-color: #1677ff;
  background: linear-gradient(180deg, #2f8cff 0%, #1668dc 100%);
  color: #f8fbff;
}

.tsa-modal-skin--confirm .ant-modal-confirm-btns .ant-btn-primary:hover,
.tsa-modal-skin--confirm .ant-modal-confirm-btns .ant-btn-primary:focus {
  border-color: #3f96ff;
  background: linear-gradient(180deg, #3f96ff 0%, #1b73e8 100%);
  color: #ffffff;
}

.tsa-modal-skin--warning .ant-modal-confirm-btns .ant-btn-primary {
  border-color: #d48806;
  background: linear-gradient(180deg, #ffb648 0%, #f59e0b 100%);
  color: #3f2a00;
}

.tsa-modal-skin--warning .ant-modal-confirm-btns .ant-btn-primary:hover,
.tsa-modal-skin--warning .ant-modal-confirm-btns .ant-btn-primary:focus {
  border-color: #e8a52c;
  background: linear-gradient(180deg, #ffc15c 0%, #f7a91f 100%);
  color: #3f2a00;
}

.tsa-modal-skin--danger .ant-modal-confirm-btns .ant-btn-primary {
  border-color: #cf1322;
  background: linear-gradient(180deg, #ff7875 0%, #ef4444 100%);
  color: #fff8f8;
}

.tsa-modal-skin--danger .ant-modal-confirm-btns .ant-btn-primary:hover,
.tsa-modal-skin--danger .ant-modal-confirm-btns .ant-btn-primary:focus {
  border-color: #f06060;
  background: linear-gradient(180deg, #ff8b88 0%, #f25454 100%);
  color: #ffffff;
}

</style>
