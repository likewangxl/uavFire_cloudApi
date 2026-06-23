// FC100 投放任务状态与操作（自 wayline.vue 抽出为模块级 store）。
// 状态为模块级单例：Fc100DeliveryView 渲染面板，wayline.vue 的详情弹窗等共享设施
// 也会引用（getFc100GeneratedWaylineActions）。轮询定时器由视图组件的生命周期启停。
import { computed, reactive } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { deliveryApi } from '/@/api/fire/delivery'
import type { DeliveryCommandBody, DeliveryCommandRef, DeliveryDeviceDTO, DeliveryDeviceProperties, DeliveryTaskOperationResult, DeliveryTaskStatus } from '/@/api/fire/delivery'
import { ELocalStorageKey } from '/@/types'
import { PlannedWaylineRecord } from '/@/types/wayline'
import { previewPlannedWayline, setFlightPositionFromWgs, setTrackedAircraft } from '/@/hooks/use-wayline-planning'
import { setFc100PositionDevice, setFc100PositionProps } from '/@/hooks/use-fc100-position'
import { FileItem, sanitizeDjiWaylineName } from '/@/components/wayline-planner/wayline-format'

export const fc100PlanningState = reactive({
  devices: [] as DeliveryDeviceDTO[],
  selectedDeviceSn: '',
  selectedDeviceProps: null as DeliveryDeviceProperties | null,
  selectedRecord: null as PlannedWaylineRecord | null,
  taskId: '',
  taskStatus: null as DeliveryTaskStatus | null,
  lastResult: '',
  loadingAction: '',
})

// 航线 → 已创建的投放任务 的本地映射（后端无"按航线查任务"接口，故前端持久化以便"反显"）。
// 持久化到 localStorage，刷新后仍能反显;状态以再次「刷新任务」为准。
export interface Fc100RouteTask {
  taskId: string
  deviceSn: string
  taskName?: string
  updatedAt: number
}
const FC100_ROUTE_TASKS_KEY = 'fc100_route_tasks'
function loadFc100RouteTasks (): Record<string, Fc100RouteTask> {
  try {
    const raw = localStorage.getItem(FC100_ROUTE_TASKS_KEY)
    const parsed = raw ? JSON.parse(raw) : {}
    return parsed && typeof parsed === 'object' ? parsed : {}
  } catch (e) {
    return {}
  }
}
export const fc100RouteTasks = reactive<Record<string, Fc100RouteTask>>(loadFc100RouteTasks())
function persistFc100RouteTasks () {
  try {
    localStorage.setItem(FC100_ROUTE_TASKS_KEY, JSON.stringify(fc100RouteTasks))
  } catch (e) {
    // ignore quota / serialization errors
  }
}
export function getFc100RouteTask (plannedWaylineId: string): Fc100RouteTask | null {
  return (plannedWaylineId && fc100RouteTasks[plannedWaylineId]) || null
}
export function setFc100RouteTask (plannedWaylineId: string, task: Fc100RouteTask) {
  if (!plannedWaylineId) return
  fc100RouteTasks[plannedWaylineId] = task
  persistFc100RouteTasks()
}
export function clearFc100RouteTask (plannedWaylineId: string) {
  if (fc100RouteTasks[plannedWaylineId]) {
    delete fc100RouteTasks[plannedWaylineId]
    persistFc100RouteTasks()
  }
}

function isFc100AircraftDevice (device: DeliveryDeviceDTO) {
  const bindStatus = String(device.bindStatus || '').toLowerCase()
  const deviceType = String(device.deviceType || '').toLowerCase()
  return bindStatus !== 'rc' && deviceType !== 'rc'
}

export const fc100AircraftDevices = computed(() => fc100PlanningState.devices.filter(isFc100AircraftDevice))

export const fc100SelectedRecordName = computed(() => {
  const record = fc100PlanningState.selectedRecord
  if (!record) return '请在下方已保存规划航线中选择已生成KMZ的记录。'
  return record.kmzUrl ? record.name : `${record.name}（请先生成航线文件）`
})

export function formatFc100DeliveryAircraftModel () {
  return 'DJI FlyCart 100'
}

export function formatFc100DeviceSelectLabel (device: DeliveryDeviceDTO) {
  return `${formatFc100DeliveryAircraftModel()} · ${device.deviceSn}`
}

