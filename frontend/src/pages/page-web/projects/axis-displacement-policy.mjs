export const AXIS_DISTANCE_MIN_METERS = 1
export const AXIS_DISTANCE_MAX_METERS = 200
export const AXIS_VERTICAL_COMMAND_MS_PER_METER = 800

export function getLatitudeOffsetForMeters ({ meters, direction }) {
  const normalizedMeters = Math.abs(Number(meters) || 0)
  const rawOffset = normalizedMeters / 111320
  return direction === 'south' ? -rawOffset : rawOffset
}

export function getLongitudeOffsetForMeters ({ latitude, meters, direction }) {
  const normalizedMeters = Math.abs(Number(meters) || 0)
  const latRadians = (Number(latitude) || 0) * Math.PI / 180
  const metersPerDegreeLongitude = 111320 * Math.cos(latRadians)
  if (!Number.isFinite(metersPerDegreeLongitude) || metersPerDegreeLongitude <= 0) {
    return 0
  }
  const rawOffset = normalizedMeters / metersPerDegreeLongitude
  return direction === 'west' ? -rawOffset : rawOffset
}

export function buildAxisFlyToTarget ({
  latitude,
  longitude,
  height,
  meters,
  direction,
}) {
  if (direction === 'north' || direction === 'south') {
    return {
      latitude: latitude + getLatitudeOffsetForMeters({ meters, direction }),
      longitude,
      height,
    }
  }
  return {
    latitude,
    longitude: longitude + getLongitudeOffsetForMeters({ latitude, meters, direction }),
    height,
  }
}

export function getVerticalCommandDurationMs (meters) {
  const normalizedMeters = Math.max(AXIS_DISTANCE_MIN_METERS, Number(meters) || AXIS_DISTANCE_MIN_METERS)
  return Math.round(normalizedMeters * AXIS_VERTICAL_COMMAND_MS_PER_METER)
}
