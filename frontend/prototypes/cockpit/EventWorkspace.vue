<template>
  <div v-if="!events.length" class="panel empty event-empty">
    <CheckCircleOutlined /><h3>没有符合条件的事件</h3><p>可以调整筛选条件继续查看。</p>
    <button class="text-button" @click="$emit('clear')">清除筛选</button>
  </div>
  <div v-else class="incident-workspace">
    <aside class="incident-list" aria-label="火情事件列表">
      <div class="incident-list-heading"><h2>事件列表</h2><span>{{ events.length }} 条</span></div>
      <div class="incident-list-scroll">
        <button v-for="event in events" :key="event.id" class="incident-card" :class="{ selected: selected.id === event.id }" :aria-pressed="selected.id === event.id" @click="$emit('select', event)">
          <div class="incident-card-top"><span class="status" :class="tone(event.state)">{{ event.state }}</span><time>{{ event.time }}</time></div>
          <div class="incident-card-body"><img :src="photo(event.image)" alt="" /><div><h3>{{ event.title }}</h3><p>{{ event.place }}</p><small>{{ deviceName(event.device) }}</small></div></div>
          <div class="incident-card-bottom"><span>{{ event.id }}</span><RightOutlined /></div>
        </button>
      </div>
    </aside>

    <article class="panel incident-scene" aria-label="火情现场图片">
      <header class="incident-scene-heading"><div><span class="eyebrow">火情现场 <span>/</span> {{ selected.id }}</span><h2>{{ selected.title }}</h2></div><span class="status" :class="tone(selected.state)">{{ selected.state }}</span></header>
      <div class="incident-scene-toolbar"><span><EnvironmentOutlined />{{ selected.place }}</span><button class="text-button" @click="imageDialog.showModal()"><ExpandOutlined />放大图片</button></div>
      <div class="incident-photo"><img :src="photo(activePhoto.key)" :alt="`${selected.title} · ${activePhoto.label}（历史样张）`" /><span class="incident-photo-label">历史样张 · 非实时</span><span class="incident-photo-index">{{ photoIndex + 1 }} / {{ photos.length }}</span></div>
      <div class="incident-gallery"><button v-for="(item, index) in photos" :key="item.key" :class="{ active: photoIndex === index }" :aria-pressed="photoIndex === index" @click="photoIndex = index"><img :src="photo(item.key)" alt="" /><span>{{ item.label }}</span></button><div class="incident-capture"><span>发现时间</span><strong>{{ eventDate }} {{ selected.time.replace('昨日 ', '') }}</strong><small>{{ deviceName(selected.device) }} 上传</small></div></div>
      <footer class="incident-scene-actions"><span><PictureOutlined />现场证据</span><button class="button secondary" @click="$emit('video', selected.device)"><VideoCameraOutlined />查看现场画面</button></footer>
    </article>

    <aside class="panel incident-handling" aria-label="事件处置详情">
      <header class="incident-handling-heading"><h2>事件处置</h2><span class="status" :class="tone(selected.state)">{{ selected.state }}</span></header>
      <div :key="selected.id" class="incident-handling-scroll">
        <section class="incident-summary">
          <div class="incident-next"><span>当前进展</span><strong>{{ progressText }}</strong></div>
          <dl><div><dt>处置单位</dt><dd>{{ selected.unit }}</dd></div><div><dt>当前负责人</dt><dd>{{ selected.owner }}</dd></div></dl>
        </section>
        <section class="incident-timeline">
          <div class="incident-timeline-heading"><h3>处置时间轴 <small>{{ selected.timeline.length }}</small></h3><button class="text-button" @click="toggleAll">{{ allExpanded ? '全部收起' : '全部展开' }}</button></div>
          <ol><li v-for="(entry, index) in selected.timeline" :key="`${selected.id}-${index}`" :class="{ latest: index === selected.timeline.length - 1 }">
            <span class="incident-timeline-icon"><component :is="entry.kind === 'done' ? CheckCircleOutlined : entry.kind === 'notice' ? BellOutlined : entry.kind === 'review' ? SafetyCertificateOutlined : ClockCircleOutlined" /></span>
            <button class="incident-entry-heading" :aria-expanded="!collapsed.includes(index)" :aria-controls="`entry-${selected.id}-${index}`" @click="toggleEntry(index)"><strong>{{ entry.title }}</strong><DownOutlined :class="{ folded: collapsed.includes(index) }" /></button>
            <time>{{ entry.time }}</time>
            <div v-show="!collapsed.includes(index)" :id="`entry-${selected.id}-${index}`" class="incident-entry-body"><p><span>{{ entry.actorLabel || '处理人员' }}</span>{{ entry.actor }}</p><p><span>{{ entry.contentLabel || '处理内容' }}</span>{{ entry.content }}</p><p v-if="entry.result"><span>处理结果</span>{{ entry.result }}</p></div>
          </li></ol>
          <div v-if="selected.state === '待复核' || selected.state === '处理中'" class="incident-awaiting"><ClockCircleOutlined /><span>{{ nextActionText }}</span></div>
        </section>
        <section class="incident-summary incident-details">
          <h3>事件详细信息</h3>
          <dl><div><dt>事件类型</dt><dd>{{ selected.kind }}</dd></div><div><dt>关注级别</dt><dd>{{ selected.level }}</dd></div></dl>
          <p>{{ selected.description }}</p>
          <div class="incident-related"><span>关联任务</span><button v-if="selected.task" class="text-button" @click="$emit('task', selected.task)">{{ selected.task }}<ArrowRightOutlined /></button><span v-else>尚未关联</span></div>
        </section>
      </div>
      <footer class="incident-handling-actions"><button v-if="selected.state === '待复核'" class="button primary" @click="$emit('review')"><CheckCircleOutlined />人工复核</button><button v-else-if="selected.state === '处理中' && selected.task" class="button secondary" @click="$emit('task', selected.task)"><ScheduleOutlined />查看关联任务</button><span v-else class="incident-complete"><CheckCircleOutlined />{{ selected.state === '处理中' ? nextActionText : '处置记录已归档' }}</span><small>记录随事件状态同步更新</small></footer>
    </aside>
  </div>
  <dialog ref="imageDialog" class="incident-image-dialog" aria-label="查看火情证据大图" @click="closeImageOnBackdrop">
    <div class="incident-image-heading"><div><strong>{{ selected.title }}</strong><span>{{ activePhoto.label }} · 历史样张</span></div><button class="icon-button" aria-label="关闭证据大图" @click="imageDialog.close()"><CloseOutlined /></button></div>
    <img :src="photo(activePhoto.key)" alt="火情证据历史样张大图" />
  </dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ArrowRightOutlined, BellOutlined, CheckCircleOutlined, ClockCircleOutlined, CloseOutlined, DownOutlined, EnvironmentOutlined, ExpandOutlined, PictureOutlined, RightOutlined, SafetyCertificateOutlined, ScheduleOutlined, VideoCameraOutlined } from '@ant-design/icons-vue'

