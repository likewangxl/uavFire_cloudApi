import { ref } from 'vue'
import { sendMsdkCommand } from '/@/api/msdk-device'
import { message } from 'ant-design-vue'

interface MsdkPointCommandBody {
  latitude?: number
  longitude?: number
  height?: number
  speed?: number
  max_speed?: number
  points?: Array<{
    latitude: number
    longitude: number
    height: number
  }>
  target_latitude?: number
  target_longitude?: number
  target_height?: number
}

function firstPoint (body: MsdkPointCommandBody) {
  const point = body.points?.[0]
  return {
    latitude: body.latitude ?? body.target_latitude ?? point?.latitude,
    longitude: body.longitude ?? body.target_longitude ?? point?.longitude,
    height: body.height ?? body.target_height ?? point?.height,
    speed: body.speed ?? body.max_speed ?? 5
  }
}

export function useDroneControl () {
  const droneControlPanelVisible = ref(false)

  function setDroneControlPanelVisible (visible: boolean) {
    droneControlPanelVisible.value = visible
  }

  async function flyToPoint (sn: string, body: MsdkPointCommandBody) {
    const { code } = await sendMsdkCommand(sn, 'fly_to_point', firstPoint(body))
    if (code === 0) {
      message.success('Fly to')
    }
  }

  async function stopFlyToPoint (sn: string) {
    const { code } = await sendMsdkCommand(sn, 'stop_fly_to_point')
    if (code === 0) {
      message.success('Stop fly to')
    }
  }

  async function takeoffToPoint (sn: string, body: MsdkPointCommandBody) {
    const takeoff = await sendMsdkCommand(sn, 'takeoff')
    const point = firstPoint(body)
    const canFlyToPoint = point.latitude != null && point.longitude != null && point.height != null
    const { code } = canFlyToPoint
      ? await sendMsdkCommand(sn, 'fly_to_point', point)
      : takeoff
    if (code === 0) {
      message.success('Take off successfully')
    }
  }

  return {
    droneControlPanelVisible,
    setDroneControlPanelVisible,
    flyToPoint,
    stopFlyToPoint,
    takeoffToPoint
  }
}
