<template>
  <div class="app-shell">
    <header class="app-header">
      <a class="brand" href="#overview"><span class="brand-icon"><DeploymentUnitOutlined /></span><span>无人机火情智巡<small>UAVFIRE · 巡护指挥台</small></span></a>
      <nav class="main-nav" aria-label="驾驶舱页面">
        <a v-for="item in tabs" :key="item.key" :href="`#${item.key}`" :class="{ active: tab === item.key }" :aria-current="tab === item.key ? 'page' : undefined"><component :is="tabIcons[item.key]" /><span>{{ item.label }}</span><b v-if="item.key === 'events' && pending.length" class="nav-count">{{ pending.length }}</b></a>
      </nav>
      <div class="header-end"><span class="prototype-tag">交互原型 · 示例数据</span><a href="#management/media" class="icon-button platform-link" aria-label="平台管理"><SettingOutlined /></a><button class="icon-button notification-button" aria-label="查看待办提醒" @click="notifications = !notifications"><BellOutlined /><i v-if="pending.length"></i></button><span class="avatar">值班</span></div>
    </header>

    <main>
      <div class="page-heading"><div><div class="eyebrow">秦岭北麓示范区 <span>/</span> 指挥台</div><h1>{{ activeTab.label }}</h1><p>{{ activeTab.hint }}</p></div><div class="heading-actions"><span class="date"><CalendarOutlined /> 2026 年 9 月 5 日 · 周六</span><button class="button secondary" @click="refresh"><ReloadOutlined :spin="refreshing" />{{ refreshing ? '更新中' : '刷新视图' }}</button></div></div>

      <section v-if="tab === 'overview'" class="overview-page">
        <div class="summary-row">
          <button class="summary-item" @click="eventFilter = '待复核'; go('events')"><span class="summary-icon orange"><FireOutlined /></span><div><span>待复核事件</span><div class="summary-value">{{ pending.length }}<small>条</small></div></div><div class="summary-tail"><span>需要人工确认</span><ArrowRightOutlined /></div></button>
          <button class="summary-item" @click="taskFilter = '执行中'; go('tasks', 'monitor')"><span class="summary-icon blue"><SendOutlined /></span><div><span>执行中任务</span><div class="summary-value">{{ running.length }}<small>项</small></div></div><div class="summary-tail"><span>查看执行进展</span><ArrowRightOutlined /></div></button>
          <button class="summary-item" @click="deviceFilter = '需关注'; go('devices')"><span class="summary-icon yellow"><WarningOutlined /></span><div><span>需关注设备</span><div class="summary-value">{{ warnings.length }}<small>架</small></div></div><div class="summary-tail"><span>低电量 / 视频弱网</span><ArrowRightOutlined /></div></button>
        </div>
        <div class="overview-shortcuts"><a href="#tasks/planner"><EnvironmentOutlined /><span>航线规划<small>地图编排与航点设置</small></span><ArrowRightOutlined /></a><a href="#tasks/routes"><ScheduleOutlined /><span>航线库<small>复用航线，创建巡检任务</small></span><ArrowRightOutlined /></a><a href="#operations/delivery"><SendOutlined /><span>灭火与投送<small>任务准备、审批与反馈</small></span><ArrowRightOutlined /></a><a href="#management/media"><AppstoreOutlined /><span>媒体资料<small>现场证据与任务资料</small></span><ArrowRightOutlined /></a></div><div class="overview-main">
          <section class="panel map-panel"><div class="panel-header"><h2>巡护态势</h2><span class="subtle">在线飞机 <strong>20</strong> / 20</span></div><PatrolMap :events="events" :devices="devices" @select-event="openEvent" @select-device="openDevice" /></section>
          <section class="panel attention-panel"><div class="panel-header"><h2>需要关注</h2><span class="small-tag">{{ pending.length + warnings.length }} 项</span></div><div class="attention-list"><button v-for="event in pending" :key="event.id" class="attention-item" @click="openEvent(event)"><span class="attention-icon orange"><FireOutlined /></span><div><div class="item-top"><span class="status" :class="tone(event.state)">{{ event.state }}</span><time>{{ event.time }}</time></div><h3>{{ event.title }}</h3><p>{{ event.place }} · {{ deviceName(event.device) }}</p></div><RightOutlined /></button><button v-for="device in warnings" :key="device.id" class="attention-item" @click="openDevice(device)"><span class="attention-icon yellow"><WarningOutlined /></span><div><div class="item-top"><span class="status warn">设备提醒</span><time>14:30</time></div><h3>{{ device.name }} · {{ device.state }}</h3><p>{{ device.id === 7 ? '电量 22%，请关注剩余作业时间' : '视频接收不稳定，建议检查上行网络' }}</p></div><RightOutlined /></button></div><div class="panel-bottom"><SafetyCertificateOutlined /> 只有实际事件和异常进入待办</div></section>
        </div>
        <section class="panel running-panel"><div class="panel-header"><h2>正在执行</h2><button class="text-button" @click="go('tasks')">全部任务 <ArrowRightOutlined /></button></div><div class="running-items"><button v-for="task in running" :key="task.id" class="running-item" @click="openTask(task)"><span class="task-icon"><SendOutlined /></span><div class="running-name"><h3>{{ task.name }}</h3><p>{{ deviceName(task.device) }} · 已执行 {{ task.duration }}</p></div><div class="compact-progress"><span>航点 {{ task.points }}</span><div class="progress-track"><i :style="{ width: `${task.progress}%` }"></i></div></div><strong>{{ task.progress }}%</strong><RightOutlined /></button></div></section>
      </section>

      <VideoWorkspace v-else-if="tab === 'video'" :devices="devices" :high-ids="highIds" :focused-id="videoDevice" @focus="videoDevice = $event" @request-high="requestVideoHigh" @related-event="openVideoEvent" @notify="toast" />

      <section v-else-if="tab === 'events'" class="events-page">
        <div class="content-toolbar"><div class="filter-tabs"><button v-for="filter in eventFilters" :key="filter" :class="{ active: eventFilter === filter }" @click="eventFilter = filter">{{ filter }}<span v-if="filter === '待复核'">{{ pending.length }}</span></button></div><label class="search-field"><SearchOutlined /><input v-model="eventSearch" placeholder="搜索事件、区域或编号" aria-label="搜索火情事件" /></label></div>
        <EventWorkspace :events="filteredEvents" :selected="selectedEvent" :devices="devices"
          @select="selectedEvent = $event" @clear="eventFilter = '全部'; eventSearch = ''"
          @video="selectVideo($event); go('video')" @task="openTaskById" @review="reviewDialog = true" />
      </section>

      <TaskCenter v-else-if="tab === 'tasks'" :tasks="tasks" :devices="devices" :selected-task="selectedTask" @select="openTask" @create="createPatrolTask" @action="runTaskDemo" @notify="toast"><template #execution>
        <div class="content-toolbar"><div class="filter-tabs"><button v-for="filter in ['全部', '执行中', '待执行', '已暂停', '已完成']" :key="filter" :class="{ active: taskFilter === filter }" @click="taskFilter = filter">{{ filter }}</button></div><span class="subtle">{{ filteredTasks.length }} 项任务</span></div><div class="tasks-layout"><section class="task-list"><button v-for="task in filteredTasks" :key="task.id" class="panel task-card" :class="{ selected: selectedTask.id === task.id }" @click="selectedTask = task"><div class="item-top"><span class="small-tag">{{ task.kind }}</span><span class="status" :class="tone(task.state)">{{ task.state }}</span></div><h3>{{ task.name }}</h3><p>{{ task.id }} · {{ deviceName(task.device) }}</p><div class="task-progress-label"><span>执行进度</span><strong>{{ task.progress }}%</strong></div><div class="progress-track"><i :style="{ width: `${task.progress}%` }"></i></div><div class="task-card-foot"><span><ClockCircleOutlined /> {{ task.state === '待执行' ? '开始时间待安排' : task.start + ' 开始' }}</span><span>查看详情 <ArrowRightOutlined /></span></div></button><div v-if="!filteredTasks.length" class="panel empty">暂无此状态任务</div></section><article v-if="filteredTasks.length" class="panel task-detail"><div class="detail-heading"><div><span class="eyebrow">任务详情 <span>/</span> {{ selectedTask.id }}</span><h2>{{ selectedTask.name }}</h2></div><span class="status" :class="tone(selectedTask.state)">{{ selectedTask.state }}</span></div><PlanningMap v-if="selectedTask.routePoints" :key="selectedTask.id" :points="selectedTask.routePoints" :title="selectedTask.routeName" /><PatrolMap v-else :events="selectedTask.event ? events.filter(e => e.id === selectedTask.event) : []" :devices="devices.filter(d => d.id === selectedTask.device)" @select-event="openEvent" @select-device="openDevice" /><div class="task-facts"><div><span>执行飞机</span><strong>{{ deviceName(selectedTask.device) }}</strong></div><div><span>已用时间</span><strong>{{ selectedTask.duration }}</strong></div><div><span>完成航点</span><strong>{{ selectedTask.points }}</strong></div></div><p class="detail-description">{{ selectedTask.note }}</p><div class="detail-actions"><button class="button primary" @click="selectVideo(selectedTask.device); go('video')"><VideoCameraOutlined />查看任务画面</button><button v-if="selectedTask.event" class="button secondary" @click="openEventById(selectedTask.event)">查看关联事件</button><button class="text-button" @click="exportTask"><DownloadOutlined />导出示例记录</button></div><div class="notice-inline"><InfoCircleOutlined /> 演示下发、暂停和完成只更新本次页面状态；真实执行接入现有任务服务。</div></article></div>
      </template></TaskCenter>
      <OperationsCenter v-else-if="tab === 'operations'" :devices="devices" :events="events" @notify="toast" @video="selectVideo($event); go('video')" @event="openEvent" @task="openTaskById" />
      <ManagementCenter v-else-if="tab === 'management'" :devices="devices" :events="events" @notify="toast" @devices="go('devices')" @planner="go('tasks','planner')" @event="openEvent" />
      <section v-else class="devices-page"><nav class="work-subnav" aria-label="设备管理功能"><a href="#devices" class="active">设备台账</a><a href="#management/maintenance">设备维护</a><a href="#management/firmware">固件管理</a><a href="#operations/flight">飞行控制</a></nav><div class="device-summary"><span><i class="legend-dot green"></i>在线 <strong>20</strong> / 20</span><span>巡护中 <strong>4</strong> 架</span><span>需关注 <strong class="warn-text">2</strong> 架</span><span>高清上传 <strong>4</strong> 路</span></div><div class="content-toolbar"><div class="filter-tabs"><button v-for="filter in ['全部', '巡护中', '待命', '需关注']" :key="filter" :class="{ active: deviceFilter === filter }" @click="deviceFilter = filter">{{ filter }}</button></div><label class="search-field"><SearchOutlined /><input v-model="deviceSearch" placeholder="搜索飞机名称或型号" aria-label="搜索设备" /></label></div><div class="panel table-panel"><table><thead><tr><th>飞机</th><th>运行状态</th><th>剩余电量</th><th>上传档位</th><th>视频链路</th><th>操作</th></tr></thead><tbody><tr v-for="device in filteredDevices" :key="device.id"><td><div class="device-name"><span class="aircraft-mini"><SendOutlined /></span><div><strong>{{ device.name }}</strong><small>{{ device.model }}</small></div></div></td><td><span class="status" :class="tone(device.state)">{{ device.state }}</span></td><td><div class="battery"><div><i :style="{ width: `${device.battery}%`, background: device.battery < 30 ? '#e7b75e' : '#58bfa3' }"></i></div>{{ device.battery }}%</div></td><td><span class="quality" :class="{ high: highIds.includes(device.id) }">{{ highIds.includes(device.id) ? '高清 · 4 Mbps' : '低清 · 0.5 Mbps' }}</span></td><td><span :class="device.channel === '弱网' ? 'warn-text' : 'subtle'">{{ device.channel }}</span></td><td><button class="text-button" @click="selectVideo(device.id); go('video')">看画面</button><button class="text-button" @click="openDevice(device)">详情</button></td></tr></tbody></table><div v-if="!filteredDevices.length" class="empty">未找到符合条件的设备</div><div class="table-footer">共 {{ filteredDevices.length }} 架飞机 <span>状态为演示数据，更新不会影响真实设备</span></div></div></section>

      <footer class="app-footer"><span>UAVFIRE <i>·</i> 页面结构预览</span><span>示例业务数据 / 历史图片样张 / 无真实控制指令</span><button @click="resetDemo">重置演示</button></footer>
    </main>
    <Transition name="toast"><div v-if="toastMessage" class="toast-message" role="status"><CheckCircleOutlined />{{ toastMessage }}</div></Transition>
    <div v-if="notifications" class="notification-popover"><div class="panel-header"><h2>待办提醒</h2><button class="icon-button" aria-label="关闭提醒" @click="notifications = false"><CloseOutlined /></button></div><button v-for="event in pending" :key="event.id" class="notification-item" @click="notifications = false; openEvent(event)"><FireOutlined /><span><strong>{{ event.title }}</strong><small>{{ event.place }} · {{ event.time }}</small></span><RightOutlined /></button><div v-if="!pending.length" class="empty">当前没有待复核事件</div></div>
    <div v-if="deviceDetail" class="dialog-backdrop" @click.self="deviceDetail = null"><section class="dialog device-dialog" role="dialog" aria-modal="true" aria-label="设备详情" tabindex="-1" ref="modalEl"><div class="detail-heading"><div><span class="eyebrow">设备详情</span><h2>{{ deviceDetail.name }}</h2></div><button class="icon-button" aria-label="关闭设备详情" @click="deviceDetail = null"><CloseOutlined /></button></div><span class="status" :class="tone(deviceDetail.state)">{{ deviceDetail.state }}</span><div class="dialog-facts"><div><span>型号</span><strong>{{ deviceDetail.model }}</strong></div><div><span>设备编号</span><strong>{{ deviceDetail.sn }}</strong></div><div><span>剩余电量</span><strong>{{ deviceDetail.battery }}%</strong></div><div><span>视频档位</span><strong>{{ highIds.includes(deviceDetail.id) ? '高清' : '低清' }}</strong></div><div><span>Agent 状态</span><strong>在线（示例）</strong></div><div><span>视频链路</span><strong>{{ deviceDetail.channel }}</strong></div></div><p v-if="['低电量','视频弱网'].includes(deviceDetail.state)" class="notice-inline"><WarningOutlined />{{ deviceDetail.id === 7 ? '建议关注剩余作业时间，并联系现场人员确认。' : '建议核查遥控器上行网络，当前保持低清档位。' }}</p><button class="button primary wide" @click="selectVideo(deviceDetail.id); deviceDetail = null; go('video')">查看该飞机画面 <ArrowRightOutlined /></button></section></div>
    <div v-if="reviewDialog" class="dialog-backdrop" @click.self="reviewDialog = false"><section class="dialog" role="dialog" aria-modal="true" aria-label="人工复核" tabindex="-1" ref="modalEl"><div class="detail-heading"><div><span class="eyebrow">原型操作</span><h2>人工复核</h2></div><button class="icon-button" aria-label="关闭人工复核" @click="reviewDialog = false"><CloseOutlined /></button></div><p>{{ selectedEvent.title }}</p><label class="form-label">复核结论<select v-model="reviewResult"><option>确认火情</option><option>排除火情</option></select></label><label class="form-label">复核说明<textarea v-model="reviewNote" placeholder="填写现场判断依据（仅保存在本次演示中）" rows="3"></textarea></label><div class="notice-inline">提交后会更新本次演示的事件状态与首页待办数量。</div><button class="button primary wide" :disabled="!reviewNote.trim()" @click="submitReview">保存演示复核结果</button></section></div>
    <div v-if="highDialog" class="dialog-backdrop" @click.self="highDialog = false"><section class="dialog" role="dialog" aria-modal="true" aria-label="切换高清名额" tabindex="-1" ref="modalEl"><div class="detail-heading"><h2>切换高清关注</h2><button class="icon-button" aria-label="关闭高清切换" @click="highDialog = false"><CloseOutlined /></button></div><p>四个高清名额已占用。请选择一架回到低清，将名额交给 {{ deviceName(videoDevice) }}。</p><label class="form-label">让出名额的飞机<select v-model.number="replaceHighId"><option v-for="id in highIds" :key="id" :value="id">{{ deviceName(id) }}</option></select></label><div class="notice-inline">切换后，所选飞机降为低清，关注画面使用高清。总量保持四路高清、十六路低清。</div><button class="button primary wide" @click="switchHigh">预览切换结果</button></section></div>
  </div>
