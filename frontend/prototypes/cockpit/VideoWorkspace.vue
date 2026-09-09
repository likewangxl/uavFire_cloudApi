<template>
  <section ref="workspaceRoot" :role="expanded ? 'dialog' : undefined" :aria-modal="expanded ? 'true' : undefined" class="video-workspace" :class="{ expanded }" aria-label="视频监控工作区">
    <div class="workspace-toolbar">
      <div class="workspace-actions">
        <button class="button secondary" :disabled="!windows.length" @click="arrange"><AppstoreOutlined />一键平铺</button>
        <button class="button secondary" @click="restoreDefault"><ReloadOutlined />恢复四画面</button>
        <span class="open-count">已打开 <strong>{{ windows.length }}</strong> 路</span>
      </div>
      <div class="workspace-quota"><span class="quality high">高清 {{ highIds.length }} / 4</span><span>低清 {{ devices.length - highIds.length }} 路</span></div>
      <button class="button secondary workspace-expand" @click="expanded = !expanded"><FullscreenExitOutlined v-if="expanded" /><FullscreenOutlined v-else />{{ expanded ? '返回页面' : '展开工作区' }}</button>
    </div>

    <div class="monitor-console">
      <div class="stage-shell">
        <div class="stage-heading"><h2>主播放区</h2><span><DragOutlined /> 拖动标题移动 · 拖右下角缩放</span></div>
        <div ref="canvas" class="monitor-canvas" :class="{ 'drop-ready': gesture?.kind === 'add', 'drop-over': gesture?.over }" aria-label="主视频播放区">
          <div v-if="!windows.length" class="workspace-empty"><VideoCameraOutlined /><h3>把想看的画面拖到这里</h3><p>也可以点击飞机缩略图，添加多个画面</p></div>
          <article v-for="window in windows" v-show="!maximized || maximized === window.id" :key="window.id" class="floating-video"
            :class="{ focused: activeId === window.id, maximized: maximized === window.id, compact: isCompact(window) }"
            :style="windowStyle(window)" :aria-label="`${device(window.id).name}播放窗口`" @pointerdown="focusWindow(window.id)">
            <header class="floating-heading">
              <button class="window-move-handle" :aria-label="`移动${device(window.id).name}画面`" title="拖动移动，方向键微调，双击放大"
                @pointerdown="beginGesture($event, 'move', window.id)" @keydown="keyboardAdjust($event, 'move', window)" @dblclick="toggleMaximize(window.id)">
                <DragOutlined /><span>{{ device(window.id).name }}</span><span class="quality" :class="{ high: highIds.includes(window.id) }">{{ highIds.includes(window.id) ? '高清' : '低清' }}</span>
              </button>
              <div class="window-actions"><button :aria-label="`${maximized === window.id ? '还原' : '放大'}${device(window.id).name}画面`" @click="toggleMaximize(window.id)"><FullscreenExitOutlined v-if="maximized === window.id" /><ExpandOutlined v-else /></button><button :aria-label="`关闭${device(window.id).name}画面`" @click="closeWindow(window.id)"><CloseOutlined /></button></div>
            </header>
            <div class="floating-picture"><img :src="photo(window.lens === 'thermal' ? 'thermal' : device(window.id).image)" :alt="`${device(window.id).name}历史样张，非实时视频`" draggable="false" /><span class="picture-sample">历史样张 · 非实时</span><span v-if="device(window.id).channel === '弱网'" class="picture-warning"><WarningOutlined /> 弱网示例</span></div>
            <footer class="floating-footer"><span>{{ device(window.id).state }} · 电量 {{ device(window.id).battery }}%</span><span v-if="activeId === window.id" class="focused-label">当前关注</span></footer>
            <button v-if="!maximized" class="window-resize-handle" :aria-label="`调整${device(window.id).name}画面大小`" title="拖动调整大小，方向键微调" @pointerdown.stop="beginGesture($event, 'resize', window.id)" @keydown="keyboardAdjust($event, 'resize', window)"><ColumnHeightOutlined /></button>
          </article>
          <div v-if="gesture?.kind === 'add' && gesture.moved" class="drop-instruction"><PlusOutlined />{{ gesture.over ? '松开即可添加画面' : '拖入主播放区添加' }}</div>
        </div>
        <div class="stage-status"><span><i class="legend-dot blue"></i>自由布局 <span class="desktop-hint">· 支持同时查看多个画面</span></span><span>{{ storageAvailable ? '布局自动保存在本机' : '布局保留在当前会话' }}</span></div>
      </div>

      <aside class="thumbnail-rail" aria-label="飞机视频缩略图列表">
        <div class="thumbnail-heading"><h2>巡查飞机</h2><span>{{ devices.length }} 架</span></div>
        <label class="search-field"><SearchOutlined /><input v-model="search" aria-label="搜索飞机缩略图" placeholder="搜索飞机名称或型号" /></label>
        <div class="thumbnail-hint">拖入左侧播放区，或点击添加</div>
        <div class="thumbnail-list">
          <button v-for="aircraft in filteredDevices" :key="aircraft.id" class="aircraft-thumbnail" :class="{ opened: isOpen(aircraft.id), active: activeId === aircraft.id }"
            :aria-label="`${isOpen(aircraft.id) ? '定位' : '添加'}${aircraft.name}画面`" @pointerdown="beginGesture($event, 'add', aircraft.id)" @click="clickThumbnail(aircraft.id)">
            <span class="thumbnail-picture"><img :src="photo(aircraft.image)" :alt="`${aircraft.name}缩略图历史样张`" draggable="false" loading="lazy" /><span class="thumbnail-sample">历史样张</span><span class="thumbnail-quality" :class="{ high: highIds.includes(aircraft.id) }">{{ highIds.includes(aircraft.id) ? '高清' : '低清' }}</span><span class="thumbnail-open-state"><CheckCircleOutlined v-if="isOpen(aircraft.id)" /><PlusOutlined v-else />{{ isOpen(aircraft.id) ? '已在播放区' : '拖入 / 点击添加' }}</span></span>
            <span class="thumbnail-meta"><strong>{{ aircraft.name }}</strong><span :class="{ warning: aircraft.channel === '弱网' || aircraft.battery < 30 }">{{ aircraft.state }} · {{ aircraft.battery }}%</span></span>
          </button>
          <div v-if="!filteredDevices.length" class="empty">未找到该飞机<button class="text-button" @click="search = ''">清除搜索</button></div>
        </div>
      </aside>
    </div>

    <div class="workspace-selection-bar">
      <div class="selected-aircraft"><SendOutlined /><div><strong>{{ activeWindow ? device(activeId).name : '未选择画面' }}</strong><span>{{ activeWindow ? '当前关注画面' : '从缩略图列表添加' }}</span></div></div>
      <div v-if="activeWindow" class="segmented"><button :class="{ active: activeWindow.lens === 'visible' }" @click="activeWindow.lens = 'visible'">可见光</button><button :class="{ active: activeWindow.lens === 'thermal' }" @click="activeWindow.lens = 'thermal'">红外样张</button></div>
      <button class="button secondary" :disabled="!activeWindow" @click="$emit('request-high', activeId)">{{ highIds.includes(activeId) ? '已使用高清名额' : '申请高清关注' }}</button>
      <button class="text-button" :disabled="!activeWindow" @click="$emit('related-event', activeId)">关联火情 <ArrowRightOutlined /></button>
      <span class="selection-note">放大画面不会自动增加高清名额</span>
    </div>
    <Teleport to="body"><div v-if="gesture?.kind === 'add' && gesture.moved" class="video-drag-ghost" :style="{ left: `${gesture.clientX + 14}px`, top: `${gesture.clientY + 12}px` }"><img :src="photo(device(gesture.id).image)" alt="" /><span>{{ device(gesture.id).name }} · 拖入播放区</span></div></Teleport>
  </section>
