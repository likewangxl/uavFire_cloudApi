const snapshotPathPrefix = '/api/v1/snapshots'
const loopbackHosts = new Set(['127.0.0.1', 'localhost', '::1', '[::1]'])

/**
 * Historical fire events may contain an absolute ai-service URL using a
 * loopback host. That host is only valid on the server which created the
 * event; on RC Plus it points back to the controller itself. Convert these
 * URLs to the existing same-origin snapshot route so Vite/nginx can proxy the
 * request to ai-service without persisting a LAN address in event data.
 */
export function normalizeSnapshotUrl (url) {
  if (typeof url !== 'string' || url.length === 0 || url.startsWith(snapshotPathPrefix)) {
    return url
  }

  let parsed
  try {
    parsed = new URL(url)
  } catch {
    return url
  }

  const isSnapshotPath = parsed.pathname === snapshotPathPrefix || parsed.pathname.startsWith(snapshotPathPrefix + '/')
  if (!isSnapshotPath || !loopbackHosts.has(parsed.hostname)) {
    return url
  }

  return parsed.pathname + parsed.search + parsed.hash
}

const imageUrlFields = new Set(['visibleImageUrl', 'thermalImageUrl'])

export function normalizeFireSnapshotUrls (value) {
  if (Array.isArray(value)) {
    return value.map(normalizeFireSnapshotUrls)
  }
  if (value === null || typeof value !== 'object' || value.constructor !== Object) {
    return value
  }

  const normalized = {}
  for (const [key, child] of Object.entries(value)) {
    normalized[key] = imageUrlFields.has(key)
      ? normalizeSnapshotUrl(child)
      : normalizeFireSnapshotUrls(child)
  }
  return normalized
}
