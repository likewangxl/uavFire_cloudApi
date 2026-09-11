<template>
  <div class="cockpit-situation-map">
    <div class="situation-mode-switch">
      <button
        type="button"
        :class="{ active: viewMode === '2d' }"
        @click="activateFlatMode"
      >
        高清卫星影像
      </button>
      <button
        type="button"
        :class="{ active: viewMode === 'tellux' }"
        @click="activateTelluxMode"
      >
        三维城市
      </button>
    </div>

    <div
      v-show="viewMode === '2d' || telluxFallbackActive"
      ref="mapEl"
      class="situation-map-canvas"
    ></div>

    <section
      v-if="viewMode === 'tellux'"
      class="terrain-status-panel"
    >
      <div>
        <span class="section-meta">{{ telluxStatusLabel }}</span>
        <h4>三维地形预览</h4>
        <p>{{ telluxStatusText }}</p>
      </div>
      <div class="terrain-layer-summary">
        <span>火情 {{ layers.fireMarkers.length }}</span>
        <span>航线 {{ layers.routeLines.length }}</span>
        <span>飞机 {{ layers.aircraftMarkers.length }}</span>
      </div>
    </section>

    <div
      v-show="viewMode === 'tellux' && !telluxFallbackActive"
      ref="telluxEl"
      class="tellux-stage-canvas"
    ></div>

    <div v-if="selectedPopup" class="situation-popup">
      <strong>{{ selectedPopup.title }}</strong>
      <p>{{ selectedPopup.detail }}</p>
    </div>

    <div class="situation-layer-legend">
      <span class="danger">HIGH</span>
      <span class="warning">MED</span>
      <span class="safe">LOW / 在线</span>
      <span>虚线圈为定位误差/估算影响</span>
    </div>

    <div v-if="layers.unlocatedEvents.length" class="unlocated-strip">
      <span>待复核定位 {{ layers.unlocatedEvents.length }}</span>
      <small>{{ layers.unlocatedEvents.slice(0, 2).map(item => item.eventId).join(' / ') }}</small>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import maplibregl from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'
import { browserMapInitialView, createBrowserLocationControl } from '/@/hooks/browser-map-location.mjs'
import { buildTiandituStyle } from '/@/hooks/tianditu'
import { useUomAirspaceReferenceLayer } from '/@/hooks/use-uom-airspace-reference-layer'
import { loadTelluxModule } from './leadership-cockpit-situation.mjs'

const props = defineProps<{
  layers: any
  focusKey?: string
  showUomAirspace?: boolean
  browserLocation?: boolean
}>()

const emit = defineEmits(['select'])

const mapEl = ref<HTMLElement | null>(null)
const telluxEl = ref<HTMLElement | null>(null)
const viewMode = ref<'2d' | 'tellux'>('2d')
const terrainReady = ref(false)
const telluxStatus = ref<'idle' | 'loading' | 'loaded' | 'unconfigured' | 'error'>('idle')
const telluxFallbackActive = ref(false)
const selectedPopup = ref<{ title: string, detail: string } | null>(null)

let map: maplibregl.Map | null = null
const markers: maplibregl.Marker[] = []
const telluxDisposeRef = ref<null |(() => void)>(null)
const telluxModuleRef = ref<any>(null)
const uomAirspace = useUomAirspaceReferenceLayer(() => map)
let hasInitialViewportFit = false
let userCameraInteraction = false
let uomAirspaceTouched = false

const telluxModuleUrl = ((import.meta.env.VITE_TELLUX_MODULE_URL as string | undefined) || '').trim()
const quantizedMeshTerrainUrl = ((import.meta.env.VITE_TELLUX_QUANTIZED_MESH_URL as string | undefined) || '').trim()
const telluxImageryUrl = ((import.meta.env.VITE_TELLUX_IMAGERY_URL as string | undefined) || '').trim()
const city3dTilesUrl = ((import.meta.env.VITE_CITY_3D_TILES_URL as string | undefined) || '').trim()
const city3dProviderLabel = ((import.meta.env.VITE_CITY_3D_PROVIDER_LABEL as string | undefined) || '授权实景三维').trim()

