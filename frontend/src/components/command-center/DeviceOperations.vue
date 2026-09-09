<template>
  <section class="cc-device-operations cc-panel">
    <div class="cc-device-operation-head">
      <div><h2>{{ device?.deviceName || device?.aircraftSn || '飞机操作' }}</h2><p class="cc-muted">{{ context.hint }}</p></div>
      <button type="button" class="cc-button" @click="$emit('close')">收起操作</button>
    </div>
    <div class="cc-detection-controls">
      <span>火情识别：{{ statusKnown ? running ? '运行中' : '未启动' : '状态待确认' }}</span>
      <button type="button" class="cc-button" :disabled="busy || !statusKnown || !context.ready || (!running && !context.detectionAllowed)" @click="toggleDetection">{{ busy ? '处理中…' : running ? '停止识别' : '启动识别' }}</button>
      <button type="button" class="cc-text-button" :disabled="busy" @click="readStatus">刷新状态</button>
    </div>
    <p v-if="context.detectionHint" class="cc-muted">{{ context.detectionHint }}</p>
    <p v-if="error" role="alert" class="cc-error">{{ error }}</p>
    <CockpitFlightControlPanel :key="device?.aircraftSn" :target="target" :msdk-device="context.ready ? device : null" :osd="context.osd" />
  </section>
</template>
<script setup lang="ts">
import { computed, ref, onMounted, onBeforeUnmount } from 'vue'
import type { MsdkDeviceState } from '/@/api/msdk-device'
import type { CockpitStreamTarget } from '/@/components/cockpit/CockpitAircraftStreamSelector.vue'
import CockpitFlightControlPanel from '/@/components/cockpit/CockpitFlightControlPanel.vue'
import { getFireDetectionStatus, getDualStreamGroup, requestDualStreamFocus, requestFireDetectionStart, requestFireDetectionStop } from '/@/api/manage'
import { controlContext } from './device-control.mjs'
const props = defineProps<{ device: MsdkDeviceState | null }>()
defineEmits(['close'])
const now = ref(Date.now())
const context = computed(() => controlContext(props.device, now.value))
const target = computed<CockpitStreamTarget | null>(() => props.device ? { key: props.device.aircraftSn, deviceSn: props.device.aircraftSn, callsign: props.device.deviceName || props.device.aircraftSn, role: 'fire-monitor', online: context.value.ready, streamStatus: 'idle' } : null)
const running = ref(false)
const statusKnown = ref(false)
const busy = ref(false)
const error = ref('')
let disposed = false
let revision = 0
let timer:ReturnType<typeof setInterval>
async function readStatus () {
  const sn = props.device?.aircraftSn
  if (!sn || busy.value || disposed) return
  const token = ++revision
  try {
    const response = await getFireDetectionStatus(sn)
    if (response.code !== 0 || typeof response.data?.running !== 'boolean') throw new Error(response.message || '识别状态读取失败')
    if (disposed || busy.value || token !== revision || sn !== props.device?.aircraftSn) return
    running.value = response.data?.running === true
    statusKnown.value = true
    error.value = ''
  } catch (_) {
    if (!disposed && !busy.value && token === revision) { statusKnown.value = false; error.value = '识别状态读取失败，请刷新后重试。' }
  }
}
async function toggleDetection () {
  const device = props.device
  const current = controlContext(device)
  if (!device || busy.value || !statusKnown.value || !current.ready || (!running.value && !current.detectionAllowed)) return
  const sn = device.aircraftSn
  revision++
  busy.value = true
  error.value = ''
  try {
    if (!running.value) {
      const group = await getDualStreamGroup(sn)
      if (group.code !== 0) throw new Error('镜头状态读取失败，请重试')
      if (disposed) return
      if (String(group.data?.currentMode || '').toLowerCase().includes('thermal')) {
        const focus = await requestDualStreamFocus(sn, 'focus-visible')
        if (focus.code !== 0) throw new Error(focus.message || '可见光镜头切换失败')
      }
    }
    if (disposed || !controlContext(props.device).ready) return
    const response = running.value ? await requestFireDetectionStop(sn) : await requestFireDetectionStart(sn)
    if (response.code !== 0) throw new Error(response.message || '识别操作失败')
    if (!disposed) statusKnown.value = false
  } catch (cause) {
    if (!disposed) error.value = cause instanceof Error ? cause.message : '识别操作失败'
  } finally {
    busy.value = false
    if (!disposed && !error.value) await readStatus()
  }
}
onMounted(() => { readStatus(); timer = setInterval(() => { now.value = Date.now(); if (!document.hidden) readStatus() }, 4000) })
onBeforeUnmount(() => { disposed = true; clearInterval(timer) })
</script>
<style scoped>
.cc-device-operations{margin-top:18px;padding:18px}.cc-device-operation-head{display:flex;gap:12px;justify-content:space-between;align-items:start}.cc-device-operation-head h2{margin:0 0 7px}.cc-detection-controls{display:flex;gap:15px;align-items:center;flex-wrap:wrap;margin:12px 0}.cc-device-operations :deep(.cockpit-flight-panel){position:relative;inset:auto;width:100%;margin-top:14px}.cc-device-operations :deep(.flight-console){max-height:none;overflow:auto}.cc-device-operations :deep(.console-toolbar){height:auto}.cc-device-operations :deep(.console-body){min-width:760px}@media(max-width:980px){.cc-device-operations :deep(.console-body){min-width:0}.cc-device-operations :deep(.flight-console){overflow:visible}}
</style>