export function isFc100DeviceOnline (device: DeliveryDeviceDTO) {
  const online = String(device.online || '').toLowerCase()
  return online === 'true' || online === 'online' || online === '1'
}

export const isFc100TerminalCommandLoading = computed(() => [
  'ropeDown',
  'ropeStop',
  'ropeUp',
  'releaseHook',
  'returnHome',
].includes(fc100PlanningState.loadingAction))

export const fc100TerminalControlHint = computed(() => getFc100TerminalControlBlockedReason() || '已满足投放控制条件')

let fc100RealtimeTimer: number | null = null
let fc100RealtimeRefreshing = false

function workspaceId (): string {
  return localStorage.getItem(ELocalStorageKey.WorkspaceId) || ''
}

function syncFc100DeviceFlightPosition (props: DeliveryDeviceProperties | null) {
  if (!props) return
  const deviceSn = props.deviceSn || fc100PlanningState.selectedDeviceSn
  if (!deviceSn) return
  // FC100 有进行中的投放任务 → 该机认领地图跟踪，挡掉其它(停地)飞机的位置写入。
  if (fc100PlanningState.taskId && !isFc100TaskTerminal(fc100PlanningState.taskStatus)) {
    setTrackedAircraft(deviceSn)
  }
  setFlightPositionFromWgs(deviceSn, props.longitude, props.latitude, {
    height: props.altitude,
    updatedAt: props.osdTimestamp || Date.now(),
  })
}

export function getFc100GeneratedWaylineActions (record: PlannedWaylineRecord) {
  return [{
    key: 'fc100-import-generated',
    label: '导入生成KMZ并创建任务',
    primary: false,
    wrap: true,
    disabled: !record.kmzUrl,
    handler: (record: PlannedWaylineRecord) => onFc100UseGeneratedWayline(record),
  }]
}

function getFc100ApiBody (res: any) {
  return res?.data ?? res
}

function formatFc100OperationResult (result: DeliveryTaskOperationResult | null | undefined, fallback: string): string {
  if (!result) return fallback
  return result.displayMessage || result.apiMessage || result.reason || fallback
}

function formatFc100TaskStatus (status: DeliveryTaskStatus | null | undefined): string {
  if (!status) return '未返回任务状态'
  const parts = [
    status.displayMessage || status.message || status.reason || '',
    status.status ? `状态 ${status.status}` : '',
    status.phase ? `阶段 ${status.phase}` : '',
    status.progressPercent !== null && status.progressPercent !== undefined ? `进度 ${status.progressPercent}%` : '',
    status.taskCode !== null && status.taskCode !== undefined ? `任务码 ${status.taskCode}` : '',
  ].filter(Boolean)
  return parts.join('；') || '任务状态已刷新'
}

function getFc100ErrorText (error: any, fallback: string): string {
  return error?.response?.data?.message || error?.message || fallback
}

export function getSelectedFc100DeviceSn (): string {
  const selected = fc100AircraftDevices.value.find(device => device.deviceSn === fc100PlanningState.selectedDeviceSn)
  const firstDrone = fc100AircraftDevices.value.find(device => device.bindStatus === 'drone')
  const firstAircraft = fc100AircraftDevices.value[0]
  return selected?.deviceSn || firstDrone?.deviceSn || firstAircraft?.deviceSn || ''
}

function getFc100OperatorId (): string {
  return localStorage.getItem(ELocalStorageKey.Username) || 'web'
}

function getFc100WaylineFileTaskName (filename: string): string {
  const baseName = String(filename || '')
    .replace(/\.(kmz|kml)$/i, '')
    .trim()
  return sanitizeDjiWaylineName(baseName || 'FC100航线', 'FC100航线')
}

export function beforeFc100WaylineUpload (file: FileItem) {
  if (!file.name || !/\.(kmz|kml)$/i.test(file.name)) {
    message.error('文件格式错误，请选择 FC100 KMZ/KML 航线文件。')
    return false
  }
  return true
}

