export interface WaypointDTO {
  waypointIndex: number;
  waypointType: string;
  lat: number;
  lng: number;
  alt: number;
  altitudeReference: string | null;
  speed: number | null;
  action: string | null;
  remark: string | null;
}
