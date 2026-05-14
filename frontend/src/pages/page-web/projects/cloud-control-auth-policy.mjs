function normalizeControlKeys (keys) {
  if (!Array.isArray(keys)) return []
  return keys.filter((key) => typeof key === 'string')
}

export function getCloudControlAuthState (host) {
  const controlKeys = normalizeControlKeys(
    host?.cloud_control_auth ?? host?.cloudControlAuth ?? host?.controlKeys
  )

  if (typeof host?.authorized === 'boolean') {
    return {
      authorized: host.authorized,
      controlKeys,
    }
  }

  if (typeof host?.is_cloud_control_auth === 'boolean') {
    return {
      authorized: host.is_cloud_control_auth,
      controlKeys,
    }
  }

  if (typeof host?.isCloudControlAuth === 'boolean') {
    return {
      authorized: host.isCloudControlAuth,
      controlKeys,
    }
  }

  return {
    authorized: controlKeys.includes('flight'),
    controlKeys,
  }
}