export const uploadFc100WaylineFile = async (options?: { file?: FileItem; onSuccess?: (res: any) => void; onError?: (err: any) => void }) => {
  const file = options?.file
  if (!file) {
    message.error('请选择 FC100 KMZ/KML 航线文件。')
    return
  }
  const deviceSn = getSelectedFc100DeviceSn()
  if (!deviceSn) {
    message.warning('请先选择FC100飞机设备。')
    options?.onError?.(new Error('FC100 device is required'))
    return
  }
  fc100PlanningState.selectedDeviceSn = deviceSn
  fc100PlanningState.loadingAction = 'directImport'
  const fileData = new FormData()
  fileData.append('file', file, file.name)
  fileData.append('deviceSn', deviceSn)
  fileData.append('taskName', sanitizeDjiWaylineName(getFc100WaylineFileTaskName(file.name), 'FC100航线'))
  fileData.append('operatorId', getFc100OperatorId())
  fileData.append('remark', `created from uploaded fc100 wayline ${file.name}`)
  try {
    const res = await deliveryApi.importCreateWaylineTask(fileData)
    const body = getFc100ApiBody(res)
    if (body.code !== 0) {
      fc100PlanningState.lastResult = `FC100导入航线文件并创建任务失败：${body.message || '接口返回异常'}`
      options?.onError?.(new Error(body.message || 'FC100 direct wayline import failed'))
      return
    }
    fc100PlanningState.taskId = body.data?.taskId || ''
    fc100PlanningState.taskStatus = null
    fc100PlanningState.selectedRecord = null
    fc100PlanningState.lastResult = `FC100任务已创建：${fc100PlanningState.taskId || '未返回任务ID'}`
    message.success('FC100航线文件已导入并创建任务')
    options?.onSuccess?.(res)
  } catch (error) {
    fc100PlanningState.lastResult = `FC100导入航线文件并创建任务失败：${getFc100ErrorText(error, '接口调用失败')}`
    options?.onError?.(error)
  } finally {
    fc100PlanningState.loadingAction = ''
  }
}

function buildFc100CommandBody (data?: Record<string, unknown>): DeliveryCommandBody {
  return {
    operatorId: getFc100OperatorId(),
    data,
  }
}

export function isFc100TaskTerminal (status: DeliveryTaskStatus | null): boolean {
  if (!status) return false
  const text = `${status.status || ''} ${status.phase || ''}`.toLowerCase()
  return status.progressPercent === 100 ||
    ['completed', 'complete', 'finished', 'finish', 'success', 'succeeded', 'done'].some(key => text.includes(key))
}

function isFc100HoveringEnough (props: DeliveryDeviceProperties | null): boolean {
  if (!props) return false
  if (props.onlineStatus === false) return false
  const horizontalSpeed = Number(props.horizontalSpeed ?? 0)
  const verticalSpeed = Number(props.verticalSpeed ?? 0)
  return Math.abs(horizontalSpeed) <= 0.5 && Math.abs(verticalSpeed) <= 0.3
}

function getFc100TerminalControlBlockedReason (): string {
  if (!fc100PlanningState.taskId) return '请先创建FC100航线任务'
  if (!isFc100TaskTerminal(fc100PlanningState.taskStatus)) return '等待航线完成'
  if (!fc100PlanningState.selectedDeviceProps) return '请先刷新FC100状态'
  if (fc100PlanningState.selectedDeviceProps.onlineStatus === false) return 'FC100设备离线'
  if (!isFc100HoveringEnough(fc100PlanningState.selectedDeviceProps)) return '等待飞机悬停稳定'
  return ''
}

export function canUseFc100TerminalControls (): boolean {
  return !getFc100TerminalControlBlockedReason()
}

function confirmFc100TerminalAction (title: string, actionText: string, danger = false): Promise<boolean> {
  const deviceSn = getSelectedFc100DeviceSn() || '-'
  const status = fc100PlanningState.taskStatus?.status || fc100PlanningState.taskStatus?.phase || '-'
  return new Promise(resolve => {
    Modal.confirm({
      title,
      content: `设备 ${deviceSn}，当前任务状态 ${status}。请确认飞机已到达终点并处于安全悬停状态后执行${actionText}。`,
      okText: `确认${actionText}`,
      cancelText: '取消',
      okButtonProps: danger ? { danger: true } : undefined,
      onOk: () => resolve(true),
      onCancel: () => resolve(false),
    })
  })
}