const layers = computed(() => props.layers || {
  fireMarkers: [],
  errorCircles: [],
  routeLines: [],
  aircraftMarkers: [],
  unlocatedEvents: [],
  bounds: null
})

const telluxStatusLabel = computed(() => {
  if (telluxStatus.value === 'loaded' && city3dTilesUrl) return city3dProviderLabel
  if (telluxStatus.value === 'loaded') return 'Tellux Terrain'
  if (telluxStatus.value === 'loading') return 'Loading Tellux'
  if (telluxFallbackActive.value) return 'Terrain Fallback'
  return 'Tellux Unconfigured'
})

const telluxStatusText = computed(() => {
  if (telluxStatus.value === 'loaded') {
    if (city3dTilesUrl) return `已接入${city3dProviderLabel}城市模型，并叠加实时火情、航线与飞机态势。`
    return quantizedMeshTerrainUrl
      ? 'Tellux 已动态加载，并接入 quantized-mesh 地形源。'
      : 'Tellux 已动态加载；当前使用影像底图与 3D 业务覆盖物，等待真实地形源。'
  }
  if (telluxStatus.value === 'loading') return '正在动态加载 Tellux，不影响二维态势首屏。'
  if (telluxFallbackActive.value) return 'Tellux 未配置或加载失败，当前显示 MapLibre DEM 降级预览。'
  return '配置授权的 3D Tiles 城市模型后，将显示真实建筑纹理与城市空间。'
})

onMounted(async () => {
  await nextTick()
  initMap()
  renderLayers()
})

onBeforeUnmount(() => {
  unmountTelluxStage()
  uomAirspace.dispose()
  clearMarkers()
  map?.remove()
  map = null
})

watch(
  () => layers.value,
  () => {
    renderLayers()
    if (viewMode.value === 'tellux' && telluxStatus.value === 'loaded') {
      mountTelluxStage()
    }
  },
  { deep: true }
)

watch(
  () => props.focusKey,
  () => focusSelectedLayer()
)

watch(
  () => props.showUomAirspace,
  value => setUomAirspaceVisible(Boolean(value)),
)

function activateFlatMode () {
  viewMode.value = '2d'
  unmountTelluxStage()
  telluxFallbackActive.value = false
  if (!map || !map.loaded()) return
  map.setTerrain(null)
  setTerrainLayerVisibility('none')
  terrainReady.value = false
  map.easeTo({ pitch: 0, bearing: 0, duration: 450 })
}

async function activateTelluxMode () {
  viewMode.value = 'tellux'
  if (telluxStatus.value === 'loaded') {
    await mountTelluxStage()
    return
  }
  if (telluxStatus.value === 'loading') return
  if (!quantizedMeshTerrainUrl && !city3dTilesUrl && !telluxModuleUrl) {
    telluxStatus.value = 'unconfigured'
    activateFallbackTerrain()
    return
  }
  telluxStatus.value = 'loading'
  const result = await loadTelluxModule(telluxModuleUrl, () => import('./tellux-situation-adapter'))
  telluxStatus.value = result.status === 'loaded'
    ? 'loaded'
    : (result.status === 'error' ? 'error' : 'unconfigured')
  telluxModuleRef.value = result.module || null
  if (telluxStatus.value === 'loaded') {
    await mountTelluxStage()
    return
  }
  activateFallbackTerrain()
}

function activateFallbackTerrain () {
  telluxFallbackActive.value = true
  if (!map || !map.loaded()) return
  ensureTerrainSources()
  map.setTerrain({ source: 'situation-terrain-dem', exaggeration: 1.45 })
  setTerrainLayerVisibility('visible')
  terrainReady.value = true
  map.easeTo({ pitch: 62, bearing: -28, zoom: Math.max(map.getZoom(), 12), duration: 650 })
}

