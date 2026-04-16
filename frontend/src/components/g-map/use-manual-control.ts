import {
  ref,
  onUnmounted,
  watch,
  Ref,
} from 'vue'
import { message } from 'ant-design-vue'
import {
  DRC_METHOD,
  StickControlProtocol,
} from '/@/types/drc'
import {
  useMqtt,
  DeviceTopicInfo
} from './use-mqtt'

let myInterval: any
const STICK_CENTER = 1024
const STICK_STEP = 260
const STICK_CONTROL_INTERVAL_MS = 100

export enum KeyCode {
  KEY_W = 'KeyW',
  KEY_A = 'KeyA',
  KEY_S = 'KeyS',
  KEY_D = 'KeyD',
  KEY_Q = 'KeyQ',
  KEY_E = 'KeyE',
  ARROW_UP = 'ArrowUp',
  ARROW_DOWN = 'ArrowDown',
}

export function useManualControl (deviceTopicInfo: DeviceTopicInfo, isCurrentFlightController: Ref<boolean>) {
  const activeCodeKey = ref(null) as Ref<KeyCode | null>
  const mqttHooks = useMqtt(deviceTopicInfo)
  let seq = 0

  function createStickControlBody (params: StickControlProtocol) {
    return {
      seq: seq++,
      method: DRC_METHOD.STICK_CONTROL,
      data: {
        roll: params.roll ?? STICK_CENTER,
        pitch: params.pitch ?? STICK_CENTER,
        throttle: params.throttle ?? STICK_CENTER,
        yaw: params.yaw ?? STICK_CENTER,
        gimbal_pitch: params.gimbal_pitch ?? STICK_CENTER,
      },
    }
  }

  function publishNeutralStick () {
    if (!deviceTopicInfo.pubTopic || activeCodeKey.value === null) {
      return
    }
    mqttHooks?.publishMqtt(deviceTopicInfo.pubTopic, createStickControlBody({}), { qos: 0 })
  }

  function handlePublish (params: StickControlProtocol) {
    handleClearInterval()
    mqttHooks?.publishMqtt(deviceTopicInfo.pubTopic, createStickControlBody(params), { qos: 0 })
    myInterval = setInterval(() => {
      const body = createStickControlBody(params)
      window.console.log('keyCode>>>>', activeCodeKey.value, body)
      mqttHooks?.publishMqtt(deviceTopicInfo.pubTopic, body, { qos: 0 })
    }, STICK_CONTROL_INTERVAL_MS)
  }

  function handleKeyup (keyCode: KeyCode) {
    if (!deviceTopicInfo.pubTopic) {
      message.error('请确保已经建立DRC链路')
      return
    }
    seq = 0
    switch (keyCode) {
      case 'KeyA':
        if (activeCodeKey.value === keyCode) return
        handlePublish({ roll: STICK_CENTER - STICK_STEP })
        activeCodeKey.value = keyCode
        break
      case 'KeyW':
        if (activeCodeKey.value === keyCode) return
        handlePublish({ pitch: STICK_CENTER + STICK_STEP })
        activeCodeKey.value = keyCode
        break
      case 'KeyS':
        if (activeCodeKey.value === keyCode) return
        handlePublish({ pitch: STICK_CENTER - STICK_STEP })
        activeCodeKey.value = keyCode
        break
      case 'KeyD':
        if (activeCodeKey.value === keyCode) return
        handlePublish({ roll: STICK_CENTER + STICK_STEP })
        activeCodeKey.value = keyCode
        break
      case 'ArrowUp':
        if (activeCodeKey.value === keyCode) return
        handlePublish({ throttle: STICK_CENTER + STICK_STEP })
        activeCodeKey.value = keyCode
        break
      case 'ArrowDown':
        if (activeCodeKey.value === keyCode) return
        handlePublish({ throttle: STICK_CENTER - STICK_STEP })
        activeCodeKey.value = keyCode
        break
      case 'KeyQ':
        if (activeCodeKey.value === keyCode) return
        handlePublish({ yaw: STICK_CENTER - STICK_STEP })
        activeCodeKey.value = keyCode
        break
      case 'KeyE':
        if (activeCodeKey.value === keyCode) return
        handlePublish({ yaw: STICK_CENTER + STICK_STEP })
        activeCodeKey.value = keyCode
        break
      default:
        break
    }
  }

  function handleClearInterval () {
    clearInterval(myInterval)
    myInterval = undefined
  }

  function resetControlState () {
    publishNeutralStick()
    seq = 0
    handleClearInterval()
    activeCodeKey.value = null
  }

  function onKeyup () {
    resetControlState()
  }

  function onKeydown (e: KeyboardEvent) {
    handleKeyup(e.code as KeyCode)
  }

  function startKeyboardManualControl () {
    window.addEventListener('keydown', onKeydown)
    window.addEventListener('keyup', onKeyup)
  }

  function closeKeyboardManualControl () {
    resetControlState()
    window.removeEventListener('keydown', onKeydown)
    window.removeEventListener('keyup', onKeyup)
  }

  watch(() => isCurrentFlightController.value, (val) => {
    if (val && deviceTopicInfo.pubTopic) {
      startKeyboardManualControl()
    } else {
      closeKeyboardManualControl()
    }
  }, { immediate: true })

  onUnmounted(() => {
    closeKeyboardManualControl()
  })

  function handleEmergencyStop () {
    if (!deviceTopicInfo.pubTopic) {
      message.error('请确保已经建立DRC链路')
      return
    }
    const body = {
      method: DRC_METHOD.DRONE_EMERGENCY_STOP,
      data: {}
    }
    resetControlState()
    window.console.log('handleEmergencyStop>>>>', deviceTopicInfo.pubTopic, body)
    mqttHooks?.publishMqtt(deviceTopicInfo.pubTopic, body, { qos: 1 })
  }

  return {
    activeCodeKey,
    handleKeyup,
    handleEmergencyStop,
    resetControlState,
  }
}
