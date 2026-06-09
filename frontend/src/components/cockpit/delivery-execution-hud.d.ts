export interface DeliveryExecutionHudItem {
  label: string
  value: string
}

export interface DeliveryExecutionHud {
  identity: DeliveryExecutionHudItem[]
  flight: DeliveryExecutionHudItem[]
  task: DeliveryExecutionHudItem[]
  chips: string[]
  flightRows: DeliveryExecutionHudItem[][]
  message: string
}

export function buildDeliveryExecutionHud(input?: {
  selectedDeviceSn?: string
  deliveryTargetSummary?: string
  taskPhase?: string
  taskProgress?: string
  aircraftStatus?: string
  liveSource?: string
  taskMessage?: string
}): DeliveryExecutionHud