async function mountTelluxStage () {
  await nextTick()
  if (!telluxEl.value || !telluxModuleRef.value) return
  unmountTelluxStage()
  telluxFallbackActive.value = false
  if (map?.loaded()) {
    map.setTerrain(null)
    setTerrainLayerVisibility('none')
  }
  const adapter = telluxModuleRef.value.default || telluxModuleRef.value
  const options = {
    container: telluxEl.value,
    layers: layers.value,
    quantizedMeshTerrainUrl,
    imageryUrl: telluxImageryUrl,
    city3dTilesUrl,
    visualEffects: {
      atmosphere: true,
      clouds: true,
      sunlight: true,
      businessOnly: false
    }
  }
  const instance = typeof adapter === 'function'
    ? await adapter(options)
    : await (adapter.mountTelluxStage || adapter.mount || adapter.create)?.(options)
  telluxDisposeRef.value = typeof instance === 'function'
    ? instance
    : (instance?.destroy || instance?.dispose || null)
}

function unmountTelluxStage () {
  if (telluxDisposeRef.value) {
    telluxDisposeRef.value()
    telluxDisposeRef.value = null
  }
  if (telluxEl.value) {
    telluxEl.value.innerHTML = ''
  }
}

function initMap () {
  if (!mapEl.value || map) return
  const initial = browserMapInitialView(window.__UAVFIRE_SITE_LOCATION__)
  map = new maplibregl.Map({
    container: mapEl.value,
    style: buildTiandituStyle('satellite'),
    center: props.browserLocation ? initial.center : [109.32667, 34.66791],
    zoom: props.browserLocation ? initial.zoom : 10.8,
    pitch: 0,
    maxPitch: 78,
    antialias: true,
    attributionControl: false
  })
  map.addControl(new maplibregl.ScaleControl({ maxWidth: 120, unit: 'metric' }), 'bottom-left')
  if (props.browserLocation) map.addControl(createBrowserLocationControl(), 'bottom-right')
  map.on('dragstart', markUserCameraInteraction)
  map.on('zoomstart', markUserCameraInteraction)
  map.on('load', renderLayers)
}

function renderLayers () {
  if (!map) return
  if (!map.loaded()) return
  clearMarkers()
  ensureVectorSources()
  setUomAirspaceVisible(Boolean(props.showUomAirspace))
  renderVectorLayers()
  renderMarkers()
  fitInitialSituationBounds()
}

function markUserCameraInteraction () {
  userCameraInteraction = true
}

function clearMarkers () {
  while (markers.length) {
    markers.pop()?.remove()
  }
}

function ensureVectorSources () {
  if (!map) return
  if (viewMode.value === 'tellux' && telluxFallbackActive.value) {
    ensureTerrainSources()
  }
  if (!map.getSource('situation-routes')) {
    map.addSource('situation-routes', emptyCollection())
    map.addLayer({
      id: 'situation-routes-line',
      type: 'line',
      source: 'situation-routes',
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: { 'line-color': ['get', 'color'], 'line-width': 3, 'line-dasharray': [1.5, 1] }
    })
  }
  if (!map.getSource('situation-circles')) {
    map.addSource('situation-circles', emptyCollection())
    map.addLayer({
      id: 'situation-circles-fill',
      type: 'fill',
      source: 'situation-circles',
      paint: { 'fill-color': ['get', 'color'], 'fill-opacity': 0.14 }
    })
    map.addLayer({
      id: 'situation-circles-outline',
      type: 'line',
      source: 'situation-circles',
      paint: { 'line-color': ['get', 'color'], 'line-width': 2, 'line-dasharray': [2, 1.2] }
    })
  }
}

function ensureTerrainSources () {
  if (!map) return
  if (!map.getSource('situation-terrain-dem')) {
    map.addSource('situation-terrain-dem', {
      type: 'raster-dem',
      url: 'https://demotiles.maplibre.org/terrain-tiles/tiles.json',
      tileSize: 256
    } as any)
  }
  if (!map.getLayer('situation-terrain-shade')) {
    map.addLayer({
      id: 'situation-terrain-shade',
      type: 'hillshade',
      source: 'situation-terrain-dem',
      layout: { visibility: viewMode.value === 'tellux' && telluxFallbackActive.value ? 'visible' : 'none' },
      paint: {
        'hillshade-shadow-color': 'rgba(2, 8, 14, 0.72)',
        'hillshade-highlight-color': 'rgba(110, 220, 255, 0.38)',
        'hillshade-accent-color': 'rgba(255, 216, 102, 0.28)'
      }
    })
  }
}

