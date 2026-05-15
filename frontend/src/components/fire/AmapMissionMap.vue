<script setup lang="ts">
import { onMounted, ref } from 'vue'
import AMapLoader from '@amap/amap-jsapi-loader'
import { CURRENT_CONFIG } from '/@/api/http/config'
import type { WaypointDTO } from '/@/types/fire/waypoint'

const props = defineProps<{
  fireLat: number;
  fireLng: number;
  waypoints?: WaypointDTO[];
  windDirectionDeg?: number;
  windSpeed?: number;
  height?: string;
}>()

const mapRef = ref<HTMLDivElement>()

const COLOR: Record<string, string> = {
  TAKEOFF: '#1890ff',
  CLIMB: '#69c0ff',
  APPROACH: '#bae0ff',
  HOLD_UPWIND: '#fadb14',
  DROP: '#cf1322',
  EXIT: '#fa8c16',
  RETURN: '#52c41a',
}

onMounted(async () => {
  // M4T 没配 security code,AMap 2.0 浏览器只用 key 即可
  // @ts-ignore
  window._AMapSecurityConfig = { securityJsCode: '' }
  const AMap = await AMapLoader.load({
    key: (CURRENT_CONFIG as any).amapKey as string,
    version: '2.0',
    plugins: ['AMap.Marker', 'AMap.Polyline', 'AMap.Circle'],
  })

  const map = new AMap.Map(mapRef.value!, {
    center: [props.fireLng, props.fireLat],
    zoom: 17,
  })

  // 火点 marker(红色) — `new AMap.X(...)` 副作用本身就会挂到 map,赋值给 _xxx 满足 ESLint no-new
  const _fireMarker = new AMap.Marker({
    map,
    position: [props.fireLng, props.fireLat],
    title: '火点',
    icon: 'https://webapi.amap.com/theme/v1.3/markers/n/mark_r.png',
  })

  if (props.waypoints && props.waypoints.length > 0) {
    const path = props.waypoints.map((w) => [w.lng, w.lat])
    const _routeLine = new AMap.Polyline({ map, path, strokeColor: '#1890ff', strokeWeight: 3 })

    props.waypoints.forEach((w) => {
      const color = COLOR[w.waypointType] ?? '#1890ff'
      const _wpMarker = new AMap.Marker({
        map,
        position: [w.lng, w.lat],
        title: `P${w.waypointIndex}-${w.waypointType} (H${w.alt}m)`,
        label: {
          content: `<span style="color:${color};font-weight:bold">P${w.waypointIndex}</span>`,
          direction: 'top',
        },
      })
    })
  }

  if (props.windDirectionDeg != null) {
    const radius = Math.max(50, (props.windSpeed ?? 0) * 10)
    const _windCircle = new AMap.Circle({
      map,
      center: [props.fireLng, props.fireLat],
      radius,
      strokeColor: '#fadb14',
      strokeWeight: 2,
      fillColor: '#fadb14',
      fillOpacity: 0.15,
    })

    const arrowDeg = (props.windDirectionDeg + 180) % 360
    const arrowRad = (arrowDeg * Math.PI) / 180
    const arrowLen = 200 // 米;1 度纬度 ≈ 111320 m;1 度经度 ≈ 111320 * cos(lat) m
    const dLat = (arrowLen * Math.cos(arrowRad)) / 111320
    const dLng = (arrowLen * Math.sin(arrowRad)) / (111320 * Math.cos((props.fireLat * Math.PI) / 180))
    const _windArrow = new AMap.Polyline({
      map,
      path: [
        [props.fireLng, props.fireLat],
        [props.fireLng + dLng, props.fireLat + dLat],
      ],
      strokeColor: '#d4b106',
      strokeWeight: 3,
      strokeStyle: 'dashed',
    })
  }
})
</script>

<template>
  <div ref="mapRef" class="map-canvas" :style="{ height: height ?? '600px' }" />
</template>

<style scoped>
.map-canvas {
  width: 100%;
}
</style>
