const now = Date.parse('2026-09-15T10:00:00Z')
const taskStates = ['draft', 'executing', 'completed', 'finished', 'file_generated', 'failed']
const labels = { draft: '草稿', executing: '执行中', completed: '已完成', finished: '已完成', file_generated: '航线已生成', failed: '异常', CANDIDATE: '待复核', NEW: '待复核', LOW_CONFIDENCE: '低置信度待复核', IGNORED: '已忽略', MISSION_CREATED: '已关联任务' }
export const tasks = Array.from({ length: 47 }, (_, i) => ({ taskId: 'task-' + i, name: i === 0 ? '巡逻-面状航线-20260914170401-森林巡护重点区域联合巡检任务' : i === 1 ? 'UNBROKEN' + 'A1B2C3D4'.repeat(18) : '巡检计划 ' + (i + 1), aircraftModelKey: i % 2 ? 'DJI Matrice 300 RTK' : 'DJI Matrice 4T', aircraftSn: 'AIRCRAFT-' + '0123456789'.repeat(i === 1 ? 5 : 1), status: 'published', taskStatus: taskStates[i % taskStates.length], statusLabel: labels[taskStates[i % taskStates.length]], taskProgress: i % 5 ? 43 : null, dataStatus: i === 1 ? 'STALE' : 'RECORDED', updateTime: now - i * 120000, executedTime: now - i * 3600000, lastProgressTime: now - 60000, totalWaypoints: 65, currentWaypointIndex: 12, waypoints: Array.from({length:65},(_,n)=>({latitude:34.0123456789+n/1000,longitude:108.123456789+n/1000})), timeline: [{time:now-500000,title:'创建巡检计划'},{time:now-300000,title:'发布航线'},{time:now-100000,title:'任务执行记录：长文本与异常说明需要在手机屏幕内完整换行。'.repeat(3)}] }))
export const events = Array.from({ length: 31 }, (_, i) => { const id = 'agent-' + 'abcdef0123456789'.repeat(i === 0 ? 12 : 4) + i; const status = ['CANDIDATE','NEW','LOW_CONFIDENCE','IGNORED','MISSION_CREATED'][i%5]; return { eventId:id, name:'火情事件 '+id, source:'无人机巡检', deviceSn:i===0?'AIRCRAFT-'+ '0123456789'.repeat(7):'1ZNBJ9600C00C7', status, statusLabel:labels[status], updateTime:now-i*3600000, lat:34.123456789,lng:108.987654321,fireLevel:'待复核',geoQuality:'ESTIMATED',geoMethod:'OSD',geoErrorRadiusM:20,positionNote:'按原始记录展示坐标与精度，需现场复核', timeline:[{time:now-5000,action:'识别记录',decisionReason:'这是一条用于检查换行和长文本的处置记录。'.repeat(6)}] } })
let saved = new Set(['task-2'])
let prefs = {urgentEvents:false,taskUpdates:false,reportReady:false,wechatSubscriptionEnabled:false}
const me = {user:{displayName:'巡护管理员',userId:'preview-user'},workspace:{name:'森林防火与应急处置联合指挥工作空间（长名称检查）',workspaceId:'preview-workspace'}}

// Synthetic verification fixture; no production records or credentials are used.
function flightReport(id, page = 1, scenario = 'normal') {
  const empty = scenario === 'empty', partial = scenario === 'partial', count = empty ? 0 : 123
  const rows = Array.from({ length: count }, (_, i) => ({ recordId: i+1, observedAt: now-732000+i*6000, receivedAt:now-732000+i*6000+100, source: 'MSDK_STATE', kind: 'telemetry', speedMs: i === 0 || i === 122 ? 0 : 7.2, relativeHeightM: i === 0 || i === 122 ? 0 : 80, verticalSpeedMs: 0.1, latitude: 34.0123+i/100000, longitude:108.1234, batteryPercent: 96-i*.4, flying:i>0&&i<122, flightMode:i>100?'GO_HOME':'GPS_NORMAL',gpsCount:24,windSpeedMs:3, waypointIndex:Math.floor(i/3), progressPercent:Math.min(100,i) }))
  return { taskId:id,runId:empty?null:'preview-run',flightId:'preview-flight',snapshotRecordId:count,aircraftName:'DJI Matrice 4T',aircraftSn:'PREVIEW-SN',runs:empty?[]:[{runId:'preview-run',firstRecordAt:now-732000,closedAt:now}],
    planned:{defaultHeight:80,maxSpeed:12,rthAltitude:100,takeoffSecurityHeight:20,totalWaypoints:65,payloadName:'机身集成相机',finishAction:'返航',rcLostAction:'继续任务',creator:'巡护管理员',publisher:'值班人员'},
    metrics:empty?{}:{takeoffAt:partial?null:now-726000,landingAt:now,returnAt:now-120000,flightDurationSeconds:partial?null:726,observedDurationSeconds:732,averageSpeedMs:7.2,maximumSpeedMs:10.5,maximumHeightM:82.3,minimumHeightM:0,maximumWindSpeedMs:3.8,takeoffBatteryPercent:partial?null:96,returnBatteryPercent:54,landingBatteryPercent:47,batteryConsumedPercentagePoints:partial?null:49,firstRecordedBatteryPercent:96,lastRecordedBatteryPercent:47,observedDistanceM:4120,telemetryCount:123},
    quality:{recordCount:count,gapCount:partial?1:0,status:empty?'NOT_RECORDED':partial?'PARTIAL':'OBSERVED',missingFields:empty?['起降时间','速度与高度','起降电量']:partial?['起飞状态与起飞电量']:[],warnings:[empty?'历史没有采集飞行遥测，无法补算。':'这是用于页面检查的合成飞行记录。','起降时间取状态变化采样；电量消耗单位为百分点。','垂直速度使用 NED 坐标，向下为正。']},
    records:{items:rows.slice((page-1)*50,page*50),total:count,page,pageSize:50,hasMore:page*50<count},
    timeline:empty?[]:[{time:now-732000,kind:'wayline_state_change',state:'任务准备完成'},{time:now-726000,kind:'wayline_state_change',state:'执行中'},{time:now-120000,kind:'wayline_state_change',state:'返航'},{time:now,kind:'wayline_state_change',state:'已落地'}],
    relatedEvents:{available:!empty,total:empty?0:2,hasMore:false,association:'同设备、同记录时段的候选关联，需要人工确认归属',items:empty?[]:events.slice(0,2).map(v=>({...v,eventTimestamp:v.updateTime}))},
    media:{available:!empty,total:empty?0:1,hasMore:false,note:'与本次执行编号一致的媒体元数据',items:empty?[]:[{fileId:'media-1',fileName:'巡检区域热成像与可见光成果记录.jpg',createTime:now}]} }
}

