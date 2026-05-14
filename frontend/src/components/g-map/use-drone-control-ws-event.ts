import { message, notification } from 'ant-design-vue'
import { ref, onMounted, onBeforeUnmount } from 'vue'
import EventBus from '/@/event-bus/'
import { EBizCode } from '/@/types'
import { ControlSource } from '/@/types/device'
import { ControlSourceChangeType, ControlSourceChangeInfo, FlyToPointMessage, TakeoffToPointMessage, DrcModeExitNotifyMessage, DrcStatusNotifyMessage } from '/@/types/drone-control'

export interface UseDroneControlWsEventParams {
}

export function useDroneControlWsEvent (sn: string, payloadSn: string, funcs?: UseDroneControlWsEventParams) {
  const droneControlSource = ref(ControlSource.A)
  const payloadControlSource = ref(ControlSource.B)
  function onControlSourceChange (data: ControlSourceChangeInfo) {
    if (data.type === ControlSourceChangeType.Flight && data.sn === sn) {
      droneControlSource.value = data.control_source
      message.info(`Flight control is changed to ${droneControlSource.value}`)
      return
    }
    if (data.type === ControlSourceChangeType.Payload && data.sn === payloadSn) {
      payloadControlSource.value = data.control_source
      message.info(`Payload control is changed to ${payloadControlSource.value}.`)
    }
  }

  // Status strings that indicate the task is still running (not a terminal state).
  // Seeing these with non-zero result code is normal (the code carries in-progress
  // diagnostics like battery warnings) and must NOT pop an error notification.
  const IN_PROGRESS_STATUSES = new Set(['task_ready', 'wayline_progress'])
  const SUCCESS_STATUSES = new Set(['wayline_ok', 'task_finish'])
  const FAILURE_STATUSES = new Set(['wayline_failed', 'wayline_cancel'])

  function handleProgress (key: string, description: string, error: number, status?: string) {
    if (status && IN_PROGRESS_STATUSES.has(status)) {
      // Silently ignore intermediate progress ticks to avoid notification spam.
      return
    }
    if (status && FAILURE_STATUSES.has(status)) {
      notification.error({
        key: key,
        message: `${key} (${status}) code:${error}`,
        description: description,
        duration: 10
      })
      return
    }
    if (status && SUCCESS_STATUSES.has(status)) {
      notification.success({
        key: key,
        message: `${key} (${status})`,
        description: description,
        duration: 5
      })
      return
    }
    // No status field — fall back to using the numeric result code.
    if (error !== 0) {
      notification.error({
        key: key,
        message: `${key} code:${error}`,
        description: description,
        duration: 10
      })
    } else {
      notification.info({
        key: key,
        message: key,
        description: description,
        duration: 5
      })
    }
  }

  function handleDroneControlWsEvent (payload: any) {
    if (!payload) {
      return
    }

    switch (payload.biz_code) {
      case EBizCode.ControlSourceChange: {
        onControlSourceChange(payload.data)
        break
      }
      case EBizCode.FlyToPointProgress: {
        const { sn: deviceSn, result, message: msg, status } = payload.data as FlyToPointMessage
        if (deviceSn !== sn) return
        handleProgress(EBizCode.FlyToPointProgress, `device(sn: ${deviceSn}) ${msg}`, result, status)
        break
      }
      case EBizCode.TakeoffToPointProgress: {
        const { sn: deviceSn, result, message: msg, status } = payload.data as TakeoffToPointMessage
        if (deviceSn !== sn) return
        handleProgress(EBizCode.TakeoffToPointProgress, `device(sn: ${deviceSn}) ${msg}`, result, status)
        break
      }
      case EBizCode.JoystickInvalidNotify: {
        // Expected after takeoff_to_point: drone enters autonomous mode and disables DRC stick.
        // Show as info, not error.
        const { sn: deviceSn } = payload.data as DrcModeExitNotifyMessage
        if (deviceSn !== sn) return
        notification.info({
          key: EBizCode.JoystickInvalidNotify,
          message: 'Drone entered autonomous flight',
          description: 'Remote stick control has been disabled because the drone is now in autonomous mode.',
          duration: 8
        })
        break
      }
      case EBizCode.DrcStatusNotify: {
        // DRC link status changed — no toast needed; UI state handles this.
        break
      }
    }
    // eslint-disable-next-line no-unused-expressions
    // console.log('payload.biz_code', payload.data)
  }

  onMounted(() => {
    EventBus.on('droneControlWs', handleDroneControlWsEvent)
  })

  onBeforeUnmount(() => {
    EventBus.off('droneControlWs', handleDroneControlWsEvent)
  })

  return {
    droneControlSource: droneControlSource,
    payloadControlSource: payloadControlSource
  }
}
