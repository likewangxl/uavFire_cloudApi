<template>
  <section class="cc-page">
    <div class="cc-heading"><div><span class="cc-eyebrow">巡护指挥台 / 事件工作区</span><h1>火情事件</h1><p>现场证据与处置进展同步查看 · 按最近发现时间倒序 · 最近 200 条记录 · 每 15 秒更新</p></div><button class="cc-button" :disabled="loading" @click="loadEvents"><ReloadOutlined />{{ loading ? '更新中' : '刷新事件' }}</button></div>
    <div v-if="listError" class="cc-error" role="alert">{{ listError }}<button class="cc-text-button" @click="loadEvents">重试</button></div>
    <div class="cc-toolbar"><div class="cc-filters"><button v-for="item in filters" :key="item.key" :class="{active:filter===item.key}" @click="filter=item.key">{{ item.label }}{{ item.key==='pending' ? ` ${pendingCount}` : '' }}</button></div><label v-if="validationCount" class="cc-validation-toggle"><input v-model="includeValidation" type="checkbox" @change="onValidationChange" />显示验收记录（{{ validationCount }}）</label><label class="cc-search"><SearchOutlined /><input v-model="search" aria-label="搜索火情事件" placeholder="搜索事件编号、设备或位置" /></label></div>
    <div v-if="loading && !events.length" class="cc-panel cc-empty" role="status">正在读取火情事件…</div>
    <div v-else-if="!visibleEvents.length" class="cc-panel cc-empty">{{ listError ? '事件暂时不可用，请重试。' : events.length ? '没有符合筛选条件的事件' : '暂无火情事件' }}<button v-if="events.length" class="cc-text-button" @click="filter='';search=''">清除筛选</button></div>
    <div v-else-if="selected" class="cc-events-grid">
      <aside class="cc-event-list" aria-label="火情事件列表"><button v-for="event in visibleEvents" :key="event.id" class="cc-event-card" :class="{selected:selected.id===event.id}" :aria-pressed="selected.id===event.id" @click="selectEvent(event)"><header><span class="cc-status" :class="eventState(event).tone">{{ eventState(event).label }}</span><time>{{ formatTime(event.lastSeenTime||event.createTime) }}</time></header><div class="cc-event-card-body"><div v-if="eventImages(event).length" class="cc-event-thumb"><FireOutlined /><img :src="eventImages(event)[0].url" alt="" loading="lazy" @error="hideBrokenThumbnail" /></div><strong>{{ isValidationEvent(event) ? '验收测试' : '火情线索' }} #{{ event.id }}</strong></div><p class="cc-event-id" :title="event.eventId">{{ event.eventId }}</p><p>{{ eventLocation(event) }}</p><p>{{ event.deviceSn || '设备未记录' }}</p></button></aside>
      <article class="cc-panel cc-event-scene" aria-label="火情现场"><header class="cc-scene-heading"><div><span class="cc-eyebrow">火情现场 / {{ selected.id }}</span><h2>火情线索 #{{ selected.id }}</h2><p><EnvironmentOutlined /> {{ eventLocation(selected) }}</p></div><span class="cc-status" :class="eventState(selected).tone">{{ eventState(selected).label }}</span></header>
        <div class="cc-evidence-image"><img v-if="currentImage && !imageFailed" :key="currentImage.url" :src="currentImage.url" :alt="`${selected.eventId} · ${currentImage.label}`" @error="imageFailed=true" /><div v-else class="cc-empty">{{ imageFailed ? '现场图片加载失败' : '此事件暂无现场图片' }}<button v-if="imageFailed" class="cc-text-button" @click="imageFailed=false">重试图片</button></div><button v-if="currentImage && !imageFailed" class="cc-button" @click="imageOpen=true"><ExpandOutlined />放大图片</button></div>
        <div class="cc-image-options"><button v-for="image in images" :key="image.key" class="cc-button" :class="{active:imageKey===image.key}" @click="imageKey=image.key">{{ image.label }}</button></div>
        <footer class="cc-scene-footer"><span>发现时间<br />{{ formatTime(selected.eventTimestamp||selected.createTime) }}</span><router-link v-if="selected.deviceSn" class="cc-button" :to="{path:'/video-monitor',query:{sn:selected.deviceSn}}"><VideoCameraOutlined />查看现场视频</router-link></footer>
      </article>
      <aside class="cc-panel cc-event-detail" aria-label="事件处置详情"><div class="cc-detail-top"><header><h2>事件处置</h2><span class="cc-status" :class="eventState(selected).tone">{{ eventState(selected).label }}</span></header><span class="cc-muted">{{ selected.linkedIncidentId ? `关联处置事件 #${selected.linkedIncidentId}` : '尚未关联处置事件' }}</span><p>{{ eventState(selected).key==='pending' ? '等待值班人员结合现场证据复核。' : '以服务端记录的当前状态为准。' }}</p></div>
        <div class="cc-detail-scroll" :key="selected.id"><div class="cc-timeline-heading"><h3>处置时间轴</h3><button class="cc-text-button" @click="expanded=!expanded">{{ expanded?'全部收起':'全部展开' }}</button></div><div v-if="detailLoading" class="cc-empty" role="status">正在读取记录…</div><div v-for="error in detailErrors" :key="error" class="cc-error" role="alert">{{ error }}<button class="cc-text-button" @click="loadDetail(selected)">重试</button></div><ol v-if="!detailLoading && timeline.length" class="cc-timeline"><li v-for="entry in timeline" :key="entry.key"><details :open="expanded"><summary>{{ entry.title }}</summary><time>{{ formatTime(entry.time) }}</time><p>记录人员 / 来源：{{ entry.actor }}</p><p v-if="entry.description">{{ entry.description }}</p><p v-if="entry.result">结果：{{ entry.result }}</p></details></li></ol><div v-if="!detailLoading&&!timeline.length&&!detailErrors.length" class="cc-empty">暂无历史或处置记录</div>
          <h3>事件详细信息</h3><dl class="cc-facts"><dt>事件编号</dt><dd>{{ selected.eventId }}</dd><dt>来源</dt><dd>{{ selected.source || '未记录' }}</dd><dt>置信度</dt><dd>{{ confidence(selected.confidence) }}</dd><dt>定位质量</dt><dd>{{ selected.locationQuality || selected.geoQuality || '待确认' }}</dd><dt>上报次数</dt><dd>{{ selected.reportCount ?? '未记录' }}</dd><dt>关联任务</dt><dd><router-link v-if="selected.missionNo" :to="`/fire-mission-detail/${encodeURIComponent(selected.missionNo)}`">{{ selected.missionNo }}</router-link><span v-else>尚未关联</span></dd></dl>
        </div><footer class="cc-detail-actions"><button v-if="eventState(selected).key==='pending'" class="cc-button primary" :disabled="!operatorId" @click="openReview">人工复核</button><router-link v-if="selected.linkedIncidentId" class="cc-button" :to="{path:'/operation-incidents',query:{incident:String(selected.linkedIncidentId)}}">进入事件处置</router-link><span v-if="!operatorId&&eventState(selected).key==='pending'" class="cc-muted">请重新登录后复核</span></footer>
      </aside>
    </div>
    <a-modal v-model:visible="imageOpen" title="现场证据" :footer="null" width="1000px"><img v-if="currentImage" class="cc-modal-image" :src="currentImage.url" :alt="currentImage.label" /></a-modal>
    <a-modal v-model:visible="reviewOpen" title="人工复核" ok-text="提交复核" cancel-text="取消" :confirm-loading="submitting" :ok-button-props="{disabled:!reviewReason.trim()||!operatorId}" :mask-closable="!submitting" :closable="!submitting" :cancel-button-props="{disabled:submitting}" @ok="submitReview"><p>{{ reviewEvent?.eventId }}</p><a-radio-group v-model:value="reviewDecision"><a-radio value="confirm">确认火情</a-radio><a-radio value="reject">排除火情</a-radio></a-radio-group><label style="display:block;margin:18px 0 8px" for="fire-review-reason">复核说明（必填）</label><a-textarea id="fire-review-reason" v-model:value="reviewReason" :rows="4" :maxlength="1000" placeholder="填写现场判断依据" /><p v-if="reviewError" role="alert" style="color:#c44;margin-top:12px">{{ reviewError }}</p></a-modal>
  </section>
