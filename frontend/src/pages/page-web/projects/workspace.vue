<template>
  <div class="command-workspace-shell"><CommandHeader /><SectionNav />
  <div class="project-app-wrapper" :class="{ 'fire-mode': isFireRoute }">
    <div class="left cc-legacy-theme">
      <div class="main-content uranus-scrollbar dark">
        <router-view />
      </div>
    </div>
    <div class="right" v-if="!isFireRoute">
      <div class="map-wrapper">
        <GMap />
      </div>
      <div id="wayline-planning-overlay-host" class="wayline-planning-overlay-host" v-if="isWaylineRoute"></div>
      <div class="media-wrapper cc-legacy-theme" v-if="root.$route.name === ERouterName.MEDIA">
        <MediaPanel />
      </div>
      <div class="task-wrapper cc-legacy-theme" v-if="root.$route.name === ERouterName.TASK">
        <TaskPanel />
      </div>
      <div class="livestream-wrapper" v-if="root.$route.name === ERouterName.LIVESTREAM">
        <WorkspaceLivestreamPanel />
      </div>
    </div>
  </div>
  </div>
</template>
<script lang="ts" setup>
import CommandHeader from '/@/components/command-center/CommandHeader.vue'
import SectionNav from '/@/components/command-center/SectionNav.vue'
import MediaPanel from '/@/components/MediaPanel.vue'
import TaskPanel from '/@/components/task/TaskPanel.vue'
import WorkspaceLivestreamPanel from '/@/components/WorkspaceLivestreamPanel.vue'
import GMap from '/@/components/GMap.vue'
import { EBizCode, ERouterName, ELocalStorageKey } from '/@/types'
import { getRoot } from '/@/root'
import { useMyStore } from '/@/store'
import { useConnectWebSocket } from '/@/hooks/use-connect-websocket'
import EventBus from '/@/event-bus'
import { computed, onMounted } from 'vue'

const root = getRoot()
const store = useMyStore()
onMounted(() => { if (!localStorage.getItem(ELocalStorageKey.Token)) root.$router.push('/project') })

// fc100 灭火模块的路由进入时,隐藏右侧固定 GMap,左侧扩展占满,fire 页面有自己的地图和全宽列表
const FIRE_ROUTES = new Set<string>([
  ERouterName.FIRE_EVENTS,
  'fire-events-table',
  ERouterName.FIRE_MISSIONS,
  ERouterName.OPERATION_INCIDENTS,
  ERouterName.FIRE_MISSION_DETAIL,
  ERouterName.FIRE_ROUTE_PREVIEW,
  ERouterName.FIRE_PAYLOAD_RELEASE,
])
const isFireRoute = computed(() => FIRE_ROUTES.has(root.$route.name as string))
const isWaylineRoute = computed(() => root.$route.name === ERouterName.WAYLINE)

