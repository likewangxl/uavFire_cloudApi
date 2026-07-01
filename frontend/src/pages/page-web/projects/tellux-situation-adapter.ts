import tellux from 'tellux'
import * as THREE from 'three'
import { CURRENT_CONFIG } from '/@/api/http/config'

type LngLat = [number, number]

interface SituationLayers {
  fireMarkers: any[]
  errorCircles: any[]
  routeLines: any[]
  aircraftMarkers: any[]
  bounds?: { west: number, south: number, east: number, north: number } | null
}

interface TelluxSituationOptions {
  container: HTMLElement
  layers: SituationLayers
  quantizedMeshTerrainUrl?: string
  imageryUrl?: string
  visualEffects?: {
    atmosphere?: boolean
    clouds?: boolean
    sunlight?: boolean
  }
}

export default async function mountTelluxSituationStage (options: TelluxSituationOptions) {
  const viewer = new tellux.Viewer(options.container, {
    terrain: options.quantizedMeshTerrainUrl
      ? { url: options.quantizedMeshTerrainUrl }
      : undefined,
    layers: buildBaseLayers(options),
    camera: buildCamera(options.layers),
    scene: {
      atmosphere: {
        show: options.visualEffects?.atmosphere !== false,
        lighting: {
          mode: 'light-source',
          sunLight: options.visualEffects?.sunlight !== false,
          skyLight: true,
          sunLightIntensity: 1.16,
          skyLightIntensity: 0.72
        },
        night: {
          enabled: true,
          ambientIntensity: 0.14,
          moonLightIntensity: 0.18
        },
        sky: {
          stars: true,
          sun: true,
          moon: true
        }
      },
      clouds: {
        show: options.visualEffects?.clouds !== false,
        quality: 'medium',
        coverage: 0.2,
        lightShafts: true
      },
      surface: {
        materialMode: 'standard'
      },
      postProcess: {
        lensFlare: true,
        smaa: true,
        dithering: true
      }
    },
    resolutionScale: Math.min(window.devicePixelRatio || 1, 1.5),
    dracoDecoderPath: '/draco/gltf/'
  })

  viewer.toneMappingExposure = 4.8

  addBusinessGeoJsonLayers(viewer, options.layers)
  const businessRoot = new THREE.Group()
  businessRoot.name = 'cockpit-situation-business-overlays'
  viewer.scene.threeScene.add(businessRoot)
  addFireObjects(viewer, businessRoot, options.layers.fireMarkers || [])
  addAircraftObjects(viewer, businessRoot, options.layers.aircraftMarkers || [])
  addRouteObjects(viewer, businessRoot, options.layers.routeLines || [])

  return () => {
    viewer.scene.threeScene.remove(businessRoot)
    businessRoot.traverse((object: any) => {
      object.geometry?.dispose?.()
      if (Array.isArray(object.material)) {
        object.material.forEach((material: any) => material.dispose?.())
      } else {
        object.material?.dispose?.()
      }
    })
    viewer.destroy()
  }
}

function buildBaseLayers (options: TelluxSituationOptions) {
  const imageryUrl = options.imageryUrl || tiandituTileUrl('img')
  const layers: any[] = []
  if (imageryUrl) {
    layers.push({
      id: 'situation-imagery',
      name: '天地图影像',
      source: { type: 'xyz', url: imageryUrl, levels: 18, tileDimension: 256 },
      style: { opacity: 0.92 }
    })
  }
  const labelUrl = tiandituTileUrl('cia')
  if (labelUrl) {
    layers.push({
      id: 'situation-labels',
      name: '天地图注记',
      source: { type: 'xyz', url: labelUrl, levels: 18, tileDimension: 256 },
      style: { opacity: 0.78 }
    })
  }
  return layers
}

function addBusinessGeoJsonLayers (viewer: any, layers: SituationLayers) {
  const routeFeatures = (layers.routeLines || []).map(route => ({
    type: 'Feature',
    properties: { color: route.color || '#45ddff' },
    geometry: { type: 'LineString', coordinates: route.coordinates || [] }
  }))
  if (routeFeatures.length) {
    viewer.layers.add({
      id: 'situation-route-drape',
      name: '任务航线投影',
      source: { type: 'geojson', geojson: { type: 'FeatureCollection', features: routeFeatures }, resolution: 512 },
      style: {
        stroke: '#45ddff',
        strokeWidth: 2,
        opacity: 0.86,
        getStyle: (_feature: any, properties: any) => ({ stroke: String(properties?.color || '#45ddff'), strokeWidth: 2 })
      }
    })
  }

  const circleFeatures = (layers.errorCircles || []).map(circle => ({
    type: 'Feature',
    properties: { color: circle.color || '#ff6172' },
    geometry: { type: 'Polygon', coordinates: [circleRing(circle.center, circle.radiusM)] }
  }))
  if (circleFeatures.length) {
    viewer.layers.add({
      id: 'situation-error-circles',
      name: '定位误差圈',
      source: { type: 'geojson', geojson: { type: 'FeatureCollection', features: circleFeatures }, resolution: 512 },
      style: {
        fill: 'rgba(255, 97, 114, 0.2)',
        stroke: '#ff6172',
        strokeWidth: 1.4,
        opacity: 0.72,
        getStyle: (_feature: any, properties: any) => ({
          fill: alphaColor(String(properties?.color || '#ff6172'), 0.2),
          stroke: String(properties?.color || '#ff6172'),
          strokeWidth: 1.4
        })
      }
    })
  }
}

