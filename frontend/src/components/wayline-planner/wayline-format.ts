// 规划航线展示/命名相关的纯函数（自 wayline.vue 抽出，供 wayline.vue 与 wayline-planner 组件共用）。
import { PlannedWaylineRecord, PlannedWaylineStatus } from '/@/types/wayline'

export interface FileItem extends File {
  uid?: string;
  status?: string;
  response?: string;
  url?: string;
}

/** 航点动作摘要标签（地图信息牌与航点列表共用） */
export const WAYPOINT_ACTION_LABELS: Record<string, string> = {
  takePhoto: '拍照',
  startRecord: '录像',
  stopRecord: '停录',
  gimbalRotate: '云台',
  hover: '悬停',
  focus: '对焦',
  rotateYaw: '转向',
}

export function formatNumber (value: unknown): string {
  const n = Number(value)
  return Number.isFinite(n) ? String(n) : '-'
}

export function formatTimestamp (value: unknown): string {
  const n = Number(value)
  return Number.isFinite(n) && n > 0 ? new Date(n).toLocaleString() : '-'
}

export function normalizePlannedWaylineStatus (recordOrStatus: PlannedWaylineRecord | string): string {
  const raw = typeof recordOrStatus === 'string' ? recordOrStatus : (recordOrStatus.taskStatus || recordOrStatus.status)
  return (raw || PlannedWaylineStatus.DRAFT).toLowerCase()
}

export function getPlannedWaylineTaskReason (record: PlannedWaylineRecord | null): string {
  if (!record) return ''
  return normalizePlannedWaylineStatus(record) === PlannedWaylineStatus.FAILED
    ? (record.taskStatusReason || '')
    : ''
}

export function formatPlannedWaylineStatus (recordOrStatus: PlannedWaylineRecord | string): string {
  const labels: Record<string, string> = {
    [PlannedWaylineStatus.DRAFT]: '草稿',
    [PlannedWaylineStatus.FILE_GENERATED]: '航线文件已生成',
    [PlannedWaylineStatus.PUBLISHING]: '下发中',
    [PlannedWaylineStatus.READY]: '就绪',
    [PlannedWaylineStatus.PREPARED]: '已准备',
    [PlannedWaylineStatus.EXECUTING]: '执行中',
    [PlannedWaylineStatus.PAUSED]: '已暂停',
    [PlannedWaylineStatus.BROKEN]: '已中断',
    [PlannedWaylineStatus.STOPPED]: '已停止',
    [PlannedWaylineStatus.COMPLETED]: '已完成',
    [PlannedWaylineStatus.FINISHED]: '已完成',
    [PlannedWaylineStatus.FAILED]: '失败',
    [PlannedWaylineStatus.CANCELED]: '已取消',
    published: '已发布',
  }
  const fallback = typeof recordOrStatus === 'string' ? recordOrStatus : ''
  return labels[normalizePlannedWaylineStatus(recordOrStatus)] || fallback || '草稿'
}

export function canOverwritePlannedWayline (record: PlannedWaylineRecord): boolean {
  return normalizePlannedWaylineStatus(record) === PlannedWaylineStatus.DRAFT && !record.publishedWaylineId
}

export function formatSafePlannedWaylineTimestamp (date: Date): string {
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}${pad(date.getMonth() + 1)}${pad(date.getDate())} ${pad(date.getHours())}${pad(date.getMinutes())}${pad(date.getSeconds())}`
}

export function sanitizeDjiWaylineName (name: string, fallback = '规划航线'): string {
  const sanitized = String(name || '')
    .trim()
    .replace(/[<>:"/|?*._\\]+/g, '-')
    .replace(/\s+/g, ' ')
    .replace(/^-+|-+$/g, '')
    .trim()
  return sanitized || fallback
}