export function createApi(scenario) {
  let bindingSubmitted = scenario === 'pending'
  return (url, method, data) => {
    const path = url.pathname.replace('/miniapp/api/v1','')
    if (path === '/auth/wechat/login') return ['binding','pending'].includes(scenario) ? {loginStatus:bindingSubmitted?'PENDING_APPROVAL':'BINDING_REQUIRED',bindingTicket:'preview-binding',expiresIn:600} : {loginStatus:'AUTHENTICATED',sessionId:'preview-session',accessToken:'preview-access',refreshToken:'preview-refresh'}
    if (path === '/auth/bindings') {bindingSubmitted=true;return {loginStatus:'PENDING_APPROVAL',bindingStatus:'PENDING'}}
    if (path === '/auth/logout') return {loggedOut:true}
    if (path === '/me') return me
    if (path === '/notification-preferences') {if(method==='PUT')prefs={...prefs,...data};return {...prefs}}
    if (path === '/dashboard/summary') return {metrics:{todayTasks:scenario==='empty'?0:12,urgentEvents:scenario==='empty'?0:31,onlineAircraft:null},conclusion:{headline:scenario==='empty'?'当前没有待复核事件':'31 项事件待复核'},activeTasks:scenario==='empty'?[]:tasks.slice(0,3),urgentEvents:scenario==='empty'?[]:events.slice(0,3),reports:scenario==='empty'?[]:tasks.slice(0,1),warnings:['实时设备状态尚未接入，在线数量暂不可确认。','执行报告来自任务记录，正式签阅与 PDF 暂未开放。'],generatedAt:new Date(now).toISOString()}
    if(path.includes('/flight-report')) return flightReport(decodeURIComponent(path.split('/')[2]),Number(url.searchParams.get('page')||1),scenario)
    const match=path.match(/^\/(inspection-tasks|events|reports)(?:\/([^/]+))?(\/bookmark)?$/)
    if (!match) throw new Error('未知检查接口')
    const kind=match[1],id=match[2]?decodeURIComponent(match[2]):''
    if(match[3]) {data.saved?saved.add(id):saved.delete(id);return {saved:saved.has(id)}}
    let rows=kind==='events'?events:tasks
    if(id) {const row=rows.find(r=>(r.eventId||r.taskId)===id);if(!row)throw new Error('记录不存在');return kind==='reports'?{...row,flightReport:flightReport(id,1,scenario),reportId:id,source:'TASK_RECORD',saved:saved.has(id),reportNote:'根据当前任务执行记录整理，未签阅或归档。'}:{...row,...(kind==='inspection-tasks'?{flightReport:flightReport(id,1,scenario)}:{})}}
    const status=url.searchParams.get('status'),keyword=url.searchParams.get('keyword')||''
    if(status) rows=rows.filter(r=>status==='completed'?['completed','finished'].includes(r.taskStatus):status==='PENDING_REVIEW'?['NEW','CANDIDATE','LOW_CONFIDENCE'].includes(r.status):(r.taskStatus||r.status)===status)
    if(keyword) rows=rows.filter(r=>[r.name,r.taskId,r.aircraftModelKey].some(v=>String(v||'').includes(keyword)))
    if(kind==='reports'&&url.searchParams.get('savedOnly')==='true')rows=rows.filter(r=>saved.has(r.taskId))
    if(scenario==='empty')rows=[]
    const page=Number(url.searchParams.get('page')||1),size=Number(url.searchParams.get('pageSize')||20),total=rows.length
    return {items:rows.slice((page-1)*size,page*size),page,pageSize:size,total,hasMore:page*size<total}
  }
}