function formatFc100CommandResult (result: DeliveryCommandRef | null | undefined, fallback: string): string {
  if (!result) return fallback
  const parts = [
    result.deviceCmdMethod ? `方法 ${result.deviceCmdMethod}` : '',
    result.status ? `状态 ${result.status}` : '',
    result.bid ? `指令 ${result.bid}` : '',
  ].filter(Boolean)
  return parts.length ? `${fallback}：${parts.join('；')}` : fallback
}

async function sendFc100TerminalCommand (
  loadingAction: string,
  actionText: string,
  danger: boolean,
  request: (deviceSn: string, body: DeliveryCommandBody) => Promise<any>,
) {
  const deviceSn = getSelectedFc100DeviceSn()
  if (!deviceSn) {
    message.warning('请先选择FC100飞机设备。')
    return
  }
  if (loadingAction !== 'returnHome' && !canUseFc100TerminalControls()) {
    const reason = getFc100TerminalControlBlockedReason()
    message.warning(reason || '当前状态不允许投放控制。')
    return
  }
  const confirmed = await confirmFc100TerminalAction(`确认执行${actionText}吗？`, actionText, danger)
  if (!confirmed) return
  fc100PlanningState.loadingAction = loadingAction
  try {
    const res = await request(deviceSn, buildFc100CommandBody())
    const body = getFc100ApiBody(res)
    if (body.code !== 0) {
      fc100PlanningState.lastResult = `${actionText}失败：${body.message || '接口返回异常'}`
      return
    }
    fc100PlanningState.lastResult = formatFc100CommandResult(body.data, `${actionText}指令已发送`)
    message.success(`${actionText}指令已发送`)
    await refreshFc100SelectedDeviceProps(deviceSn)
  } catch (error) {
    fc100PlanningState.lastResult = `${actionText}失败：${getFc100ErrorText(error, '接口调用失败')}`
  } finally {
    fc100PlanningState.loadingAction = ''
  }
}

export function handleFc100RopeDown () {
  return sendFc100TerminalCommand('ropeDown', '放绳', false, deliveryApi.sendFc100RopeDownCommand)
}

export function handleFc100RopeStop () {
  return sendFc100TerminalCommand('ropeStop', '停止放收绳', false, deliveryApi.sendFc100RopeStopCommand)
}

export function handleFc100RopeUp () {
  return sendFc100TerminalCommand('ropeUp', '收绳', false, deliveryApi.sendFc100RopeUpCommand)
}

export function handleFc100ReleaseHook () {
  return sendFc100TerminalCommand('releaseHook', '脱钩', true, deliveryApi.sendFc100ReleaseHookCommand)
}

export function handleFc100ReturnHome () {
  return sendFc100TerminalCommand('returnHome', '返航', true, (deviceSn, body) =>
    deliveryApi.sendDeviceCommand(deviceSn, 'return_home', body))
}

export async function handleFc100RefreshDevices () {
  fc100PlanningState.loadingAction = 'devices'
  try {
    const res = await deliveryApi.listDevices(workspaceId())
    const body = getFc100ApiBody(res)
    if (body.code !== 0) {
      fc100PlanningState.lastResult = `FC100设备列表获取失败：${body.message || '接口返回异常'}`
      return
    }
    fc100PlanningState.devices = body.data || []
    if (!fc100AircraftDevices.value.some(device => device.deviceSn === fc100PlanningState.selectedDeviceSn)) {
      fc100PlanningState.selectedDeviceSn = fc100AircraftDevices.value.find(device => device.bindStatus === 'drone')?.deviceSn ||
        fc100AircraftDevices.value[0]?.deviceSn ||
        ''
    }
    if (fc100PlanningState.selectedDeviceSn) {
      await handleFc100SelectDevice(fc100PlanningState.selectedDeviceSn)
    } else {
      fc100PlanningState.selectedDeviceProps = null
      fc100PlanningState.lastResult = 'FC100设备列表为空，请确认飞机已绑定到当前FC100 workspace/group。'
    }
  } catch (error) {
    fc100PlanningState.lastResult = `FC100设备列表获取失败：${getFc100ErrorText(error, '接口调用失败')}`
  } finally {
    fc100PlanningState.loadingAction = ''
  }
}