function addFireObjects (viewer: any, root: THREE.Group, markers: any[]) {
  markers.forEach(marker => {
    const [lng, lat] = marker.coordinates
    const group = new THREE.Group()
    group.name = marker.id || `fire-${lng}-${lat}`
    group.matrixAutoUpdate = false
    group.matrix.copy(viewer.cartographicToMatrix4([lng, lat, 35]))

    const color = new THREE.Color(marker.color || '#ff6172')
    const beam = new THREE.Mesh(
      new THREE.CylinderGeometry(18, 52, 260, 32, 1, true),
      new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.32, side: THREE.DoubleSide, blending: THREE.AdditiveBlending })
    )
    beam.position.y = 130
    group.add(beam)

    const glow = new THREE.Mesh(
      new THREE.SphereGeometry(66, 32, 18),
      new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.48, blending: THREE.AdditiveBlending })
    )
    glow.position.y = 280
    group.add(glow)

    const core = new THREE.Mesh(
      new THREE.SphereGeometry(22, 24, 14),
      new THREE.MeshStandardMaterial({ color: '#fff0a8', emissive: color, emissiveIntensity: 1.6, roughness: 0.3 })
    )
    core.position.y = 280
    group.add(core)

    const light = new THREE.PointLight(color, 1.4, 760)
    light.position.y = 320
    group.add(light)

    root.add(group)
  })
}

function addAircraftObjects (viewer: any, root: THREE.Group, markers: any[]) {
  markers.forEach(marker => {
    const [lng, lat] = marker.coordinates
    const group = new THREE.Group()
    group.name = marker.id || `aircraft-${lng}-${lat}`
    group.matrixAutoUpdate = false
    group.matrix.copy(viewer.cartographicToMatrix4([lng, lat, 180]))

    const color = new THREE.Color(marker.online ? '#42e29d' : '#ff6172')
    const body = new THREE.Mesh(
      new THREE.BoxGeometry(90, 18, 34),
      new THREE.MeshStandardMaterial({ color, emissive: color, emissiveIntensity: 0.75, roughness: 0.42 })
    )
    body.position.y = 0
    group.add(body)

    const wing = new THREE.Mesh(
      new THREE.BoxGeometry(24, 8, 122),
      new THREE.MeshStandardMaterial({ color: '#bdf7ff', emissive: color, emissiveIntensity: 0.28, roughness: 0.5 })
    )
    wing.position.y = 2
    group.add(wing)

    const halo = new THREE.Mesh(
      new THREE.TorusGeometry(72, 4, 10, 48),
      new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.62, blending: THREE.AdditiveBlending })
    )
    halo.rotation.x = Math.PI / 2
    halo.position.y = -46
    group.add(halo)

    root.add(group)
  })
}

function addRouteObjects (viewer: any, root: THREE.Group, routes: any[]) {
  routes.forEach(route => {
    const points = (route.coordinates || [])
      .map((coordinate: LngLat) => viewer.cartographicToVector3([coordinate[0], coordinate[1], 110]))
      .filter(Boolean)
    if (points.length < 2) return
    const line = new THREE.Line(
      new THREE.BufferGeometry().setFromPoints(points),
      new THREE.LineBasicMaterial({ color: route.color || '#45ddff', transparent: true, opacity: 0.82 })
    )
    line.name = route.id || 'situation-route'
    root.add(line)
  })
}

function buildCamera (layers: SituationLayers) {
  const bounds = layers.bounds
  if (!bounds) {
    return { latitude: 34.66791, longitude: 109.32667, height: 26000, heading: -35, pitch: -32 }
  }
  const latitude = (bounds.north + bounds.south) / 2
  const longitude = (bounds.east + bounds.west) / 2
  const span = Math.max(Math.abs(bounds.east - bounds.west), Math.abs(bounds.north - bounds.south))
  return {
    latitude,
    longitude,
    height: Math.max(4200, span * 105000),
    heading: -38,
    pitch: -34
  }
}

function tiandituTileUrl (layer: 'img' | 'cia') {
  const tk = (CURRENT_CONFIG as any).tiandituKey
  if (!tk) return ''
  return `https://t0.tianditu.gov.cn/${layer}_w/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=${layer}&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&tk=${tk}`
}

function circleRing (center: LngLat, radiusM: number, steps = 96) {
  if (!center || !Number.isFinite(radiusM) || radiusM <= 0) return []
  const [lng, lat] = center
  const dLat = radiusM / 111320
  const dLng = radiusM / (111320 * Math.max(Math.cos((lat * Math.PI) / 180), 0.08))
  const ring = []
  for (let i = 0; i <= steps; i += 1) {
    const t = (i / steps) * 2 * Math.PI
    ring.push([lng + dLng * Math.cos(t), lat + dLat * Math.sin(t)])
  }
  return ring
}

function alphaColor (color: string, alpha: number) {
  if (!color.startsWith('#') || color.length !== 7) return `rgba(69, 221, 255, ${alpha})`
  const value = Number.parseInt(color.slice(1), 16)
  const r = (value >> 16) & 255
  const g = (value >> 8) & 255
  const b = value & 255
  return `rgba(${r}, ${g}, ${b}, ${alpha})`
}