function setTerrainLayerVisibility (visibility: 'visible' | 'none') {
  if (map?.getLayer('situation-terrain-shade')) {
    map.setLayoutProperty('situation-terrain-shade', 'visibility', visibility)
  }
}

function setUomAirspaceVisible (visible: boolean) {
  if (visible) {
    uomAirspaceTouched = true
    uomAirspace.setVisible(true)
    return
  }
  if (uomAirspaceTouched) {
    uomAirspace.setVisible(false)
  }
}

function renderVectorLayers () {
  const routes = map?.getSource('situation-routes') as maplibregl.GeoJSONSource | undefined
  const circles = map?.getSource('situation-circles') as maplibregl.GeoJSONSource | undefined
  routes?.setData({
    type: 'FeatureCollection',
    features: layers.value.routeLines.map((route: any) => ({
      type: 'Feature',
      properties: { id: route.id, color: route.color },
      geometry: { type: 'LineString', coordinates: route.coordinates }
    }))
  } as any)
  circles?.setData({
    type: 'FeatureCollection',
    features: layers.value.errorCircles.map((circle: any) => ({
      type: 'Feature',
      properties: { id: circle.id, color: circle.color },
      geometry: { type: 'Polygon', coordinates: [circleRing(circle.center, circle.radiusM)] }
    }))
  } as any)
}

function renderMarkers () {
  for (const marker of layers.value.fireMarkers) {
    addMarker(marker.coordinates, `fire ${marker.tone}`, marker.popup, marker.id)
  }
  for (const marker of layers.value.aircraftMarkers) {
    addMarker(marker.coordinates, `aircraft ${marker.tone}`, marker.popup, marker.id)
  }
}

function addMarker (coordinates: [number, number], className: string, popup: any, key: string) {
  if (!map) return
  const el = document.createElement('button')
  el.type = 'button'
  el.className = `situation-marker ${className}`
  el.title = popup?.title || key
  el.addEventListener('click', () => {
    selectedPopup.value = popup
    emit('select', key)
  })
  const marker = new maplibregl.Marker({ element: el, anchor: 'center' })
    .setLngLat(coordinates)
    .addTo(map)
  markers.push(marker)
}

function fitInitialSituationBounds () {
  if (props.browserLocation) return
  if (hasInitialViewportFit || userCameraInteraction) return
  const b = layers.value.bounds
  if (!map || !b) return
  hasInitialViewportFit = true
  if (b.west === b.east && b.south === b.north) {
    map.easeTo({ center: [b.west, b.south], zoom: 13, duration: 450 })
    return
  }
  map.fitBounds([[b.west, b.south], [b.east, b.north]], {
    padding: 72,
    duration: 450,
    maxZoom: 14
  })
}

function focusSelectedLayer () {
  if (!map || !props.focusKey) return
  const fire = layers.value.fireMarkers.find((item: any) => item.id === props.focusKey || item.eventId === props.focusKey)
  const aircraft = layers.value.aircraftMarkers.find((item: any) => item.id === props.focusKey)
  const target = fire || aircraft
  if (!target) return
  userCameraInteraction = true
  selectedPopup.value = target.popup
  map.easeTo({ center: target.coordinates, zoom: 14, duration: 500 })
}

function emptyCollection () {
  return { type: 'geojson', data: { type: 'FeatureCollection', features: [] } }
}

function circleRing (center: [number, number], radiusM: number, steps = 72) {
  const [lng, lat] = center
  const dLat = radiusM / 111320
  const dLng = radiusM / (111320 * Math.cos((lat * Math.PI) / 180))
  const ring = []
  for (let i = 0; i <= steps; i += 1) {
    const t = (i / steps) * 2 * Math.PI
    ring.push([lng + dLng * Math.cos(t), lat + dLat * Math.sin(t)])
  }
  return ring
}
</script>

<style lang="scss" scoped>
.cockpit-situation-map {
  position: relative;
  min-width: 0;
  min-height: 0;
  height: 100%;
  border-radius: 26px;
  overflow: hidden;
  border: 1px solid rgba(69, 221, 255, 0.16);
  background: #05101c;
}