</template>

<script setup>
import { ref, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { DeploymentUnitOutlined, DashboardOutlined, VideoCameraOutlined, FireOutlined, ScheduleOutlined, AppstoreOutlined, BellOutlined, CalendarOutlined, ReloadOutlined, ArrowRightOutlined, RightOutlined, SendOutlined, WarningOutlined, SafetyCertificateOutlined, SearchOutlined, ClockCircleOutlined, CheckCircleOutlined, InfoCircleOutlined, DownloadOutlined, CloseOutlined, ControlOutlined, SettingOutlined, EnvironmentOutlined } from '@ant-design/icons-vue'
import PatrolMap from './PatrolMap.vue'
import PlanningMap from './PlanningMap.vue'
import TaskCenter from './TaskCenter.vue'
import OperationsCenter from './OperationsCenter.vue'
import ManagementCenter from './ManagementCenter.vue'
import { resetOperations } from './operations-state'
import { planning, resetPlanning } from './planning-data'
import VideoWorkspace from './VideoWorkspace.vue'
import EventWorkspace from './EventWorkspace.vue'
import { clearVideoLayout } from './video-layout'
import { tabs, initialEvents, initialTasks, devices } from './data.js'
const tabIcons = { overview: DashboardOutlined, video: VideoCameraOutlined, events: FireOutlined, tasks: ScheduleOutlined, devices: AppstoreOutlined, operations: ControlOutlined }
const clone = value => JSON.parse(JSON.stringify(value))
const events = ref(clone(initialEvents)), tasks = ref(clone(initialTasks))
const tab = ref('overview'), eventFilter = ref('全部'), eventSearch = ref(''), taskFilter = ref('全部'), deviceFilter = ref('全部'), deviceSearch = ref('')
const activeTab = computed(() => tabs.find(t => t.key === tab.value) || (tab.value === 'management' ? {label:'平台管理',hint:'管理媒体资料、设备维护、成员权限与地图资源。'} : tabs[0]))
const selectedEvent = ref(events.value[0]), selectedTask = ref(tasks.value[0])
const pending = computed(() => events.value.filter(e => e.state === '待复核'))
const running = computed(() => tasks.value.filter(t => t.state === '执行中'))
const warnings = devices.filter(d => ['低电量', '视频弱网'].includes(d.state))
const eventFilters = ['全部', '待复核', '处理中', '已关闭']
const filteredEvents = computed(() => events.value.filter(e => (eventFilter.value === '全部' || (eventFilter.value === '已关闭' ? ['已排除','已结束'].includes(e.state) : e.state === eventFilter.value)) && `${e.title} ${e.place} ${e.id}`.includes(eventSearch.value.trim())))
const filteredTasks = computed(() => tasks.value.filter(t => taskFilter.value === '全部' || t.state === taskFilter.value))
const filteredDevices = computed(() => devices.filter(d => (deviceFilter.value === '全部' || (deviceFilter.value === '需关注' ? warnings.includes(d) : d.state === deviceFilter.value)) && `${d.name} ${d.model}`.toLowerCase().includes(deviceSearch.value.trim().toLowerCase())))
const videoDevice = ref(0)
const highIds = ref([1, 2, 3, 4]), highDialog = ref(false), replaceHighId = ref(1)
const deviceDetail = ref(null), notifications = ref(false), reviewDialog = ref(false), reviewResult = ref('确认火情'), reviewNote = ref(''), modalEl = ref(null)
const toastMessage = ref(''), refreshing = ref(false)
let toastTimer, refreshTimer, previousFocus
const deviceName = id => devices.find(d => d.id === id)?.name || '未分配'
const tone = state => ['待复核', '优先'].includes(state) ? 'danger' : ['低电量','视频弱网','待执行'].includes(state) ? 'warn' : ['执行中','巡护中','处理中'].includes(state) ? 'info' : ['已完成','已结束','已排除'].includes(state) ? 'success' : 'neutral'
function readHash () {
  const [key, id] = location.hash.slice(1).split('/')
  tab.value = (tabs.some(t => t.key === key) || key === 'management') ? key : 'overview'
  if (key === 'events' && id) selectedEvent.value = events.value.find(e => e.id === id) || selectedEvent.value
  if (key === 'tasks' && id) selectedTask.value = tasks.value.find(t => t.id === id) || selectedTask.value
  if (key === 'video' && Number(id) > 0 && Number(id) <= 20) { videoDevice.value = Number(id) }
}
function go (key, id = '') { location.hash = key + (id ? '/' + id : ''); tab.value = key; window.scrollTo(0, 0) }
function openEvent (event) { selectedEvent.value = events.value.find(e => e.id === event.id) || event; eventFilter.value = '全部'; eventSearch.value = ''; go('events', event.id) }
function openEventById (id) { const event = events.value.find(e => e.id === id); if (event) openEvent(event) }
function openTask (task) { selectedTask.value = task; taskFilter.value = '全部'; go('tasks', task.id) }
function openTaskById (id) { const task = tasks.value.find(t => t.id === id); if (task) openTask(task) }
function createPatrolTask(task) { tasks.value.unshift(task); selectedTask.value = tasks.value[0]; taskFilter.value = '全部' }
function runTaskDemo({ task, action }) {
  const current = tasks.value.find(t => t.id === task.id)
  if (!current) return
  taskFilter.value = '全部'
  const total = current.routePoints?.length || Number(current.points.split('/')[1]) || 1
  if (action === 'start' && current.state === '待执行') { current.state = '执行中'; current.progress = Math.round(100 / total); current.points = `1 / ${total}`; current.start = '14:35'; current.duration = '1 分钟'; current.note = '演示任务已下发，开始按保存的航线执行。'; const route = planning.routes.find(r => r.id === current.routeId); if (route) route.uses++ }
  else if (action === 'pause' && current.state === '执行中') { current.state = '已暂停'; current.note = '演示执行已暂停，可以继续。' }
  else if (action === 'resume' && current.state === '已暂停') { current.state = '执行中'; current.note = '演示执行已恢复。' }
  else if (action === 'finish' && ['执行中','已暂停'].includes(current.state)) { current.state = '已完成'; current.progress = 100; current.points = `${total} / ${total}`; current.duration = '18 分钟'; current.note = '演示任务已完成，执行记录已保留；可以使用原航线再次创建任务。' }
  selectedTask.value = current
  toast(`演示任务：${current.state}`)
}
function openDevice (device) { deviceDetail.value = devices.find(d => d.id === device.id) || device }
function selectVideo (id) { videoDevice.value = id }
function openVideoEvent (id) { videoDevice.value = id; const event = events.value.find(e => e.device === videoDevice.value); if (event) openEvent(event); else toast('该飞机当前没有关联火情事件') }
function toast (message) { clearTimeout(toastTimer); toastMessage.value = message; toastTimer = setTimeout(() => { toastMessage.value = '' }, 3200) }
function refresh () { refreshing.value = true; clearTimeout(refreshTimer); refreshTimer = setTimeout(() => { refreshing.value = false; toast('示例视图已更新') }, 450) }
function requestVideoHigh (id) { videoDevice.value = id; if (highIds.value.includes(videoDevice.value)) { toast('该画面已占用高清名额'); return } replaceHighId.value = highIds.value[0]; highDialog.value = true }
function switchHigh () { highIds.value = highIds.value.map(id => id === replaceHighId.value ? videoDevice.value : id); highDialog.value = false; toast('已预览名额切换：保持 4 高清 + 16 低清') }
function submitReview () {
  if (!reviewNote.value.trim()) return
  const event = selectedEvent.value
  const confirmed = reviewResult.value === '确认火情'
  const nextMinute = Math.max(14 * 60 + 33, ...event.timeline.map(entry => {
    const match = entry.time.match(/(\d{2}):(\d{2})$/)
    return match ? Number(match[1]) * 60 + Number(match[2]) + 1 : 0
  }))
  const reviewTime = `2026-09-05 ${String(Math.floor(nextMinute / 60)).padStart(2, '0')}:${String(nextMinute % 60).padStart(2, '0')}`
  event.state = confirmed ? '处理中' : '已排除'
  event.owner = confirmed ? '值班员（待派遣）' : '值班员'
  event.timeline.push({ kind: confirmed ? 'review' : 'done', title: '人工复核', time: reviewTime, actor: '值班员', contentLabel: '复核说明', content: reviewNote.value.trim(), result: confirmed ? '确认火情，等待安排现场处置' : '排除火情，事件关闭' })
  reviewDialog.value = false
  reviewNote.value = ''
  toast('演示结果已保存，处置时间轴与首页待办已更新')
}
function resetDemo () { resetPlanning(); resetOperations(); events.value = clone(initialEvents); tasks.value = clone(initialTasks); selectedEvent.value = events.value[0]; selectedTask.value = tasks.value[0]; highIds.value = [1,2,3,4]; eventFilter.value = taskFilter.value = deviceFilter.value = '全部'; eventSearch.value = deviceSearch.value = ''; clearVideoLayout(); videoDevice.value = 1; go('overview'); nextTick(clearVideoLayout); toast('已恢复初始演示数据') }
function exportTask () { const task = selectedTask.value; const text = `UAVFIRE 任务演示记录（非真实任务）\n\n任务：${task.name}\n编号：${task.id}\n状态：${task.state}\n飞机：${deviceName(task.device)}\n进度：${task.progress}%\n说明：${task.note}\n`; const url = URL.createObjectURL(new Blob([text], { type: 'text/plain;charset=utf-8' })); const a = document.createElement('a'); a.href = url; a.download = `${task.id}-演示记录.txt`; a.click(); URL.revokeObjectURL(url); toast('示例任务记录已导出') }
const dialogOpen = computed(() => !!deviceDetail.value || reviewDialog.value || highDialog.value)
watch(dialogOpen, async open => { if (open) { previousFocus = document.activeElement; await nextTick(); modalEl.value?.focus() } else previousFocus?.focus?.() })
function handleKey (event) {
  if (event.key === 'Escape') { if (dialogOpen.value || notifications.value) event.preventDefault(); deviceDetail.value = null; reviewDialog.value = false; highDialog.value = false; notifications.value = false }
  if (event.key === 'Tab' && dialogOpen.value && modalEl.value) {
    const els = [...modalEl.value.querySelectorAll('button:not(:disabled),input,select,textarea,[tabindex="0"]')]; const first = els[0], last = els.at(-1)
    if (event.shiftKey && (document.activeElement === first || document.activeElement === modalEl.value)) { event.preventDefault(); last?.focus() }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
  }
}
watch(tab, () => { notifications.value = false; window.scrollTo(0, 0) })
watch(filteredEvents, list => { if (list.length && !list.some(e => e.id === selectedEvent.value.id)) selectedEvent.value = list[0] })
watch(filteredTasks, list => { if (list.length && !list.some(t => t.id === selectedTask.value.id)) selectedTask.value = list[0] })
onMounted(() => { readHash(); window.addEventListener('hashchange', readHash); window.addEventListener('keydown', handleKey) })
onBeforeUnmount(() => { clearTimeout(toastTimer); clearTimeout(refreshTimer); window.removeEventListener('hashchange', readHash); window.removeEventListener('keydown', handleKey) })
</script>
