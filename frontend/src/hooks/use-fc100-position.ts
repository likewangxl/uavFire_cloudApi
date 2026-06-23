import { reactive } from 'vue'
import type { DeliveryDeviceProperties } from '/@/api/fire/delivery'

export const fc100PositionState = reactive({
  selectedDeviceSn: '',
  selectedDeviceProps: null as DeliveryDeviceProperties | null,
})

export function setFc100PositionDevice (deviceSn: string) {
  fc100PositionState.selectedDeviceSn = deviceSn || ''
  if (!deviceSn) {
    fc100PositionState.selectedDeviceProps = null
  }
}

export function setFc100PositionProps (props: DeliveryDeviceProperties | null) {
  fc100PositionState.selectedDeviceProps = props
}
