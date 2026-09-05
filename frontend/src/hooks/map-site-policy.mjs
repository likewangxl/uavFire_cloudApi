import { gcj02towgs84 } from '../vendors/coordtransform.js'

export function validMapPoint (point) {
  return Array.isArray(point) && point.length === 2 && point.every(Number.isFinite) &&
    Math.abs(point[0]) <= 180 && Math.abs(point[1]) <= 85 &&
    !(point[0] === 0 && point[1] === 0)
}

export function resolveSiteView (site) {
  if (!site || !['WGS84', 'GCJ02'].includes(site.coordinateSystem)) return null
  const point = [site.longitude, site.latitude]
  if (!validMapPoint(point)) return null
  const center = site.coordinateSystem === 'GCJ02' ? gcj02towgs84(...point) : point
  return { center, zoom: Number.isFinite(site.zoom) && site.zoom >= 2 && site.zoom <= 20 ? site.zoom : 17 }
}

export function resolveInitialMapView (site, cached, fallback) {
  const siteView = resolveSiteView(site)
  if (siteView) return { ...siteView, source: 'site' }
  if (cached && validMapPoint(cached.center)) {
    return { center: cached.center, zoom: Number.isFinite(cached.zoom) && cached.zoom >= 2 && cached.zoom <= 20 ? cached.zoom : 17, source: 'history' }
  }
  return { ...fallback, source: 'unconfigured' }
}
