export const sections = [
  { key: 'overview', label: '运行总览', path: '/leadership-cockpit', icon: 'DashboardOutlined' },
  { key: 'video', label: '视频监控', path: '/video-monitor', icon: 'VideoCameraOutlined' },
  { key: 'events', label: '火情事件', path: '/fire-events', icon: 'FireOutlined' },
  { key: 'tasks', label: '任务中心', path: '/wayline', icon: 'ScheduleOutlined' },
  { key: 'devices', label: '设备管理', path: '/devices', icon: 'AppstoreOutlined' }
]
export const subnav = {
  tasks: [{ label: '航线库', path: '/wayline' }, { label: '巡检计划', path: '/task' }, { label: '灭火任务与审批', path: '/fire-missions' }, { label: '执行记录', path: '/task-history' }],
  devices: [{ label: '设备台账与维护', path: '/devices' }, { label: '固件管理', path: '/firmwares' }],
  management: [{ label: '媒体资料', path: '/media' }, { label: '成员权限', path: '/members' }, { label: '地图图层', path: '/layer' }, { label: '空域管理', path: '/flight-area' }],
  events: [{ label: '事件工作区', path: '/fire-events' }, { label: '高级列表与历史', path: '/fire-events-table' }, { label: '事件处置', path: '/operation-incidents' }]
}
export function sectionFor (path, query = {}) {
  if (path === '/wayline' || path === '/task-history' || path === '/task' || path.startsWith('/task/')) return 'tasks'
  if (['/fire-events', '/fire-events-table', '/operation-incidents'].includes(path)) return 'events'
  if (path === '/video-monitor' || path === '/livestream') return 'video'
  if (['/devices', '/firmwares'].includes(path)) return 'devices'
  if (['/media', '/members', '/layer', '/flight-area'].includes(path)) return 'management'
  if (path.startsWith('/fire-mission') || path.startsWith('/fire-route-preview') || path.startsWith('/fire-payload-release')) return 'tasks'
  return 'overview'
}
export function activeSubnav (target, path, query = {}) {
  const [base, search = ''] = target.split('?')
  const view = new URLSearchParams(search).get('view')
  return (path === base || (base === '/task' && path.startsWith('/task/')) || (base === '/fire-missions' && ['/fire-mission-detail/', '/fire-route-preview/', '/fire-payload-release/'].some(prefix => path.startsWith(prefix)))) && (view ? query.view === view : true)
}
