export const TRIAL_EXPIRES_AT_ISO = '2026-09-30T16:00:00.000Z'
export const TRIAL_EXPIRES_AT_EPOCH_MS = Date.parse(TRIAL_EXPIRES_AT_ISO)
export const TRIAL_EXPIRES_AT_DISPLAY = '2026 年 10 月 1 日 00:00（北京时间）'

export function isTrialExpired (nowMs = Date.now()) {
  return nowMs >= TRIAL_EXPIRES_AT_EPOCH_MS
}