const messageHandler = async (payload: any) => {
  if (!payload) {
    return
  }

  switch (payload.biz_code) {
    case EBizCode.GatewayOsd: {
      store.commit('SET_GATEWAY_INFO', payload.data)
      break
    }
    case EBizCode.DeviceOsd: {
      store.commit('SET_DEVICE_INFO', payload.data)
      break
    }
    case EBizCode.DockOsd: {
      store.commit('SET_DOCK_INFO', payload.data)
      break
    }
    case EBizCode.MapElementCreate: {
      store.commit('SET_MAP_ELEMENT_CREATE', payload.data)
      break
    }
    case EBizCode.MapElementUpdate: {
      store.commit('SET_MAP_ELEMENT_UPDATE', payload.data)
      break
    }
    case EBizCode.MapElementDelete: {
      store.commit('SET_MAP_ELEMENT_DELETE', payload.data)
      break
    }
    case EBizCode.DeviceOnline: {
      store.commit('SET_DEVICE_ONLINE', payload.data)
      break
    }
    case EBizCode.DeviceOffline: {
      store.commit('SET_DEVICE_OFFLINE', payload.data)
      break
    }
    case EBizCode.FlightTaskProgress:
    case EBizCode.FlightTaskMediaProgress:
    case EBizCode.FlightTaskMediaHighestPriority: {
      EventBus.emit('flightTaskWs', payload)
      break
    }
    case EBizCode.DeviceHms: {
      store.commit('SET_DEVICE_HMS_INFO', payload.data)
      break
    }
    case EBizCode.DeviceReboot:
    case EBizCode.DroneOpen:
    case EBizCode.DroneClose:
    case EBizCode.CoverOpen:
    case EBizCode.CoverClose:
    case EBizCode.PutterOpen:
    case EBizCode.PutterClose:
    case EBizCode.ChargeOpen:
    case EBizCode.ChargeClose:
    case EBizCode.DeviceFormat:
    case EBizCode.DroneFormat:
    {
      store.commit('SET_DEVICES_CMD_EXECUTE_INFO', {
        biz_code: payload.biz_code,
        timestamp: payload.timestamp,
        ...payload.data,
      })
      break
    }
    case EBizCode.ControlSourceChange:
    case EBizCode.FlyToPointProgress:
    case EBizCode.TakeoffToPointProgress:
    case EBizCode.JoystickInvalidNotify:
    case EBizCode.DrcStatusNotify:
    case EBizCode.CloudControlAuthUpdate:
    {
      EventBus.emit('droneControlWs', payload)
      break
    }
    case EBizCode.FlightAreasSyncProgress: {
      EventBus.emit('flightAreasSyncProgressWs', payload.data)
      break
    }
    case EBizCode.FlightAreasDroneLocation: {
      EventBus.emit('flightAreasDroneLocationWs', payload)
      break
    }
    case EBizCode.FlightAreasUpdate: {
      EventBus.emit('flightAreasUpdateWs', payload.data)
      break
    }
    default:
      break
  }
}

// 监听ws 消息
useConnectWebSocket(messageHandler)

</script>
<style lang="scss" scoped>
@use '/@/styles/index.scss';

.command-workspace-shell {height:100dvh;display:flex;flex-direction:column;background:#0c1420;}
.project-app-wrapper {
  flex:1;
  min-height:0;
  display: flex;
  transition: width 0.2s ease;
  height: auto;
  width: 100%;

  &.fire-mode {
    .left {
      width: 100%;
      flex: 1 1 auto;
      min-width: 0;

      .main-content {
        flex: 1;
        min-width: 0;
        min-height: 0;
        width: auto;
        overflow-y: auto;
        overflow-x: hidden;
        background-color: #0c1420;
        color: #222;
      }
    }

    :deep(.demo-project-sidebar-wrapper) {
      flex: 0 0 50px;
      position: relative;
      z-index: 20;
      background-color: #232323;
    }
  }

  .left {
    display: flex;
    width: 290px;
    flex: 0 0 290px;
    background-color: #0f1b2a;

    .main-content {
      flex: 1;
      color: $text-white-basic;
      width: 100%;
    }
  }

  .right {
    flex-grow: 1;
    position: relative;

    .map-wrapper{
      width: 100%;
      height: 100%;
    }

    .wayline-planning-overlay-host {
      position: absolute;
      top: 0;
      right: 0;
      bottom: 0;
      left: 0;
      z-index: 80;
      pointer-events: none;
    }

    .media-wrapper,
    .task-wrapper,
    .livestream-wrapper {
      position: absolute;
      top: 0;
      bottom: 0;
      left: 0;
      right: 0;
      z-index: 100;
      background: #0f1b2a;
    }
  }
}
@media(max-width:800px){
  .project-app-wrapper:not(.fire-mode){flex-direction:column;overflow:auto;
    .left{width:100%;flex:0 0 300px;min-height:0;overflow:auto}
    .right{width:100%;flex:0 0 620px;min-height:620px}
  }
}
</style>