</template>

<script setup>
import { computed, ref, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { AppstoreOutlined, ReloadOutlined, FullscreenOutlined, FullscreenExitOutlined, DragOutlined, VideoCameraOutlined, ExpandOutlined, CloseOutlined, ColumnHeightOutlined, WarningOutlined, PlusOutlined, SearchOutlined, CheckCircleOutlined, SendOutlined, ArrowRightOutlined } from '@ant-design/icons-vue'
import { VIDEO_LAYOUT_KEY, clamp, boundWindow, tileWindows, defaultWindows, loadWindows } from './video-layout'
const props = defineProps({ devices: { type: Array, required: true }, highIds: { type: Array, required: true }, focusedId: { type: Number, default: 1 } })
const emit = defineEmits(['focus', 'request-high', 'related-event', 'notify'])
const windows = ref(loadWindows(props.devices.map(d => d.id)))
const activeId = ref(windows.value.some(w => w.id === props.focusedId) ? props.focusedId : windows.value[0]?.id || 0)
const activeWindow = computed(() => windows.value.find(w => w.id === activeId.value))
const workspaceRoot = ref(null)
const search = ref(''), expanded = ref(false), maximized = ref(0), canvas = ref(null), gesture = ref(null)
const storageAvailable = ref(true), stageWidth = ref(1000), stageHeight = ref(600)
const filteredDevices = computed(() => props.devices.filter(d => `${d.name} ${d.model}`.toLowerCase().includes(search.value.trim().toLowerCase())))
let observer, savedBodyOverflow, expandedPreviousFocus, ignoreClick = false, ignoreClickTimer, saveTimer
const device = id => props.devices.find(d => d.id === id)
const photo = key => new URL(`./assets/${key}.jpg`, import.meta.url).href
const isOpen = id => windows.value.some(w => w.id === id)
const isCompact = window => !maximized.value && (window.width * stageWidth.value < 290 || window.height * stageHeight.value < 160)
const topZ = () => Math.max(0, ...windows.value.map(w => w.z)) + 1
function windowStyle (window) {
  if (maximized.value === window.id) return { left: 0, top: 0, width: '100%', height: '100%', zIndex: 25 }
  return { left: `${window.x * 100}%`, top: `${window.y * 100}%`, width: `${window.width * 100}%`, height: `${window.height * 100}%`, zIndex: window.z }
}
function save () {
  try { localStorage.setItem(VIDEO_LAYOUT_KEY, JSON.stringify(windows.value)); storageAvailable.value = true } catch (_) { storageAvailable.value = false }
}
watch(windows, () => { clearTimeout(saveTimer); saveTimer = setTimeout(save, 200) }, { deep: true })
function focusWindow (id) {
  const window = windows.value.find(w => w.id === id)
  if (!window) return
  if (window.z < topZ() - 1) window.z = topZ()
  activeId.value = id
  if (props.focusedId !== id) emit('focus', id)
}
function addWindow (id, point) {
  if (!device(id)) return
  if (isOpen(id)) { maximized.value = 0; focusWindow(id); return }
  maximized.value = 0
  const index = windows.value.length
  const size = { width: 0.52, height: 0.61 }
  const position = point || { x: 0.04 + (index % 5) * 0.06, y: 0.04 + (index % 5) * 0.055 }
  windows.value.push(boundWindow({ id, ...size, ...position, z: topZ(), lens: 'visible' }))
  focusWindow(id)
}
function clickThumbnail (id) { if (!ignoreClick) addWindow(id) }
function closeWindow (id) {
  windows.value = windows.value.filter(w => w.id !== id)
  if (maximized.value === id) maximized.value = 0
  if (activeId.value === id) { activeId.value = 0; const next = [...windows.value].sort((a, b) => b.z - a.z)[0]; if (next) focusWindow(next.id); else emit('focus', 0) }
}
function toggleMaximize (id) { maximized.value = maximized.value === id ? 0 : id; focusWindow(id) }
function arrange () { maximized.value = 0; windows.value = tileWindows(windows.value) }
function restoreDefault () { windows.value = defaultWindows(); maximized.value = 0; activeId.value = 1; emit('focus', 1); emit('notify', '已恢复四画面，高清名额保持不变') }
function pointInside (x, y, rect) { return x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom }
function beginGesture (event, kind, id) {
  if (event.button !== 0 || !event.isPrimary || gesture.value || (maximized.value && kind !== 'add')) return
  // Touch can scroll the thumbnail rail; tapping remains an alternative to dragging.
  if (kind === 'add' && event.pointerType === 'touch') return
  const element = event.currentTarget
  const window = windows.value.find(w => w.id === id)
  const rect = canvas.value.getBoundingClientRect()
  gesture.value = { kind, id, pointerId: event.pointerId, element, rect, original: window ? { ...window } : null, startX: event.clientX, startY: event.clientY, clientX: event.clientX, clientY: event.clientY, moved: false, over: false }
  element.setPointerCapture(event.pointerId)
  if (kind !== 'add') { event.preventDefault(); focusWindow(id) }
  windowListen(true)
}
function movePointer (event) {
  const state = gesture.value
  if (!state || state.pointerId !== event.pointerId) return
  const dx = event.clientX - state.startX, dy = event.clientY - state.startY
  state.clientX = event.clientX; state.clientY = event.clientY
  if (Math.hypot(dx, dy) > 6) state.moved = true
  if (!state.moved) return
  event.preventDefault()
  if (state.kind === 'add') { state.over = pointInside(event.clientX, event.clientY, state.rect); return }
  const item = windows.value.find(w => w.id === state.id)
  if (!item) return
  if (state.kind === 'move') Object.assign(item, boundWindow({ ...item, x: state.original.x + dx / state.rect.width, y: state.original.y + dy / state.rect.height }))
  else Object.assign(item, resizeTo(item, state.original.width + dx / state.rect.width, state.original.height + dy / state.rect.height))
}
function resizeTo (item, width, height) {
  const minWidth = Math.min(180 / stageWidth.value, 1 - item.x)
  const minHeight = Math.min(120 / stageHeight.value, 1 - item.y)
  return boundWindow({ ...item, width: clamp(width, minWidth, 1 - item.x), height: clamp(height, minHeight, 1 - item.y) })
}
function endPointer (event) {
  const state = gesture.value
  if (!state || (event && state.pointerId !== event.pointerId)) return
  if (state.moved && state.kind === 'add') {
    ignoreClick = true; clearTimeout(ignoreClickTimer); ignoreClickTimer = setTimeout(() => { ignoreClick = false }, 80)
    if (event?.type === 'pointerup' && pointInside(event.clientX, event.clientY, state.rect)) addWindow(state.id, { x: (event.clientX - state.rect.left) / state.rect.width - 0.08, y: (event.clientY - state.rect.top) / state.rect.height - 0.04 })
  }
  if (state.element.hasPointerCapture(state.pointerId)) state.element.releasePointerCapture(state.pointerId)
  gesture.value = null; windowListen(false); save()
}
function windowListen (enabled) {
  const method = enabled ? 'addEventListener' : 'removeEventListener'
  window[method]('pointermove', movePointer, { passive: false }); window[method]('pointerup', endPointer); window[method]('pointercancel', endPointer)
}
function keyboardAdjust (event, kind, item) {
  if (maximized.value || !['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(event.key)) return
  event.preventDefault(); focusWindow(item.id)
  const distance = event.shiftKey ? 30 : 10
  const dx = (event.key === 'ArrowRight' ? distance : event.key === 'ArrowLeft' ? -distance : 0) / stageWidth.value
  const dy = (event.key === 'ArrowDown' ? distance : event.key === 'ArrowUp' ? -distance : 0) / stageHeight.value
  Object.assign(item, kind === 'move' ? boundWindow({ ...item, x: item.x + dx, y: item.y + dy }) : resizeTo(item, item.width + dx, item.height + dy))
}
function escape (event) {
  if (event.defaultPrevented || document.querySelector('.dialog-backdrop [aria-modal="true"]')) return
  if (event.key === 'Escape') { endPointer(); if (maximized.value) maximized.value = 0; else expanded.value = false }
  if (event.key === 'Tab' && expanded.value) {
    const controls = [...workspaceRoot.value.querySelectorAll('button:not(:disabled),input')].filter(el => el.getClientRects().length)
    const first = controls[0], last = controls.at(-1)
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
  }
}
watch(() => props.focusedId, id => { if (id && id !== activeId.value) addWindow(id) })
watch(expanded, async value => { if (value) { expandedPreviousFocus = document.activeElement; savedBodyOverflow = document.body.style.overflow; document.body.style.overflow = 'hidden'; await nextTick(); workspaceRoot.value?.querySelector('button:not(:disabled)')?.focus() } else { document.body.style.overflow = savedBodyOverflow || ''; expandedPreviousFocus?.focus?.() } })
onMounted(() => {
  observer = new ResizeObserver(entries => { const rect = entries[0].contentRect; stageWidth.value = Math.max(1, rect.width); stageHeight.value = Math.max(1, rect.height) })
  observer.observe(canvas.value)
  if (props.focusedId && !isOpen(props.focusedId)) addWindow(props.focusedId)
  window.addEventListener('keydown', escape); window.addEventListener('pagehide', save)
})
onBeforeUnmount(() => { endPointer(); clearTimeout(saveTimer); clearTimeout(ignoreClickTimer); save(); observer?.disconnect(); window.removeEventListener('keydown', escape); window.removeEventListener('pagehide', save); if (expanded.value) document.body.style.overflow = savedBodyOverflow || '' })
</script>

<style scoped>
.video-workspace{min-width:0}.workspace-toolbar{display:flex;align-items:center;gap:15px;justify-content:space-between;margin-bottom:16px}.workspace-actions{display:flex;align-items:center;gap:10px}.workspace-actions .button{font-size:12px}.open-count{font-size:11px;color:#8ea4bf;margin-left:7px}.open-count strong{font-weight:600;color:#ceddf2}.workspace-quota{display:flex;gap:12px;align-items:center;font-size:11px;color:#91a7c0;margin-left:auto}.workspace-expand{margin-left:12px}.monitor-console{display:grid;grid-template-columns:minmax(0,1fr) 258px;gap:16px;height:clamp(415px,calc(100vh - 305px),820px)}.stage-shell{min-width:0;min-height:0;background:#0d1725;border:1px solid #30415a;border-radius:9px;display:flex;flex-direction:column;overflow:hidden}.stage-heading{height:43px;flex-shrink:0;display:flex;justify-content:space-between;align-items:center;padding:0 15px;border-bottom:1px solid #26374d;background:#152235;gap:10px}.stage-heading h2{font-size:13px}.stage-heading>span{display:flex;gap:6px;align-items:center;font-size:10px;color:#829ab9}.monitor-canvas{position:relative;flex:1;min-height:0;margin:10px;isolation:isolate;border:1px solid transparent;border-radius:4px}.monitor-canvas.drop-ready{outline:1px dashed #678fc9;outline-offset:3px}.monitor-canvas.drop-over{background:#1b3554;outline:2px dashed #8fb8f3}.stage-status{display:flex;justify-content:space-between;gap:12px;align-items:center;min-height:31px;padding:7px 14px;border-top:1px solid #25364c;color:#778fab;font-size:9px}.stage-status>span:first-child{display:flex;align-items:center;gap:6px}.floating-video{position:absolute;background:#080e16;border:1px solid #374c68;border-radius:6px;display:flex;flex-direction:column;overflow:hidden;box-shadow:0 5px 16px #0005;min-width:0;min-height:0}.floating-video.focused{border-color:#90bafa;box-shadow:0 0 0 1px #648cc74d,0 6px 22px #0006}.floating-heading{height:35px;flex-shrink:0;background:#16263a;display:flex;align-items:center;justify-content:space-between;border-bottom:1px solid #30445e;min-width:0}.focused .floating-heading{background:#233b58}.window-move-handle{display:flex;align-items:center;gap:7px;flex:1;min-width:0;height:100%;padding:0 9px;text-align:left;cursor:grab;touch-action:none;user-select:none}.window-move-handle:active{cursor:grabbing}.window-move-handle>.anticon{color:#7e97b6;font-size:12px}.window-move-handle>span:not(.anticon):not(.quality){font-size:11px;font-weight:500;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.window-move-handle .quality{font-size:8px;padding:2px 4px;flex-shrink:0}.window-actions{display:flex;align-items:center;flex-shrink:0;padding-right:4px}.window-actions button{height:27px;width:26px;display:grid;place-items:center;color:#9ab0ce;font-size:13px;border-radius:4px}.window-actions button:hover{background:#355477;color:#fff}.window-actions button:last-child:hover{background:#59372f;color:#ffb9a1}.floating-picture{position:relative;flex:1;min-height:0;display:flex;align-items:center;justify-content:center}.floating-picture img{width:100%;height:100%;object-fit:contain;user-select:none;pointer-events:none}.picture-sample{position:absolute;top:8px;left:8px;padding:3px 5px;background:#101824c9;font-size:8px;border-radius:3px;color:#b3c1d4;pointer-events:none}.picture-warning{position:absolute;bottom:8px;right:8px;background:#563d24d9;color:#f3c68c;font-size:9px;padding:4px 6px;border-radius:3px}.floating-footer{min-height:24px;padding:5px 28px 5px 9px;font-size:8px;display:flex;align-items:center;justify-content:space-between;color:#90a6bf;background:#132033;gap:8px;flex-shrink:0}.focused-label{color:#a4c8fa;white-space:nowrap}.window-resize-handle{position:absolute;bottom:0;right:0;width:26px;height:26px;display:grid;place-items:center;color:#a2bce0;cursor:nwse-resize;touch-action:none;z-index:2;background:#223650;border-radius:5px 0 0 0}.window-resize-handle .anticon{transform:rotate(-45deg);font-size:14px}.window-resize-handle:hover{background:#406598;color:white}.floating-video.compact .floating-heading{height:29px}.floating-video.compact .window-move-handle{gap:4px;padding-left:6px}.floating-video.compact .window-move-handle>.anticon,.floating-video.compact .window-move-handle .quality,.floating-video.compact .focused-label{display:none}.floating-video.compact .floating-footer{font-size:8px}.workspace-empty{height:100%;display:flex;flex-direction:column;align-items:center;justify-content:center;color:#7691b5;text-align:center}.workspace-empty>.anticon{font-size:35px;color:#6283ae;margin-bottom:17px}.workspace-empty h3{font-size:16px;color:#bdcfe8;margin-bottom:9px}.workspace-empty p{font-size:11px}.drop-instruction{position:absolute;inset:auto 20% 16px;z-index:1000;display:flex;justify-content:center;gap:7px;background:#335a88;color:white;padding:12px;font-size:12px;border-radius:6px;pointer-events:none;box-shadow:0 4px 16px #0006}.thumbnail-rail{min-width:0;min-height:0;background:#121e2d;border:1px solid #2c3d55;border-radius:9px;display:flex;flex-direction:column;overflow:hidden}.thumbnail-heading{height:47px;display:flex;align-items:center;justify-content:space-between;padding:0 15px;border-bottom:1px solid #2c3d55;flex-shrink:0}.thumbnail-heading h2{font-size:14px}.thumbnail-heading>span{font-size:11px;color:#93a7c1}.thumbnail-rail .search-field{margin:12px 11px 8px;width:calc(100% - 22px);padding:8px}.thumbnail-hint{font-size:9px;color:#7f97b6;padding:0 13px 12px}.thumbnail-list{flex:1;min-height:0;overflow:auto;padding:0 10px 10px;overscroll-behavior:contain}.aircraft-thumbnail{display:block;width:100%;border:1px solid #2d425d;background:#162537;border-radius:6px;overflow:hidden;padding:0;text-align:left;margin-bottom:11px;cursor:grab;user-select:none;touch-action:pan-y}.aircraft-thumbnail:hover{border-color:#82aeea;background:#223852}.aircraft-thumbnail.active{border-color:#90bafa}.thumbnail-picture{position:relative;display:block;aspect-ratio:16/8.2;overflow:hidden;background:#080f18}.thumbnail-picture>img{width:100%;height:100%;object-fit:cover;pointer-events:none}.thumbnail-sample{position:absolute;top:6px;left:6px;padding:2px 4px;font-size:8px;background:#101a29c9;border-radius:3px;color:#c1cddd}.thumbnail-quality{position:absolute;right:6px;top:6px;font-size:8px;padding:2px 4px;border-radius:3px;background:#203348e6;color:#a7bdd9}.thumbnail-quality.high{background:#234979;color:#c1d8ff}.thumbnail-open-state{position:absolute;bottom:5px;right:5px;background:#15283cd9;color:#c3d4ec;font-size:8px;padding:4px 6px;border-radius:3px;display:flex;gap:5px;align-items:center}.opened .thumbnail-open-state{color:#abd0ff;background:#1e446ddf}.thumbnail-meta{display:flex;justify-content:space-between;align-items:center;padding:9px 8px;gap:5px}.thumbnail-meta strong{font-size:10px;font-weight:500}.thumbnail-meta>span{font-size:8px;color:#8ea5c1}.thumbnail-meta .warning{color:#eac18b}.thumbnail-list .empty{padding:20px 4px}.thumbnail-list .empty button{display:block;margin:9px auto}.workspace-selection-bar{display:flex;align-items:center;gap:14px;background:#142133;border:1px solid #2c405b;border-radius:7px;padding:12px 16px;margin-top:14px;min-height:62px}.selected-aircraft{display:flex;align-items:center;gap:10px;padding-right:16px;border-right:1px solid #31445f}.selected-aircraft>.anticon{color:#7ea8e2;font-size:19px}.selected-aircraft strong{font-size:11px;display:block;white-space:nowrap}.selected-aircraft div>span{font-size:9px;color:#829bb9;display:block;margin-top:4px}.selection-note{color:#8098b5;font-size:10px;margin-left:auto}.video-workspace.expanded{position:fixed;inset:0;z-index:90;background:#0b1523;padding:20px;display:flex;flex-direction:column}.expanded .monitor-console{flex:1;height:auto;min-height:0}.expanded .workspace-toolbar{flex-shrink:0}.expanded .workspace-selection-bar{flex-shrink:0}.expanded .monitor-console{grid-template-columns:minmax(0,1fr) 280px}
@media(min-width:1650px){.monitor-console{grid-template-columns:minmax(0,1fr) 292px}.thumbnail-meta strong{font-size:12px}.thumbnail-meta>span{font-size:10px}.thumbnail-picture{aspect-ratio:16/8.5}.floating-heading{height:39px}.window-move-handle>span:not(.anticon):not(.quality){font-size:13px}.floating-footer{font-size:10px;min-height:27px}}
@media(max-width:1100px){.monitor-console{grid-template-columns:minmax(0,1fr) 215px;gap:12px}.workspace-selection-bar{gap:9px;flex-wrap:wrap}.selection-note{font-size:9px}.stage-heading>span{font-size:9px}.workspace-quota{gap:6px}.workspace-actions{gap:6px}.workspace-actions .button,.workspace-expand{font-size:10px;padding:8px 10px}.open-count{margin-left:0}.workspace-expand{margin-left:0}.window-move-handle .quality{display:none}.stage-status{font-size:8px}.desktop-hint{display:none}.selected-aircraft{padding-right:10px}.monitor-console{height:clamp(420px,calc(100vh - 340px),750px)}}
@media(max-width:700px){.workspace-toolbar{flex-wrap:wrap;gap:10px}.workspace-actions{flex:1}.workspace-quota{order:3;flex-basis:100%;justify-content:flex-start}.open-count{font-size:9px}.monitor-console,.expanded .monitor-console{display:flex;flex-direction:column;height:auto;gap:12px}.stage-shell{height:390px;flex-shrink:0}.stage-heading{padding:0 10px}.stage-heading h2{font-size:11px}.stage-heading>span{font-size:8px}.monitor-canvas{margin:7px}.thumbnail-rail{height:220px;flex-shrink:0}.thumbnail-heading{height:37px}.thumbnail-heading h2{font-size:12px}.thumbnail-rail .search-field{margin:7px 10px;width:calc(100% - 20px)}.thumbnail-hint{display:none}.thumbnail-list{display:flex;gap:9px;padding:0 9px 8px;overflow-x:auto;overflow-y:hidden}.aircraft-thumbnail{width:170px;flex-shrink:0;margin:0;align-self:flex-start}.thumbnail-picture{aspect-ratio:16/7.2}.thumbnail-meta{padding:7px 6px}.workspace-selection-bar{padding:10px;gap:9px}.selected-aircraft>.anticon{display:none}.selected-aircraft{border:0;padding:0}.selected-aircraft div>span{display:none}.workspace-selection-bar .segmented button{padding:6px 8px;font-size:10px}.workspace-selection-bar>.button{font-size:10px;padding:7px 9px}.selection-note{width:100%;margin:0;font-size:9px}.stage-status{padding:7px 9px}.video-workspace.expanded{padding:12px;overflow:auto;display:block}.expanded .monitor-console{min-height:auto}.window-actions button{width:22px}.floating-video.compact .window-move-handle>span:not(.anticon):not(.quality){font-size:9px}.floating-footer{padding-left:5px;font-size:7px!important}.drop-instruction{inset:auto 10px 10px;font-size:10px}.workspace-empty h3{font-size:14px}}
</style>
<style>
.video-drag-ghost{position:fixed;width:195px;z-index:250;pointer-events:none;border:1px solid #9ac1ff;border-radius:7px;overflow:hidden;box-shadow:0 12px 35px #0009;background:#1c324e;opacity:.95}.video-drag-ghost img{width:100%;height:100px;object-fit:cover}.video-drag-ghost span{display:block;color:#e6efff;font-size:11px;padding:8px 10px}
</style>
