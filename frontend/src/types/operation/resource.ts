export type AssignableDeviceKind = 'DELIVERY' | 'MONITOR'

export interface AssignableDeviceOption {
  resourceSn: string;
  kind: AssignableDeviceKind;
  displayName?: string;
  model?: string;
  online?: boolean | null;
  onlineLabel?: string;
  batteryPercent?: number | null;
  rtkStatus?: string | null;
  rtkCount?: number | null;
  gpsCount?: number | null;
  positionFixed?: boolean | null;
  latitude?: number | null;
  longitude?: number | null;
  altitude?: number | null;
  updatedAt?: number | null;
  optionLabel?: string;
}