</template>
<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ReloadOutlined, SearchOutlined, EnvironmentOutlined, ExpandOutlined, VideoCameraOutlined, FireOutlined } from '@ant-design/icons-vue'
import { eventApi } from '/@/api/fire/event'
import { realOperationIncidentApi as operationIncidentApi } from '/@/api/operation/incident'
import type { FireEventDTO } from '/@/types/fire/event'
import { ELocalStorageKey } from '/@/types'
import { eventState, eventLocation, eventImages, filterEvents, isValidationEvent, formatTime, buildEventTimeline, createLatestRequest, responseData } from '/@/components/command-center/event-model.mjs'
const route = useRoute(); const router = useRouter()
const events = ref<FireEventDTO[]>([]); const selectedId = ref<number>(); const filter = ref(''); const search = ref(''); const loading = ref(false); const listError = ref('')
const detailLoading = ref(false); const detailErrors = ref<string[]>([]); const timeline = ref<any[]>([]); const expanded = ref(true)
const imageKey = ref('visible'); const imageFailed = ref(false); const imageOpen = ref(false)
const reviewOpen = ref(false); const reviewEvent = ref<FireEventDTO|null>(null); const reviewDecision = ref('confirm'); const reviewReason = ref(''); const reviewError = ref(''); const submitting = ref(false)
const operatorId = localStorage.getItem(ELocalStorageKey.UserId) || ''
const filters = [{ key: '', label: '全部' }, { key: 'pending', label: '待复核' }, { key: 'handling', label: '处理中' }, { key: 'closed', label: '已关闭' }]
const includeValidation = ref(false)
const validationCount = computed(() => events.value.filter(isValidationEvent).length)
const visibleEvents = computed(() => filterEvents(events.value, filter.value, search.value, includeValidation.value))
const selected = computed(() => visibleEvents.value.find(e => e.id === selectedId.value) || visibleEvents.value[0])
const pendingCount = computed(() => events.value.filter(e => !isValidationEvent(e) && eventState(e).key === 'pending').length)
const images = computed(() => eventImages(selected.value)); const currentImage = computed(() => images.value.find(i => i.key === imageKey.value) || images.value[0])
const listRequest = createLatestRequest(); const detailRequest = createLatestRequest()
let timer: ReturnType<typeof setInterval>; let disposed = false
const confidence = (value:any) => value !== null && value !== '' && Number.isFinite(Number(value)) ? `${(Number(value) * 100).toFixed(1)}%` : '未记录'
function onValidationChange () {
  if (includeValidation.value) return
  const target = events.value.find(event => event.eventId === route.query.event || String(event.id) === route.query.event)
  if (target && isValidationEvent(target)) {
    selectedId.value = undefined
    router.replace({ query: { ...route.query, event: undefined } })
  }
}
async function loadEvents () {
  if (loading.value || disposed) return
  loading.value = true
  await listRequest.run(async () => {
    const data = responseData(await eventApi.list({ workspaceId: localStorage.getItem(ELocalStorageKey.WorkspaceId) || undefined, limit: 200 }, true))
    const rows = Array.isArray(data) ? data : []
    const target = String(route.query.event || '')
    if (target && !rows.some(row => row.eventId === target || String(row.id) === target)) {
      try { const older = responseData(await eventApi.get(target)); if (older) rows.unshift(older) } catch { throw new Error('指定事件无法读取，请检查事件编号或访问权限。') }
    }
    return rows
  }, rows => {
    const previous = selected.value?.id
    events.value = rows; listError.value = ''
    if (route.query.event) { const e = rows.find(row => row.eventId === route.query.event || String(row.id) === route.query.event); if (e) { selectedId.value = e.id; if (isValidationEvent(e)) includeValidation.value = true } }
    if (selected.value && selected.value.id === previous) loadDetail(selected.value)
  }, error => { listError.value = error.message === '指定事件无法读取，请检查事件编号或访问权限。' ? error.message : '火情事件更新失败，当前内容可能不是最新记录。' })
  loading.value = false
}
function hideBrokenThumbnail (event:Event) { (event.target as HTMLImageElement).style.display = 'none' }
function selectEvent (event:FireEventDTO) { selectedId.value = event.id; router.replace({ query: { ...route.query, event: event.eventId } }) }
async function loadDetail (event:FireEventDTO) {
  detailLoading.value = true; detailErrors.value = []; timeline.value = []
  await detailRequest.run(async () => Promise.allSettled([eventApi.history(event.eventId).then(responseData), event.linkedIncidentId ? operationIncidentApi.timeline(event.linkedIncidentId).then(responseData) : Promise.resolve([])]), results => { const data = results.map((r, index) => { if (r.status === 'fulfilled') return Array.isArray(r.value) ? r.value : []; detailErrors.value.push(index === 0 ? '识别历史加载失败' : '处置时间轴加载失败'); return [] }); timeline.value = buildEventTimeline(data[0], data[1]); detailLoading.value = false }, () => { detailErrors.value = ['事件记录加载失败']; detailLoading.value = false })
}
watch(() => selected.value?.id, () => { detailRequest.invalidate(); imageKey.value = 'visible'; imageFailed.value = false; imageOpen.value = false; expanded.value = true; if (selected.value)loadDetail(selected.value); else { timeline.value = []; detailLoading.value = false } })
watch(() => currentImage.value?.url, () => { imageFailed.value = false })
watch(() => route.query.event, () => { const e = events.value.find(row => row.eventId === route.query.event || String(row.id) === route.query.event); if (e) { selectedId.value = e.id; if (isValidationEvent(e)) includeValidation.value = true } else if (route.query.event) loadEvents() })
function openReview () { reviewEvent.value = selected.value || null; reviewReason.value = ''; reviewError.value = ''; reviewDecision.value = 'confirm'; reviewOpen.value = true }
async function submitReview () { const event = reviewEvent.value; if (submitting.value || !operatorId || !reviewReason.value.trim() || !event || eventState(event).key !== 'pending') return; submitting.value = true; reviewError.value = ''; try { responseData(await eventApi[reviewDecision.value === 'confirm' ? 'confirm' : 'reject'](event.id, { operatorId, reason: reviewReason.value.trim() })); reviewOpen.value = false; const fresh = responseData(await eventApi.get(event.eventId)); events.value = events.value.map(row => row.id === event.id ? fresh : row); await loadEvents(); if (selected.value?.id === event.id) await loadDetail(selected.value) } catch (error) { reviewError.value = '复核未保存，请检查服务状态后重试；当前事件状态未修改。' } finally { submitting.value = false } }
onMounted(() => { loadEvents(); timer = setInterval(() => { if (!document.hidden && !reviewOpen.value)loadEvents() }, 15000) })
onBeforeUnmount(() => { disposed = true; clearInterval(timer); listRequest.invalidate(); detailRequest.invalidate() })
</script>

<style scoped>
.cc-validation-toggle { display: flex; align-items: center; gap: 6px; font-size: 12px; white-space: nowrap; }
</style>