.situation-map-canvas {
  position: absolute;
  inset: 0;
}

.tellux-stage-canvas {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(circle at 50% 28%, rgba(69, 221, 255, 0.16), transparent 36%),
    linear-gradient(180deg, #06131f, #02070d);
}

.situation-mode-switch {
  position: absolute;
  top: 14px;
  right: 14px;
  z-index: 8;
  display: inline-flex;
  gap: 6px;
  padding: 5px;
  border: 1px solid rgba(69, 221, 255, 0.28);
  border-radius: 999px;
  background: rgba(4, 14, 24, 0.76);
  backdrop-filter: blur(8px);
}

.situation-mode-switch button {
  min-height: 28px;
  border: 0;
  border-radius: 999px;
  padding: 0 12px;
  color: #9bc7df;
  background: transparent;
  font-size: 12px;
  cursor: pointer;
}

.situation-mode-switch button.active {
  color: #ffffff;
  background: rgba(45, 140, 240, 0.9);
}

:deep(.situation-marker) {
  width: 18px;
  height: 18px;
  border: 2px solid rgba(255, 255, 255, 0.72);
  border-radius: 50%;
  cursor: pointer;
  box-shadow: 0 0 18px currentColor;
}

:deep(.situation-marker.fire.danger) {
  color: #ff6172;
  background: #ff6172;
}

:deep(.situation-marker.fire.warning) {
  color: #ffd866;
  background: #ffd866;
}

:deep(.situation-marker.fire.safe) {
  color: #42e29d;
  background: #42e29d;
}

:deep(.situation-marker.aircraft) {
  width: 20px;
  height: 20px;
  border-radius: 5px;
  color: #45ddff;
  background: #45ddff;
  transform: rotate(45deg);
}

:deep(.maplibregl-ctrl-bottom-left) {
  left: 14px;
  bottom: 52px;
  z-index: 8;
}

:deep(.maplibregl-ctrl-bottom-left .maplibregl-ctrl) {
  margin: 0;
}

.terrain-status-panel,
.situation-layer-legend,
.unlocated-strip,
.situation-popup {
  position: absolute;
  z-index: 7;
  border: 1px solid rgba(69, 221, 255, 0.18);
  border-radius: 8px;
  background: rgba(4, 14, 24, 0.76);
  backdrop-filter: blur(8px);
}

.terrain-status-panel {
  top: 62px;
  right: 14px;
  display: grid;
  gap: 6px;
  width: min(300px, calc(100% - 28px));
  padding: 10px 12px;
}

.terrain-status-panel strong {
  color: #effbff;
  font-size: 13px;
}

.terrain-status-panel > span {
  color: #9fc5dc;
  font-size: 12px;
}

.terrain-layer-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.terrain-layer-summary small,
.situation-layer-legend span,
.unlocated-strip span,
.unlocated-strip small {
  color: #b9d8ed;
  font-size: 12px;
}

.terrain-layer-summary small,
.situation-layer-legend span {
  padding: 5px 8px;
}

.situation-layer-legend {
  left: 14px;
  bottom: 14px;
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  max-width: calc(100% - 28px);
}

.situation-layer-legend .danger {
  color: #ff9da7;
}

.situation-layer-legend .warning {
  color: #ffe39a;
}

.situation-layer-legend .safe {
  color: #91f0c1;
}

.unlocated-strip {
  left: 14px;
  top: 14px;
  display: flex;
  align-items: center;
  gap: 8px;
  max-width: min(420px, calc(100% - 220px));
  padding: 8px 10px;
}

.unlocated-strip small {
  color: #87a9c0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.situation-popup {
  right: 14px;
  bottom: 62px;
  width: min(360px, calc(100% - 28px));
  padding: 12px 14px;
}

.situation-popup strong {
  display: block;
  color: #effbff;
  font-size: 14px;
  line-height: 1.4;
}

.situation-popup p {
  margin: 8px 0 0;
  color: #a9c5d7;
  font-size: 12px;
  line-height: 1.6;
}
</style>
