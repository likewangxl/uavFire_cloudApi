<template>
  <div class="patrol-map">
    <div ref="container" class="map-canvas" aria-label="示例巡护区态势地图"></div>
    <div class="map-caption"><strong>秦岭北麓 · 示范巡护区</strong><span>点位与航迹均为演示数据</span></div>
    <div class="map-layer-controls">
      <button :class="{ selected: showEvents }" @click="showEvents = !showEvents; refreshPins()"><FireOutlined /> 火情</button>
      <button :class="{ selected: showAircraft }" @click="showAircraft = !showAircraft; refreshPins()"><SendOutlined /> 飞机</button>
      <button aria-label="恢复地图范围" @click="reset"><AimOutlined /></button>
    </div>
    <div v-if="mapError" class="map-network-note">底图暂不可用 · 示例点位仍可查看</div>
    <div class="map-key"><span><i class="legend-dot orange"></i>待处理火情</span><span><i class="legend-dot blue"></i>巡护飞机</span></div>
  </div>
</template>
<script setup>
import { ref, onMounted, onBeforeUnmount, watch, h, render } from 'vue'
import maplibregl from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'
import { FireOutlined, SendOutlined, AimOutlined } from '@ant-design/icons-vue'
const props = defineProps({ events: Array, devices: Array, focus: Object })
const emit = defineEmits(['select-event', 'select-device'])
const container = ref(null)
const showEvents = ref(true), showAircraft = ref(true), mapError = ref(false)
let map, pins = []
function reset () { map?.fitBounds([[108.735, 34.0], [109.02, 34.185]], { padding: 65, duration: 600 }) }
function refreshPins () {
  if (!map) return
  for (const { marker, el } of pins) { render(null, el); marker.remove() }
  pins = []
  const items = [
    ...(showAircraft.value ? (props.devices || []).filter(d => d.id <= 8).map(d => ({ ...d, kind: 'device' })) : []),
    ...(showEvents.value ? (props.events || []).filter(e => !['已排除', '已结束'].includes(e.state)).map(e => ({ ...e, kind: 'event' })) : [])
  ]
  for (const item of items) {
    const el = document.createElement('button')
    el.className = `map-pin ${item.kind}`
    el.setAttribute('aria-label', item.kind === 'event' ? `查看事件：${item.title}` : `查看飞机：${item.name}`)
    render(h(item.kind === 'event' ? FireOutlined : SendOutlined), el)
    el.onclick = () => emit(item.kind === 'event' ? 'select-event' : 'select-device', item)
    const marker = new maplibregl.Marker({ element: el }).setLngLat(item.coord).addTo(map)
    // MapLibre supplies its own generic label during construction; restore the meaningful label.
    el.setAttribute('aria-label', item.kind === 'event' ? `查看事件：${item.title}` : `查看飞机：${item.name}`)
    pins.push({ marker, el })
  }
}
watch(() => [props.events, props.devices], refreshPins, { deep: true })
watch(() => props.focus, focus => { if (focus?.coord) map?.flyTo({ center: focus.coord, zoom: 12, duration: 600 }) })
onMounted(() => {
  map = new maplibregl.Map({
    container: container.value, center: [108.88, 34.09], zoom: 11.1, maxZoom: 15, minZoom: 9,
    style: { version: 8, sources: { base: { type: 'raster', tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'], tileSize: 256, attribution: '© <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap contributors</a>' } }, layers: [{ id: 'base', type: 'raster', source: 'base' }] },
    attributionControl: { compact: false }
  })
  map.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'bottom-right')
  map.on('error', () => { mapError.value = true })
  map.on('load', () => {
    reset()
    map.addSource('patrol', { type: 'geojson', data: { type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: [[108.785,34.065],[108.84,34.04],[108.88,34.06],[108.89,34.12],[108.94,34.1],[108.965,34.15]] } } })
    map.addLayer({ id: 'patrol', type: 'line', source: 'patrol', paint: { 'line-color': '#5a9bfb', 'line-width': 2, 'line-opacity': 0.6, 'line-dasharray': [3, 3] } })
  })
  refreshPins()
})
onBeforeUnmount(() => { for (const { el } of pins) render(null, el); map?.remove() })
</script>
