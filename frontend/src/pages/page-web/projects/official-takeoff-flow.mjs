export const OFFICIAL_TAKEOFF_MAX_SPEED = 5
export const OFFICIAL_TAKEOFF_TARGET_HEIGHT = 50
export const OFFICIAL_TAKEOFF_STAGE1_OFFSET_DEG = 0.00015
export const OFFICIAL_TAKEOFF_STAGE2_OFFSET_DEG = 0.0018
export const OFFICIAL_TAKEOFF_STAGE2_STABILIZATION_MS = 1500

export function buildOfficialTakeoffPlan ({ latitude, longitude, absoluteHeight }) {
  const baseAbsoluteHeight = Number(absoluteHeight)
  const targetAbsoluteHeight = baseAbsoluteHeight + OFFICIAL_TAKEOFF_TARGET_HEIGHT

  return {
    stage1: {
      targetLatitude: latitude + OFFICIAL_TAKEOFF_STAGE1_OFFSET_DEG,
      targetLongitude: longitude,
      targetHeight: targetAbsoluteHeight,
      securityTakeoffHeight: OFFICIAL_TAKEOFF_TARGET_HEIGHT,
      commanderFlightHeight: OFFICIAL_TAKEOFF_TARGET_HEIGHT,
      maxSpeed: OFFICIAL_TAKEOFF_MAX_SPEED,
    },
    stage2South: {
      targetLatitude: latitude - OFFICIAL_TAKEOFF_STAGE2_OFFSET_DEG,
      targetLongitude: longitude,
      targetHeight: targetAbsoluteHeight,
      maxSpeed: OFFICIAL_TAKEOFF_MAX_SPEED,
    },
    stage2North: {
      targetLatitude: latitude,
      targetLongitude: longitude,
      targetHeight: targetAbsoluteHeight,
      maxSpeed: OFFICIAL_TAKEOFF_MAX_SPEED,
    },
  }
}

export function isOfficialTakeoffProgressSuccessStatus (status) {
  return status === 'wayline_ok' || status === 'task_finish'
}

export function isOfficialTakeoffProgressFailureStatus (status) {
  return status === 'wayline_failed' || status === 'wayline_cancel'
}