export async function handleFc100SelectDevice (deviceSn: string) {
  fc100PlanningState.selectedDeviceSn = deviceSn
  setFc100PositionDevice(deviceSn)
  if (!deviceSn) {
    fc100PlanningState.selectedDeviceProps = null
    setFc100PositionProps(null)
    return
  }
  // 用户选择 FC100 设备即设为跟踪目标（进行中的任务会在轮询里持续覆盖）。
  setTrackedAircraft(deviceSn)
  await refreshFc100SelectedDeviceProps(deviceSn)
}

async function refreshFc100SelectedDeviceProps (deviceSn = fc100PlanningState.selectedDeviceSn) {
  if (!deviceSn) return null
  try {
    const res = await deliveryApi.deviceProps(deviceSn)
    const body = getFc100ApiBody(res)
    if (!body || body.code !== 0) {
      fc100PlanningState.lastResult = `FC100设备物模型获取失败：${body?.message || '接口返回异常'}`
      return null
    }
    fc100PlanningState.selectedDeviceProps = body.data || null
    setFc100PositionProps(fc100PlanningState.selectedDeviceProps)
    syncFc100DeviceFlightPosition(fc100PlanningState.selectedDeviceProps)
    return fc100PlanningState.selectedDeviceProps
  } catch (error) {
    // 反显的持久化设备可能已失效/离线，轮询取物模型失败不应抛断轮询
    fc100PlanningState.lastResult = `FC100设备物模型获取失败：${getFc100ErrorText(error, '接口调用失败')}`
    return null
  }
}

async function refreshFc100TaskStatus (showLoading = true) {
  const taskId = fc100PlanningState.taskId
  if (!taskId) return null
  if (showLoading) {
    fc100PlanningState.loadingAction = 'status'
  }
  try {
    const res = await deliveryApi.waylineTaskStatus(taskId)
    const body = getFc100ApiBody(res)
    if (body.code !== 0) {
      fc100PlanningState.lastResult = `FC100任务状态获取失败：${body.message || '接口返回异常'}`
      return null
    }
    fc100PlanningState.taskStatus = body.data || null
    fc100PlanningState.lastResult = formatFc100TaskStatus(fc100PlanningState.taskStatus)
    return fc100PlanningState.taskStatus
  } catch (error) {
    fc100PlanningState.lastResult = `FC100任务状态获取失败：${getFc100ErrorText(error, '接口调用失败')}`
    return null
  } finally {
    if (showLoading) {
      fc100PlanningState.loadingAction = ''
    }
  }
}

async function refreshFc100RealtimeState () {
  if (fc100RealtimeRefreshing) return
  fc100RealtimeRefreshing = true
  try {
    if (fc100PlanningState.selectedDeviceSn) {
      await refreshFc100SelectedDeviceProps()
    }
    if (fc100PlanningState.taskId) {
      await refreshFc100TaskStatus(false)
    }
  } catch (error) {
    // Realtime refresh stays quiet; explicit refresh/actions still show errors.
  } finally {
    fc100RealtimeRefreshing = false
  }
}

export function startFc100RealtimeRefresh () {
  if (fc100RealtimeTimer !== null) return
  fc100RealtimeTimer = window.setInterval(refreshFc100RealtimeState, 3000)
}

export function stopFc100RealtimeRefresh () {
  if (fc100RealtimeTimer === null) return
  window.clearInterval(fc100RealtimeTimer)
  fc100RealtimeTimer = null
  fc100RealtimeRefreshing = false
}

function buildFc100StartPreflightWarnings (props: DeliveryDeviceProperties | null) {
  const warnings: string[] = []
  if (!props) {
    warnings.push('未获取到飞行器状态')
    return warnings
  }
  if (props.onlineStatus === false) warnings.push('飞行器离线')
  if (props.batteryPercent !== null && props.batteryPercent !== undefined && props.batteryPercent < 30) warnings.push('电量低于30%')
  if (!props.rtkStatus) warnings.push('RTK/GPS状态未知')
  if (props.latitude === null || props.latitude === undefined || props.longitude === null || props.longitude === undefined) warnings.push('未获取到经纬度')
  return warnings
}

async function onFc100UseGeneratedWayline (record: PlannedWaylineRecord) {
  fc100PlanningState.selectedRecord = record
  previewPlannedWayline(record)
  if (!fc100PlanningState.devices.length) {
    await handleFc100RefreshDevices()
  }
  await handleFc100ImportGeneratedWaylineTask(record)
}