const props = defineProps({ events: { type: Array, required: true }, selected: { type: Object, required: true }, devices: { type: Array, required: true } })
defineEmits(['select', 'clear', 'video', 'task', 'review'])
const photoIndex = ref(0), collapsed = ref([]), imageDialog = ref(null)
const photo = key => new URL(`./assets/${key}.jpg`, import.meta.url).href
const deviceName = id => props.devices.find(device => device.id === id)?.name || '未分配'
const tone = state => state === '待复核' ? 'danger' : state === '处理中' ? 'info' : 'success'
const eventDate = computed(() => props.selected.id.startsWith('F-0904') ? '2026-09-04' : '2026-09-05')
const photos = computed(() => props.selected.image === 'thermal' ? [{ key: 'thermal', label: '红外样张' }] : [{ key: props.selected.image, label: '现场图片' }, { key: 'thermal', label: '红外参考样张' }])
const activePhoto = computed(() => photos.value[photoIndex.value] || photos.value[0])
const progressText = computed(() => ({ 待复核: '已发现线索，等待值班复核', 处理中: props.selected.owner.includes('待派遣') ? '已确认火情，等待安排处置' : '已安排核查，等待现场反馈', 已排除: '已排除火情，事件关闭', 已结束: '现场复查完成，事件归档' })[props.selected.state])
const nextActionText = computed(() => props.selected.state === '待复核' ? '等待人工复核，确认现场情况' : props.selected.owner.includes('待派遣') ? '等待安排现场处置' : '等待现场处置反馈')
const allExpanded = computed(() => collapsed.value.length === 0)
function toggleEntry (index) { collapsed.value = collapsed.value.includes(index) ? collapsed.value.filter(value => value !== index) : [...collapsed.value, index] }
function toggleAll () { collapsed.value = allExpanded.value ? props.selected.timeline.map((_, index) => index) : [] }
function closeImageOnBackdrop (event) { if (event.target === imageDialog.value) imageDialog.value.close() }
watch(() => props.selected.id, () => { photoIndex.value = 0; collapsed.value = []; imageDialog.value?.close() })
</script>

