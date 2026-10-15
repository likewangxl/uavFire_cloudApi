export const TRIAL_EXPIRES_AT_ISO = '2026-09-30T16:00:00.000Z'
export const TRIAL_EXPIRES_AT_EPOCH_MS = Date.parse(TRIAL_EXPIRES_AT_ISO)
export const TRIAL_EXPIRES_AT_DISPLAY = '2026 年 10 月 1 日 00:00（北京时间）'

export function resolveTrialDecision (policy, nowMs = Date.now()) {
  const mode = String(policy?.mode || 'off').toLowerCase()
  if (mode !== 'until') {
    return {
      expired: false,
      display: policy?.display || '试用截止未启用',
      expiresAtMs: null,
    }
  }
  const expiresAtMs = Date.parse(policy?.expiresAt || TRIAL_EXPIRES_AT_ISO)
  if (!Number.isFinite(expiresAtMs)) {
    return {
      expired: true,
      display: '试用到期时间无效',
      expiresAtMs: null,
    }
  }
  return {
    expired: nowMs >= expiresAtMs,
    display: policy?.display || TRIAL_EXPIRES_AT_DISPLAY,
    expiresAtMs,
  }
}

export function isTrialExpired (nowMs = Date.now()) {
  return resolveTrialDecision({
    mode: 'until',
    expiresAt: TRIAL_EXPIRES_AT_ISO,
  }, nowMs).expired
}