export function onFc100PreviewGeneratedWayline (record: PlannedWaylineRecord) {
  fc100PlanningState.selectedRecord = record
  previewPlannedWayline(record)
}

export function selectFc100GeneratedWayline (record: PlannedWaylineRecord) {
  fc100PlanningState.selectedRecord = record
  previewPlannedWayline(record)
  if (!record.kmzUrl) {
    message.warning('请先生成航线文件后再创建FC100任务。')
  }
}

export async function handleFc100ImportGeneratedWaylineTask (record = fc100PlanningState.selectedRecord) {
  if (!record) {
    message.warning('请先选择已保存规划航线。')
    return
  }
  if (!record.kmzUrl) {
    message.warning('请先生成航线文件后再导入FC100。')
    return
  }
  const deviceSn = getSelectedFc100DeviceSn()
  if (!deviceSn) {
    message.warning('请先选择FC100飞机设备。')
    return
  }
  fc100PlanningState.selectedRecord = record
  fc100PlanningState.selectedDeviceSn = deviceSn
  fc100PlanningState.loadingAction = 'import'
  try {
    const res = await deliveryApi.importGeneratedPlannedWaylineTask({
      workspaceId: workspaceId(),
      plannedWaylineId: record.plannedWaylineId,
      deviceSn,
      taskName: sanitizeDjiWaylineName(record.name || 'FC100规划航线'),
      operatorId: localStorage.getItem(ELocalStorageKey.Username) || 'web',
      remark: `created from planned wayline ${record.plannedWaylineId}`,
    })
    const body = getFc100ApiBody(res)
    if (body.code !== 0) {
      fc100PlanningState.lastResult = `FC100导入生成KMZ并创建任务失败：${body.message || '接口返回异常'}`
      return
    }
    fc100PlanningState.taskId = body.data?.taskId || ''
    fc100PlanningState.taskStatus = null
    fc100PlanningState.lastResult = `FC100任务已创建：${fc100PlanningState.taskId || '未返回任务ID'}`
    message.success('FC100航线任务已创建')
  } catch (error) {
    fc100PlanningState.lastResult = `FC100导入生成KMZ并创建任务失败：${getFc100ErrorText(error, '接口调用失败')}`
  } finally {
    fc100PlanningState.loadingAction = ''
  }
}

export async function handleFc100StartGeneratedWaylineTask () {
  const taskId = fc100PlanningState.taskId
  if (!taskId) {
    message.warning('请先创建FC100任务。')
    return
  }
  const deviceSn = getSelectedFc100DeviceSn()
  if (!deviceSn) {
    message.warning('请先选择FC100飞机设备。')
    return
  }
  fc100PlanningState.loadingAction = 'start'
  try {
    const props = await refreshFc100SelectedDeviceProps(deviceSn)
    const warnings = buildFc100StartPreflightWarnings(props)
    if (warnings.length) {
      const text = `FC100 开始执行航线前检查未通过：${warnings.join('；')}`
      fc100PlanningState.lastResult = text
      message.warning(text)
      return
    }
    const res = await deliveryApi.startWaylineTask(taskId, deviceSn)
    const body = getFc100ApiBody(res)
    if (body.code !== 0) {
      fc100PlanningState.lastResult = `FC100开始执行失败：${body.message || '接口返回异常'}`
      return
    }
    const operation = body.data as DeliveryTaskOperationResult | null
    fc100PlanningState.lastResult = formatFc100OperationResult(operation, 'FC100开始执行航线指令已发送。')
    if (operation?.accepted === false) {
      message.warning(fc100PlanningState.lastResult)
    } else {
      message.success('FC100开始执行航线指令已发送')
    }
    await handleFc100GeneratedWaylineTaskStatus()
  } catch (error) {
    fc100PlanningState.lastResult = `FC100开始执行失败：${getFc100ErrorText(error, '接口调用失败')}`
  } finally {
    fc100PlanningState.loadingAction = ''
  }
}

export async function handleFc100GeneratedWaylineTaskStatus () {
  if (!fc100PlanningState.taskId) {
    message.warning('请先创建FC100任务。')
    return
  }
  await refreshFc100TaskStatus(true)
}