<style scoped>
.incident-workspace{display:grid;grid-template-columns:250px minmax(0,1fr) 350px;gap:16px;height:calc(100dvh - 290px);min-height:440px}
.incident-workspace>*,.incident-list-scroll,.incident-handling-scroll{min-width:0;min-height:0}
.incident-list{display:flex;flex-direction:column;gap:12px}.incident-list-heading{display:flex;align-items:center;justify-content:space-between;padding:1px 3px}.incident-list-heading h2{font-size:13px}.incident-list-heading>span{font-size:11px;color:var(--muted)}
.incident-list-scroll{display:flex;flex-direction:column;gap:10px;overflow-y:auto;padding:1px 5px 2px 1px}
.incident-card{position:relative;border:1px solid var(--border);background:var(--panel);border-radius:7px;padding:12px;text-align:left;flex-shrink:0;width:100%}.incident-card:hover{background:#19283b;border-color:#4c678c}.incident-card.selected{background:#192b40;border-color:#689bdf}.incident-card.selected:before{content:'';position:absolute;left:-1px;top:15px;bottom:15px;width:3px;background:#8bb7f8;border-radius:3px}
.incident-card-top,.incident-card-bottom{display:flex;align-items:center;justify-content:space-between}.incident-card-top time{font-size:10px;color:#94a6bf}.incident-card-body{display:flex;gap:10px;margin:10px 0}.incident-card-body img{width:53px;height:57px;object-fit:cover;border-radius:4px}.incident-card-body>div{min-width:0}.incident-card-body h3{font-size:12px;line-height:1.55;font-weight:500}.incident-card-body p{font-size:10px;color:#8fa3be;margin:4px 0 2px;line-height:1.5}.incident-card-body small{font-size:10px;color:#8fa3be}.incident-card-bottom{color:#7e96b6;font-size:10px}.incident-card-bottom .anticon{font-size:9px}
.incident-scene{display:flex;flex-direction:column;overflow:hidden}.incident-scene-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:18px 18px 13px}.incident-scene-heading>div{min-width:0}.incident-scene-heading h2{font-size:18px;margin-top:7px;line-height:1.5}.incident-scene-heading>.status{flex-shrink:0}.incident-scene-heading .eyebrow{font-size:10px}
.incident-scene-toolbar{display:flex;align-items:center;justify-content:space-between;gap:8px;padding:0 18px 13px;color:#9aabc2;font-size:11px}.incident-scene-toolbar>span{display:flex;align-items:center;gap:6px}.incident-scene-toolbar .text-button{font-size:10px;padding:0;white-space:nowrap}
.incident-photo{position:relative;flex:1;min-height:160px;overflow:hidden;margin:0 14px;background:#080d14;border-radius:5px}.incident-photo>img{width:100%;height:100%;object-fit:contain;position:absolute;inset:0}.incident-photo-label,.incident-photo-index{position:absolute;bottom:10px;background:#101925df;color:#c7d2e2;padding:4px 7px;border-radius:4px;font-size:10px}.incident-photo-label{left:10px}.incident-photo-index{right:10px}
.incident-gallery{display:flex;gap:10px;align-items:center;padding:14px}.incident-gallery>button{display:flex;align-items:center;gap:8px;padding:5px;border:1px solid #2c3d55;border-radius:5px;color:#9bacc2;font-size:10px;text-align:left}.incident-gallery>button.active{border-color:#79aafa;background:#223754;color:#c9dfff}.incident-gallery>button img{width:58px;height:43px;object-fit:cover;border-radius:3px}.incident-gallery>button span{max-width:40px;line-height:1.7}.incident-capture{margin-left:auto;text-align:right;line-height:1.8}.incident-capture span,.incident-capture small{display:block;color:#8da1bc;font-size:10px}.incident-capture strong{font-size:10px;font-weight:400;color:#c2d0e3;white-space:nowrap}
.incident-scene-actions{display:flex;justify-content:space-between;align-items:center;padding:13px 16px;border-top:1px solid var(--border);gap:8px}.incident-scene-actions>span{display:flex;gap:6px;align-items:center;color:#8fa1ba;font-size:11px}.incident-scene-actions .button{font-size:11px}
.incident-handling{display:flex;flex-direction:column;overflow:hidden}.incident-handling-heading{display:flex;justify-content:space-between;align-items:center;border-bottom:1px solid var(--border);padding:17px 18px;gap:12px}.incident-handling-heading h2{font-size:15px}.incident-handling-scroll{overflow:auto;flex:1;overscroll-behavior:contain}.incident-summary{padding:17px 18px;border-bottom:1px solid var(--border)}.incident-next{padding:10px 12px;background:#19293c;border-radius:5px;border-left:2px solid #72a7f8}.incident-next>span{font-size:10px;display:block;color:#8fa5c2;margin-bottom:5px}.incident-next>strong{font-size:12px;color:#c8dcf8;font-weight:500;line-height:1.7}.incident-summary dl{display:grid;grid-template-columns:1.15fr 1fr;gap:14px 12px;margin:16px 0}.incident-summary dt{font-size:10px;color:#8096b3;margin-bottom:5px}.incident-summary dd{margin:0;font-size:11px;color:#d1dcea;line-height:1.6}.incident-summary>p{font-size:11px;color:#9cacc2;line-height:1.8}.incident-related{display:flex;justify-content:space-between;align-items:center;gap:10px;margin-top:12px;font-size:10px;color:#8da0bc}.incident-related .text-button{padding:0;font-size:10px}
.incident-timeline{padding:18px 18px 20px}.incident-timeline-heading{display:flex;align-items:center;justify-content:space-between;gap:10px;margin-bottom:22px}.incident-timeline-heading h3{font-size:13px}.incident-timeline-heading small{font-size:10px;color:#93a8c3;background:#24364d;padding:2px 5px;border-radius:4px;margin-left:5px}.incident-timeline-heading .text-button{font-size:10px;padding:0}
.incident-timeline ol{list-style:none;margin:0;padding:0}.incident-timeline li{position:relative;padding:0 0 23px 33px;margin:0}.incident-timeline li:not(:last-child):before{content:'';position:absolute;left:10px;top:26px;bottom:4px;border-left:1px solid #344b69}.incident-timeline-icon{position:absolute;left:0;top:0;display:grid;place-items:center;width:22px;height:22px;border-radius:50%;background:#22364f;color:#92baf2;font-size:13px}.latest .incident-timeline-icon{background:#294563;color:#b9d8ff}.incident-entry-heading{display:flex;width:100%;align-items:center;justify-content:space-between;gap:10px;padding:1px 0 3px;text-align:left;color:#bdd4f4}.incident-entry-heading strong{font-size:12px;font-weight:500}.incident-entry-heading .anticon{font-size:9px;color:#809dbf;transition:transform .15s}.incident-entry-heading .folded{transform:rotate(-90deg)}.incident-timeline li>time{font-size:10px;color:#7f96b3;display:block;margin:4px 0 9px;font-variant-numeric:tabular-nums}.incident-entry-body{font-size:11px;color:#b4c3d7;line-height:1.8}.incident-entry-body p{margin:3px 0;overflow-wrap:anywhere}.incident-entry-body p>span{color:#7f96b3;margin-right:8px}.incident-awaiting{display:flex;gap:8px;align-items:center;font-size:10px;color:#9aacbf;border-top:1px dashed #334862;padding:13px 0 0 33px;line-height:1.7}
.incident-handling-actions{display:flex;flex-direction:column;align-items:stretch;border-top:1px solid var(--border);padding:12px 18px;gap:8px}.incident-handling-actions .button{font-size:12px;min-height:36px}.incident-handling-actions small{text-align:center;color:#8297b3;font-size:10px}.incident-complete{display:flex;align-items:center;justify-content:center;gap:8px;color:#89c8b1;font-size:12px;min-height:36px}
.incident-image-dialog{background:#101b2a;color:var(--text);border:1px solid #456083;border-radius:9px;width:min(1200px,94vw);max-height:92dvh;padding:16px}.incident-image-dialog::backdrop{background:#050a13db}.incident-image-heading{display:flex;align-items:center;justify-content:space-between;gap:15px;margin-bottom:14px}.incident-image-heading strong{font-size:14px}.incident-image-heading span{display:block;font-size:11px;color:#8fa5c2;margin-top:6px}.incident-image-dialog>img{width:100%;max-height:calc(92dvh - 104px);object-fit:contain;background:#080d14}
.incident-summary dl{margin-bottom:0}.incident-details{border-top:1px solid var(--border);border-bottom:0}.incident-details h3{font-size:13px}.incident-details dl{margin-bottom:14px}
@media(min-width:1600px){.incident-workspace{grid-template-columns:280px minmax(0,1fr) 390px;gap:20px}.incident-card{padding:14px}.incident-card-body img{width:63px;height:61px}.incident-card-body h3{font-size:13px}.incident-summary dd,.incident-entry-body,.incident-summary>p{font-size:12px}.incident-entry-heading strong{font-size:13px}}
@media(max-width:1250px){.incident-workspace{grid-template-columns:220px minmax(0,1fr) 310px;gap:12px}.incident-card{padding:10px}.incident-card-body{gap:8px}.incident-card-body img{width:43px;height:51px}.incident-scene-heading{padding:15px 14px 12px}.incident-scene-heading h2{font-size:16px}.incident-scene-toolbar{padding:0 14px 12px;flex-wrap:wrap}.incident-capture{display:none}.incident-handling-heading,.incident-summary,.incident-timeline{padding-left:15px;padding-right:15px}}
@media(max-width:1000px){.incident-workspace{grid-template-columns:minmax(0,1fr) 320px;height:auto;min-height:0}.incident-list{grid-column:1/-1}.incident-list-scroll{flex-direction:row;overflow-x:auto;padding-bottom:7px}.incident-card{width:230px;flex-shrink:0}.incident-scene,.incident-handling{height:650px}.incident-photo{min-height:230px}.incident-handling-scroll{overscroll-behavior:auto}}
@media(max-width:700px){.incident-workspace{grid-template-columns:minmax(0,1fr);gap:15px}.incident-list-heading{display:none}.incident-card{width:230px}.incident-scene{height:auto}.incident-photo{flex:none;aspect-ratio:4/3;min-height:0}.incident-scene-heading h2{font-size:16px}.incident-scene-toolbar{flex-wrap:nowrap}.incident-gallery{padding:12px}.incident-handling{height:auto}.incident-handling-scroll{overflow:visible}.incident-capture{display:block}.incident-summary dl{grid-template-columns:1fr 1fr}.incident-entry-body{font-size:12px}.incident-handling-actions{padding:14px 18px}.incident-scene-actions>span{font-size:10px}}
@media(max-width:380px){.incident-capture{display:none}}
</style>
