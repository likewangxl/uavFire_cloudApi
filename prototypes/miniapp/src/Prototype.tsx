import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import {
  ActivityLogIcon, ArrowLeftIcon, ArrowRightIcon, BellIcon, BookmarkIcon,
  CheckCircledIcon, ChevronDownIcon, ChevronRightIcon, ClockIcon, Cross2Icon,
  DashboardIcon, ExclamationTriangleIcon, FileTextIcon, HomeIcon,
  InfoCircledIcon, LayersIcon, LightningBoltIcon, LockClosedIcon, MagnifyingGlassIcon,
  MixerHorizontalIcon, PersonIcon, ReloadIcon, TargetIcon, VideoIcon,
} from '@radix-ui/react-icons';
import { BottomSheet, Carousel, FlowStack, KeyboardInput, MobileScroll, useFlow, useKeyboard, type FlowScreen } from './mobile';

type Tab = 'home' | 'tasks' | 'situation' | 'reports' | 'mine';
type Scenario = 'normal' | 'empty' | 'stale' | 'error';
type Task = { id: string; name: string; zone: string; status: string; time: string; model: string; progress: number; pilot: string; distance: string; duration: string; eventId?: string; reportId?: string };
type Event = { id: string; name: string; zone: string; level: string; status: string; time: string; taskId: string; description: string };
type Report = { id: string; name: string; zone: string; date: string; status: string; taskId: string; distance: string; duration: string; findings: number };
const tasks: Task[] = [
  { id: 'P-0914-01', name: '北岭重点区域巡护', zone: '北岭林区', status: '执行中', time: '09:30', model: 'Matrice 4T', progress: 68, pilot: '一号巡护组', distance: '6.8', duration: '24', eventId: 'E-014' },
  { id: 'P-0914-02', name: '河谷输电走廊巡检', zone: '河谷林区', status: '待执行', time: '10:30', model: 'Matrice 350 RTK', progress: 0, pilot: '二号巡护组', distance: '8.2', duration: '32' },
  { id: 'P-0914-03', name: '南山防火带晨间巡查', zone: '南山林区', status: '已完成', time: '08:20', model: 'Matrice 4T', progress: 100, pilot: '三号巡护组', distance: '5.6', duration: '22', reportId: 'R-003' },
  { id: 'P-0914-04', name: '北岭林缘复检', zone: '北岭林区', status: '异常', time: '09:00', model: 'Matrice 4T', progress: 35, pilot: '一号巡护组', distance: '3.4', duration: '15', eventId: 'E-015' },
  { id: 'P-0913-05', name: '河谷水源地巡护', zone: '河谷林区', status: '已完成', time: '昨日', model: 'Matrice 350 RTK', progress: 100, pilot: '二号巡护组', distance: '7.3', duration: '29', reportId: 'R-002' },
];
const events: Event[] = [
  { id: 'E-014', name: '北岭东段发现疑似烟点', zone: '北岭林区', level: '高关注', status: '待复核', time: '09:46', taskId: 'P-0914-01', description: '巡护过程中识别到疑似烟雾，需结合现场图像和飞手反馈复核。当前为位置估算，不作为投放依据。' },
  { id: 'E-015', name: '林缘区域出现持续烟雾', zone: '北岭林区', level: '需关注', status: '处置中', time: '09:22', taskId: 'P-0914-04', description: '值班组已收到反馈，正在协调地面人员核查。复检任务等待飞手确认。' },
  { id: 'E-013', name: '河谷作业点热源复核', zone: '河谷林区', level: '一般', status: '已关闭', time: '昨日', taskId: 'P-0913-05', description: '经现场复核为正常作业热源，已记录核查结论并归档。' },
];
const reports: Report[] = [
  { id: 'R-003', name: '南山防火带晨间巡查报告', zone: '南山林区', date: '09-14 08:52', status: '待签阅', taskId: 'P-0914-03', distance: '5.6', duration: '22', findings: 0 },
  { id: 'R-002', name: '河谷水源地巡护报告', zone: '河谷林区', date: '09-13 16:40', status: '已归档', taskId: 'P-0913-05', distance: '7.3', duration: '29', findings: 1 },
  { id: 'R-001', name: '北岭巡护阶段报告', zone: '北岭林区', date: '09-14 09:48', status: '生成中', taskId: 'P-0914-01', distance: '6.8', duration: '24', findings: 1 },
];
const tabs = [
  { key: 'home' as Tab, title: '首页', icon: HomeIcon },
  { key: 'tasks' as Tab, title: '任务', icon: LayersIcon },
  { key: 'situation' as Tab, title: '态势', icon: TargetIcon },
  { key: 'reports' as Tab, title: '报告', icon: FileTextIcon },
  { key: 'mine' as Tab, title: '我的', icon: PersonIcon },
];
const titles: Record<Tab, string> = { home: '无人机火情智巡', tasks: '巡检任务', situation: '火情态势', reports: '巡检报告', mine: '我的工作台' };
const scenarioNames: Record<Scenario, string> = { normal: '示例场景', empty: '暂无数据', stale: '数据延迟', error: '连接异常' };
type AppState = {
  tab: Tab; setTab: (tab: Tab) => void; scenario: Scenario; setScenario: (scenario: Scenario) => void;
  zone: string; setZone: (zone: string) => void; notify: (text: string) => void;
  saved: string[]; setSaved: (ids: string[]) => void; preferences: Record<string, boolean>;
  setPreferences: (values: Record<string, boolean>) => void;
};
const Context = createContext<AppState>(null!);
const useApp = () => useContext(Context);

export default function Prototype() {
  const keyboard = useKeyboard();
  const [tab, setTab] = useState<Tab>('home');
  const [scenario, setScenario] = useState<Scenario>('normal');
  const [zone, setZone] = useState('全部林区');
  const [toast, setToast] = useState('');
  const [saved, setSaved] = useState<string[]>([]);
  const [preferences, setPreferences] = useState<Record<string, boolean>>({ 紧急事件: true, 任务动态: true, 报告完成: false });
  useEffect(() => { if (!toast) return; const timer = setTimeout(() => setToast(''), 3200); return () => clearTimeout(timer); }, [toast]);
  return <Context.Provider value={{ tab, setTab, scenario, setScenario, zone, setZone, notify: setToast, saved, setSaved, preferences, setPreferences }}>
    <div className="uav-app" onBlur={event => {
      const leavingInput = event.target instanceof HTMLInputElement || event.target instanceof HTMLTextAreaElement;
      const enteringInput = event.relatedTarget instanceof HTMLInputElement || event.relatedTarget instanceof HTMLTextAreaElement;
      if (leavingInput && !enteringInput) keyboard.hide();
    }}>
      <FlowStack initial={rootScreen} />
      {toast && <div className="uav-toast" role="status"><CheckCircledIcon />{toast}</div>}
    </div>
  </Context.Provider>;
}

const rootScreen: FlowScreen = {
  id: 'root', headerHeight: 54, header: () => <Header />, footerHeight: 66, footer: () => <TabBar />,
  render: () => <RootPage />,
};
function Header({ title }: { title?: string }) {
  const app = useApp(); const flow = useFlow(); const keyboard = useKeyboard();
  return <div className="uav-header">
    {title ? <button className="icon-button" aria-label="返回" onClick={() => { keyboard.hide(); flow.pop(); }}><ArrowLeftIcon /></button> : <span className="brand-icon"><DashboardIcon /></span>}
    <div className="header-title"><strong>{title || titles[app.tab]}</strong><span>领导移动驾驶舱</span></div>
    {!title && <button className="icon-button" aria-label="查看通知" onClick={() => { keyboard.hide(); flow.push(detailScreen('notifications', '消息中心', <Notifications />)); }}><BellIcon /><i className="notification-dot" /></button>}
    {title && <span className="preview-mini">预览</span>}
  </div>;
}
function TabBar() {
  const { tab, setTab } = useApp(); const keyboard = useKeyboard();
  return <nav className="uav-tabs" aria-label="小程序主导航">{tabs.map(item => <button key={item.key} aria-current={tab === item.key ? 'page' : undefined} onClick={() => { keyboard.hide(); setTab(item.key); }}><item.icon /><span>{item.title}</span></button>)}</nav>;
}
function RootPage() {
  const { tab } = useApp();
  return <MobileScroll key={tab} className="uav-scroll"><main className="uav-content">
    <PreviewBar />
    {tab === 'home' ? <Home /> : tab === 'tasks' ? <TaskList /> : tab === 'situation' ? <Situation /> : tab === 'reports' ? <ReportList /> : <Mine />}
  </main></MobileScroll>;
}
function detailScreen(id: string, title: string, content: ReactNode): FlowScreen {
  return { id, headerHeight: 54, header: () => <Header title={title} />, render: () => <MobileScroll className="uav-scroll"><main className="uav-content detail-content"><PreviewBar compact />{content}</main></MobileScroll> };
}
function PreviewBar({ compact = false }: { compact?: boolean }) {
  const { scenario, setScenario } = useApp(); const [open, setOpen] = useState(false);
  return <><div className="preview-bar"><span><span className="tiny-dot" />交互预览 · 示例数据</span>{!compact && <button onClick={() => setOpen(true)} aria-label="切换预览状态">{scenarioNames[scenario]}<MixerHorizontalIcon /></button>}</div>
    <BottomSheet open={open} onOpenChange={setOpen} title="体验不同数据状态" description="这里的切换仅用于页面预览，不影响真实系统。" snap={0.58}>
      <div className="sheet-options">{(['normal', 'empty', 'stale', 'error'] as Scenario[]).map(value => <button key={value} className={scenario === value ? 'chosen' : ''} onClick={() => { setScenario(value); setOpen(false); }}><span><strong>{scenarioNames[value]}</strong><small>{{ normal: '浏览任务、事件和报告的完整流程', empty: '查看首次进入与暂无记录的界面', stale: '查看数据过期时的提醒与状态变化', error: '体验连接异常和重新加载' }[value]}</small></span>{scenario === value && <CheckCircledIcon />}</button>)}</div>
    </BottomSheet></>;
}
function ZonePicker() {
  const { zone, setZone } = useApp(); const [open, setOpen] = useState(false);
  return <><button className="zone-picker" onClick={() => setOpen(true)}><TargetIcon />{zone}<ChevronDownIcon /></button>
    <BottomSheet open={open} onOpenChange={setOpen} title="查看范围" description="预览区域为虚构示例。" snap={0.48}><div className="sheet-options">{['全部林区', '北岭林区', '河谷林区', '南山林区'].map(value => <button key={value} className={value === zone ? 'chosen' : ''} onClick={() => { setZone(value); setOpen(false); }}><strong>{value}</strong>{zone === value && <CheckCircledIcon />}</button>)}</div></BottomSheet></>;
}
function StateGate({ children, noun = '记录' }: { children: ReactNode; noun?: string }) {
  const { scenario, setScenario } = useApp(); const [loading, setLoading] = useState(false);
  useEffect(() => { if (!loading) return; const timer = setTimeout(() => { setScenario('normal'); setLoading(false); }, 750); return () => clearTimeout(timer); }, [loading, setScenario]);
  if (scenario === 'error') return <div className="empty-panel"><ExclamationTriangleIcon /><h3>{loading ? '正在重新连接' : '暂时无法更新数据'}</h3><p>当前列表不可用，请稍后重新加载。</p><button className="primary" disabled={loading} onClick={() => setLoading(true)}><ReloadIcon />{loading ? '重新连接中…' : '重新加载'}</button></div>;
  if (scenario === 'empty') return <Empty title={`暂无${noun}`} description="新的记录出现后，会在这里展示。" action={<button className="text-button" onClick={() => setScenario('normal')}>返回示例场景<ArrowRightIcon /></button>} />;
  return <>{scenario === 'stale' && <div className="data-alert"><ClockIcon /><span>示例数据已 8 分钟未更新，设备在线与执行状态待确认。</span></div>}{children}</>;
}
function Empty({ title, description, action }: { title: string; description: string; action?: ReactNode }) { return <div className="empty-panel"><LayersIcon /><h3>{title}</h3><p>{description}</p>{action}</div>; }
function Badge({ children, tone = '' }: { children: ReactNode; tone?: string }) { return <span className={`badge ${tone}`}>{children}</span>; }
const toneFor = (status: string) => status === '待复核' || status === '异常' || status === '高关注' ? 'danger' : status === '执行中' || status === '处置中' ? 'blue' : status === '待执行' || status === '待签阅' || status === '需关注' ? 'amber' : 'green';
function Section({ title, action, onClick }: { title: string; action?: string; onClick?: () => void }) { return <div className="section-heading"><h2>{title}</h2>{action && <button onClick={onClick}>{action}<ChevronRightIcon /></button>}</div>; }
function scoped<T extends { zone: string }>(items: T[], zone: string) { return zone === '全部林区' ? items : items.filter(item => item.zone === zone); }
function Home() {
  const app = useApp(); const flow = useFlow(); const keyboard = useKeyboard();
  const localTasks = scoped(tasks, app.zone).filter(task => task.time !== '昨日');
  const localEvents = scoped(events, app.zone).filter(event => event.status !== '已关闭');
  const attention = localEvents[0]; const running = localTasks.find(task => task.status === '执行中');
  const latest = scoped(reports, app.zone).find(report => report.status !== '生成中');
  const unavailable = app.scenario === 'empty' || app.scenario === 'error';
  return <>
    <div className="workspace-row"><ZonePicker /><span className="date-label">9 月 14 日 · 示例日</span></div>
    <section className="home-intro"><span className="eyebrow">今日巡护</span><h1>{unavailable ? '等待新的巡护动态' : app.scenario === 'stale' ? <>数据延迟，<br /><em>请留意</em>状态更新时间</> : attention ? <>巡护有序，<br /><em>{localEvents.length} 项动态</em>需要关注</> : '巡护动态，一目了然'}</h1><p>{app.scenario === 'normal' ? '先看风险，再看进度。' : '当前状态请结合数据提示查看。'}</p></section>
    <div className="metric-row"><button onClick={() => app.setTab('tasks')}><span>今日任务</span><strong>{unavailable ? '—' : localTasks.length}<small>项</small></strong></button><button onClick={() => app.setTab('situation')}><span>待关注事件</span><strong className="coral">{unavailable ? '—' : localEvents.length}<small>项</small></strong></button><button onClick={() => flow.push(detailScreen('devices', '设备概览', <Devices />))}><span>在线航空器</span><strong>{unavailable || app.scenario === 'stale' ? '—' : app.zone === '全部林区' ? 4 : app.zone === '北岭林区' ? 2 : 1}<small>架</small></strong></button></div>
    <StateGate noun="巡护动态">
      {attention && <button className="attention-card" onClick={() => flow.push(detailScreen(attention.id, '事件详情', <EventDetail event={attention} />))}><div className="row-between"><Badge tone="danger"><ExclamationTriangleIcon />优先关注</Badge><span>{attention.time}</span></div><h3>{attention.name}</h3><p>待人工复核 · 位置为估算</p><div className="attention-link">查看事件与关联任务<ArrowRightIcon /></div></button>}
      <Section title="巡护进行时" action="全部任务" onClick={() => app.setTab('tasks')} />
      {running ? <TaskCard task={running} /> : <div className="quiet-card"><CheckCircledIcon /><span>当前范围没有执行中的示例任务</span></div>}
      <Section title="快捷查看" />
      <div className="quick-grid"><button onClick={() => app.setTab('situation')}><TargetIcon /><span>火情态势</span><small>风险与处置动态</small></button><button onClick={() => app.setTab('reports')}><FileTextIcon /><span>巡检报告</span><small>成果与待签阅</small></button></div>
      <Section title="最新成果" action="报告中心" onClick={() => app.setTab('reports')} />
      {latest ? <ReportCard report={latest} /> : <div className="quiet-card"><ClockIcon />该区域暂无可查看的示例报告</div>}
      <p className="footnote">数据时点 09:48 · 当前仅为交互示例</p>
    </StateGate>
  </>;
}
function TaskCard({ task }: { task: Task }) {
  const flow = useFlow(); const keyboard = useKeyboard(); const { scenario } = useApp(); const delayed = scenario === 'stale' && task.status === '执行中';
  return <button className="task-card card" onClick={() => { keyboard.hide(); flow.push(detailScreen(task.id, '任务详情', <TaskDetail task={task} />)); }}><div className="row-between"><span className="overline">{task.zone} · {task.time}</span><Badge tone={delayed ? 'amber' : toneFor(task.status)}>{delayed ? '状态待更新' : task.status}</Badge></div><h3>{task.name}</h3><p className="card-subtitle">{task.model} · {task.pilot}</p>{task.progress > 0 && task.status !== '已完成' && <><div className="progress-meta"><span>航线进度</span><strong>{task.progress}%</strong></div><progress max="100" value={task.progress} aria-label={`任务进度 ${task.progress}%`} /></>}<div className="card-footer"><span><LayersIcon />{task.distance} km</span><span><ClockIcon />{task.duration} 分钟</span><ChevronRightIcon /></div></button>;
}
function Filters({ values, value, onChange }: { values: string[]; value: string; onChange: (value: string) => void }) { return <Carousel ariaLabel="筛选选项" className="filter-carousel" contentClassName="filter-track">{values.map(item => <button className={item === value ? 'filter active' : 'filter'} aria-pressed={item === value} key={item} onClick={() => onChange(item)}>{item}</button>)}</Carousel>; }
function TaskList() {
  const { zone } = useApp(); const [filter, setFilter] = useState('全部'); const [search, setSearch] = useState('');
  const filtered = scoped(tasks, zone).filter(task => (filter === '全部' || task.status === filter) && (task.name + task.id + task.model).toLowerCase().includes(search.trim().toLowerCase()));
  return <><div className="list-intro"><h1>每一次巡护<br /><em>进度都清楚</em></h1><ZonePicker /></div><div className="search-field"><MagnifyingGlassIcon /><KeyboardInput aria-label="搜索任务" placeholder="搜索任务名称、编号或机型" value={search} onChange={event => setSearch(event.target.value)} />{search && <button aria-label="清空搜索" onClick={() => setSearch('')}><Cross2Icon /></button>}</div><Filters values={['全部', '执行中', '待执行', '异常', '已完成']} value={filter} onChange={setFilter} /><StateGate noun="巡检任务"><div className="list-count">{filtered.length} 项任务 <span>含今日与近期任务</span></div><div className="card-list">{filtered.map(task => <TaskCard key={task.id} task={task} />)}</div>{!filtered.length && <Empty title="没有匹配的任务" description="试试其他关键词或筛选条件。" action={<button className="text-button" onClick={() => { setFilter('全部'); setSearch(''); }}>清除筛选<ReloadIcon /></button>} />}</StateGate></>;
}
function TaskDetail({ task }: { task: Task }) {
  const [section, setSection] = useState('概览'); const flow = useFlow(); const { scenario } = useApp();
  const stale = scenario === 'stale' && task.status !== '已完成';
  const event = events.find(item => item.id === task.eventId); const report = reports.find(item => item.id === task.reportId);
  const timeline = task.status === '已完成'
    ? [[task.time === '昨日' ? '昨日 15:30' : '08:00', '任务计划已发布', '已发布航线 V1.2'], [task.time === '昨日' ? '昨日 15:50' : '08:20', '任务开始执行', `${task.pilot}开始巡护`], [task.time === '昨日' ? '昨日 16:19' : '08:42', '任务执行完成', '成果已进入报告中心']]
    : task.status === '待执行'
      ? [['09:00', '任务计划已发布', '已发布航线 V1.2'], ['待确认', '等待现场飞手确认', '计划于 10:30 执行，尚无执行回执']]
      : [['08:50', '任务计划已发布', '已发布航线 V1.2'], [task.time, '任务开始执行', `${task.pilot}开始本次巡护`], [task.status === '异常' ? '09:22' : '09:46', task.status === '异常' ? '收到异常反馈' : '航线进度更新', task.status === '异常' ? '现场正在核查，后续状态待反馈' : `最近记录进度 ${task.progress}%`]];
  return <><div className="detail-heading"><Badge tone={stale ? 'amber' : toneFor(task.status)}>{stale ? '状态待更新' : task.status}</Badge><h1>{task.name}</h1><p>{task.id} · {task.zone}</p></div>{stale && <div className="quiet-card"><InfoCircledIcon />数据已 8 分钟未更新，以下为最近记录，当前执行状态待确认。</div>}<Filters values={['概览', '航线', '进度']} value={section} onChange={setSection} />
    {section === '概览' ? <><div className="card padded"><div className="section-heading"><h2>执行概况</h2><span className="accent">{task.progress}%</span></div><progress max="100" value={task.progress} aria-label="航线执行进度" /><dl className="info-grid"><Info label="执行航空器" value={task.model} /><Info label="巡护小组" value={task.pilot} /><Info label="计划里程" value={`${task.distance} km`} /><Info label="预计用时" value={`${task.duration} 分钟`} /><Info label="计划时间" value={task.time} /><Info label="航线版本" value="V1.2 · 示例" /></dl></div>
      <Section title="关联信息" />{event ? <button className="menu-row" onClick={() => flow.push(detailScreen(event.id, '事件详情', <EventDetail event={event} />))}><span className="menu-icon danger"><ExclamationTriangleIcon /></span><span><strong>{event.name}</strong><small>{event.status} · {event.time}</small></span><ChevronRightIcon /></button> : <div className="quiet-card"><CheckCircledIcon />没有关联的待复核示例事件</div>}{report && <button className="menu-row" onClick={() => flow.push(detailScreen(report.id, '巡检报告', <ReportDetail report={report} />))}><span className="menu-icon"><FileTextIcon /></span><span><strong>查看本次巡检报告</strong><small>{report.status} · V1.0</small></span><ChevronRightIcon /></button>}<div className="read-only-note"><LockClosedIcon />移动端仅查看任务，飞行由现场飞手执行。</div></>
      : section === '航线' ? <><div className="card padded"><span className="eyebrow">已发布航线</span><h2>{task.zone}常规巡护线</h2><p className="subtext">航点顺序预览 · 共 12 个航点 · 示例数据</p><div className="route-stops">{['起降点 · 巡护工作站', '01—04 · 沿防火带巡查', '05—08 · 重点坡面复核', '09—12 · 林缘检查', '结束点 · 返回工作站'].map((stop, index) => <div key={stop}><span>{index + 1}</span><p>{stop}</p></div>)}</div><dl className="info-grid"><Info label="巡航高度" value="80 m" /><Info label="默认俯仰" value="−45°" /></dl></div><p className="footnote">本页展示航点顺序，真实地理地图待接入。</p></>
      : <div className="card padded"><h2>任务时间线</h2><Timeline entries={timeline} /></div>}
  </>;
}
function Info({ label, value }: { label: string; value: string }) { return <div><dt>{label}</dt><dd>{value}</dd></div>; }
function Timeline({ entries }: { entries: string[][] }) { return <div className="timeline">{entries.map(([time, title, note], index) => <div className="timeline-item" key={`${time}-${title}`}><div className="timeline-marker"><span className={index === entries.length - 1 ? 'current' : ''} /></div><div><small>{time}</small><h3>{title}</h3><p>{note}</p></div></div>)}</div>; }
function Situation() {
  const { zone } = useApp(); const [filter, setFilter] = useState('全部');
  const items = scoped(events, zone).filter(event => filter === '全部' || event.status === filter);
  return <><div className="list-intro"><h1>重点风险<br /><em>及时掌握</em></h1><ZonePicker /></div><div className="situation-summary"><span className="menu-icon danger"><TargetIcon /></span><div><strong>从发现到复核</strong><p>查看风险等级、位置精度与处置进展</p></div></div><Filters values={['全部', '待复核', '处置中', '已关闭']} value={filter} onChange={setFilter} /><StateGate noun="火情事件"><div className="list-count">{items.length} 项事件<span>优先展示待复核事件</span></div><div className="card-list">{items.map(event => <EventCard key={event.id} event={event} />)}</div>{!items.length && <Empty title="当前范围没有此类事件" description="切换筛选或查看全部林区。" />}</StateGate></>;
}
function EventCard({ event }: { event: Event }) {
  const flow = useFlow(); return <button className="card event-card" onClick={() => flow.push(detailScreen(event.id, '事件详情', <EventDetail event={event} />))}><div className="row-between"><Badge tone={toneFor(event.level)}>{event.level}</Badge><span className="overline">{event.time}</span></div><h3>{event.name}</h3><p>{event.zone} · {event.id}</p><div className="card-footer"><span className={event.status === '待复核' ? 'coral' : ''}><ActivityLogIcon />{event.status}</span><span>{event.status === '已关闭' ? '已核查' : '位置估算'}</span><ChevronRightIcon /></div></button>;
}
function EventDetail({ event }: { event: Event }) {
  const keyboard = useKeyboard();
  const flow = useFlow(); const [more, setMore] = useState(false); const task = tasks.find(item => item.id === event.taskId)!;
  return <><div className="detail-heading"><div className="inline-badges"><Badge tone={toneFor(event.level)}>{event.level}</Badge><Badge tone={toneFor(event.status)}>{event.status}</Badge></div><h1>{event.name}</h1><p>{event.id} · {event.time} 发现</p></div><div className="card padded"><h2>现场情况</h2><p className="body-copy">{event.description}</p><div className="evidence-state"><VideoIcon /><strong>现场影像待接入</strong><span>真实照片与视频将在联调后展示</span></div><button className="text-button" onClick={() => setMore(true)}>位置精度说明<InfoCircledIcon /></button></div><Section title="关联巡检" /><TaskCard task={task} /><Section title="处置记录" /><div className="card padded"><Timeline entries={event.status === '已关闭' ? [['昨日 15:20', '识别到异常热源', '系统记录待复核事件'], ['昨日 15:35', '现场复核完成', '正常作业热源，记录已关闭']] : [[event.time, '发现疑似异常', '巡检航空器上报候选事件'], [event.time, '通知值班工作台', '等待人工研判'], ['待反馈', event.status === '处置中' ? '地面核查中' : '待人工复核', '以现场反馈为准']]}/></div><button className="secondary full-width" onClick={() => { keyboard.hide(); flow.push(detailScreen('notifications', '消息中心', <Notifications />)); }}><BellIcon />查看相关动态</button>
    <BottomSheet open={more} onOpenChange={setMore} title="位置精度" description="估算位置用于研判与复核。" snap={0.47}><div className="sheet-copy"><Badge tone="amber">ESTIMATED · 估算</Badge><p>小程序应同时展示坐标来源、更新时间与精度等级。当前预览不提供真实坐标，也不将估算位置作为投放依据。</p><button className="primary full-width" onClick={() => setMore(false)}>我知道了</button></div></BottomSheet></>;
}
function ReportList() {
  const { zone, saved } = useApp(); const [filter, setFilter] = useState('全部');
  const items = scoped(reports, zone).filter(report => filter === '全部' || (filter === '已收藏' ? saved.includes(report.id) : report.status === filter)).sort((left, right) => right.date.localeCompare(left.date));
  return <><div className="list-intro"><h1>巡护成果<br /><em>随时可查</em></h1><ZonePicker /></div><Filters values={['全部', '待签阅', '生成中', '已归档', '已收藏']} value={filter} onChange={setFilter} /><StateGate noun="巡检报告"><div className="list-count">{items.length} 份报告<span>最近生成优先</span></div><div className="card-list">{items.map(report => <ReportCard key={report.id} report={report} />)}</div>{!items.length && <Empty title={filter === '已收藏' ? '还没有收藏报告' : '暂无匹配的报告'} description={filter === '已收藏' ? '打开报告后，可以收藏常用成果。' : '试试其他筛选或查看范围。'} />}</StateGate></>;
}
function ReportCard({ report }: { report: Report }) {
  const flow = useFlow(); return <button className="card report-card" onClick={() => flow.push(detailScreen(report.id, '巡检报告', <ReportDetail report={report} />))}><span className="report-symbol"><FileTextIcon /></span><div className="report-info"><div className="row-between"><span className="overline">巡检报告 · V1.0</span><Badge tone={report.status === '生成中' ? 'blue' : toneFor(report.status)}>{report.status}</Badge></div><h3>{report.name}</h3><p>{report.date} · {report.zone}</p></div><ChevronRightIcon className="report-arrow" /></button>;
}
function ReportDetail({ report }: { report: Report }) {
  const keyboard = useKeyboard();
  const { saved, setSaved, notify } = useApp(); const flow = useFlow(); const [sheet, setSheet] = useState(false);
  const task = tasks.find(item => item.id === report.taskId)!; const bookmarked = saved.includes(report.id);
  if (report.status === '生成中') return <><div className="detail-heading"><Badge tone="blue">生成中</Badge><h1>{report.name}</h1><p>当前预览展示报告生成过程</p></div><div className="card padded"><Timeline entries={[[report.date, '任务资料汇集', '任务轨迹与巡护记录已汇集'], ['处理中', '整理巡护结论', '汇总异常事项与数据完整性'], ['待完成', '生成结构化报告', '完成后可在报告中心查看']]} /></div><button className="secondary full-width" onClick={() => { keyboard.hide(); flow.push(detailScreen(task.id, '任务详情', <TaskDetail task={task} />)); }}>查看关联任务<ArrowRightIcon /></button></>;
  return <><div className="report-cover"><div className="report-cover-top"><FileTextIcon /><span>巡护成果 / INSPECTION</span></div><Badge tone={toneFor(report.status)}>{report.status}</Badge><h1>{report.name}</h1><p>{report.id} · V1.0</p><div className="report-cover-bottom"><span>{report.date}</span><span>{report.zone}</span></div></div><div className="report-actions"><button className={bookmarked ? 'selected' : ''} onClick={() => { setSaved(bookmarked ? saved.filter(id => id !== report.id) : [...saved, report.id]); notify(bookmarked ? '已取消预览收藏' : '已加入预览收藏'); }}><BookmarkIcon />{bookmarked ? '已收藏' : '收藏报告'}</button><button onClick={() => setSheet(true)}><InfoCircledIcon />报告说明</button></div><Section title="巡护结论" /><div className="card padded"><div className="report-conclusion"><CheckCircledIcon /><h3>{report.findings ? '发现的问题已完成现场核查' : '本次巡护未发现异常事项'}</h3></div><p className="body-copy">按计划完成重点区域巡查，航线执行记录完整。{report.findings ? '异常热源经复核为正常作业，相关记录已留存。' : '重点防火带巡查记录已归集。'}</p><dl className="info-grid report-metrics"><Info label="巡护里程" value={`${report.distance} km`} /><Info label="飞行时长" value={`${report.duration} 分钟`} /><Info label="发现事项" value={`${report.findings} 项`} /><Info label="报告版本" value="V1.0" /></dl></div><Section title="成果目录" /><div className="card padded"><ul className="result-list"><li><CheckCircledIcon />任务概况与执行记录</li><li><CheckCircledIcon />重点区域巡查结论</li><li><ClockIcon />媒体附件待真实数据接入</li></ul></div><button className="secondary full-width" onClick={() => { keyboard.hide(); flow.push(detailScreen(task.id, '任务详情', <TaskDetail task={task} />)); }}>查看关联任务<ArrowRightIcon /></button><p className="footnote">这是结构化报告预览，不产生真实签阅或归档记录。</p>
    <BottomSheet open={sheet} onOpenChange={setSheet} title="报告说明" description="用于确认移动端阅读体验。" snap={0.46}><div className="sheet-copy"><p>预览支持报告列表、详情、状态与收藏。真实 PDF 下载、签阅和分享会在报告服务接入后开放。</p><button className="primary full-width" onClick={() => setSheet(false)}>继续查看报告</button></div></BottomSheet></>;
}
function Mine() {
  const flow = useFlow(); const { saved, zone } = useApp();
  return <><div className="profile-card"><div className="avatar"><PersonIcon /></div><h1>巡护值班领导</h1><p>预览身份 · 只读查看</p><Badge tone="blue">示例指挥中心</Badge></div><div className="profile-stats"><div><strong>{saved.length}</strong><span>预览收藏</span></div><div><strong>5</strong><span>工作入口</span></div><div><LockClosedIcon /><span>只读模式</span></div></div><Section title="账号与工作空间" /><div className="menu-group"><button className="menu-row" onClick={() => flow.push(detailScreen('account', '账号与绑定', <Account />))}><span className="menu-icon"><PersonIcon /></span><span><strong>身份与账号绑定</strong><small>查看登录与绑定流程</small></span><ChevronRightIcon /></button><button className="menu-row" onClick={() => flow.push(detailScreen('workspace', '查看范围', <Workspace />))}><span className="menu-icon"><LayersIcon /></span><span><strong>查看范围</strong><small>{zone}</small></span><ChevronRightIcon /></button></div><Section title="偏好与帮助" /><div className="menu-group"><button className="menu-row" onClick={() => flow.push(detailScreen('preferences', '通知偏好', <Preferences />))}><span className="menu-icon"><BellIcon /></span><span><strong>通知偏好</strong><small>紧急事件、任务动态、报告完成</small></span><ChevronRightIcon /></button><button className="menu-row" onClick={() => flow.push(detailScreen('about', '关于小程序', <About />))}><span className="menu-icon"><InfoCircledIcon /></span><span><strong>关于小程序</strong><small>版本与预览说明</small></span><ChevronRightIcon /></button></div><p className="footnote">UAVFIRE · 移动驾驶舱<br />交互预览 v0.2 · 数据与操作均为本地示例</p></>;
}
function Notifications() {
  const flow = useFlow(); const [read, setRead] = useState(false); const { notify } = useApp();
  return <><div className="section-heading"><h2>最近动态</h2><button onClick={() => { setRead(true); notify('已在预览中标为已读'); }}>{read ? '全部已读' : '全部已读标记'}</button></div><div className="card-list"><button className="menu-row notification-row" onClick={() => flow.push(detailScreen(events[0].id, '事件详情', <EventDetail event={events[0]} />))}><span className="menu-icon danger"><ExclamationTriangleIcon /></span><span><strong>有一项事件等待复核</strong><small>北岭东段发现疑似烟点 · 09:46</small></span>{!read && <i className="unread-dot" />}</button><button className="menu-row notification-row" onClick={() => flow.push(detailScreen(reports[0].id, '巡检报告', <ReportDetail report={reports[0]} />))}><span className="menu-icon"><FileTextIcon /></span><span><strong>晨间巡查报告已生成</strong><small>南山防火带 · 08:52</small></span>{!read && <i className="unread-dot" />}</button></div><p className="footnote">当前为站内消息交互预览，未发送微信通知。</p></>;
}
function Preferences() {
  const { preferences, setPreferences } = useApp();
  return <><div className="detail-heading"><h1>只关注<br />需要你的消息</h1><p>调整预览偏好，不申请微信通知授权。</p></div><div className="menu-group">{Object.entries(preferences).map(([name, checked]) => <div className="menu-row" key={name}><span><strong>{name}</strong><small>{{ 紧急事件: '高等级风险与待复核事件', 任务动态: '任务开始、异常与完成', 报告完成: '新报告生成与待签阅提醒' }[name]}</small></span><button className="toggle" role="switch" aria-checked={checked} aria-label={name} onClick={() => setPreferences({ ...preferences, [name]: !checked })}><span /></button></div>)}</div><div className="read-only-note"><InfoCircledIcon />偏好仅保留在当前预览会话中。</div></>;
}
function Account() {
  const [status, setStatus] = useState('未登录'); const [name, setName] = useState(''); const [org, setOrg] = useState(''); const [validation, setValidation] = useState(''); const [privacy, setPrivacy] = useState(false); const keyboard = useKeyboard();
  const states = ['未登录', '待绑定', '待审核', '已绑定', '已停用'];
  return <><div className="detail-heading"><Badge tone="blue">登录流程预览</Badge><h1>{status === '未登录' ? '进入你的巡护工作台' : status === '待绑定' ? '绑定工作身份' : status === '待审核' ? '申请已提交' : status === '已停用' ? '账号暂不可用' : '工作身份已绑定'}</h1><p>不调用真实微信登录，不提交任何账号申请。</p></div>
    <Filters values={states} value={status} onChange={value => { keyboard.hide(); setStatus(value); setValidation(''); }} />
    {status === '未登录' ? <div className="card padded"><PersonIcon className="large-icon" /><h2>微信快捷进入</h2><p className="body-copy">登录后可查看授权范围内的任务、事件与报告。</p><button className="primary full-width" onClick={() => setStatus('待绑定')}>体验登录流程<ArrowRightIcon /></button><button className="text-button" onClick={() => setPrivacy(true)}>个人信息与账号说明<InfoCircledIcon /></button></div>
      : status === '待绑定' ? <form className="card padded binding-form" onSubmit={event => { event.preventDefault(); if (!name.trim() || !org.trim()) { setValidation('请填写姓名和所属单位后继续预览。'); return; } keyboard.hide(); setValidation(''); setStatus('待审核'); }}><label>姓名<KeyboardInput value={name} onChange={event => setName(event.target.value)} placeholder="填写示例姓名" maxLength={20} /></label><label>所属单位<KeyboardInput value={org} onChange={event => setOrg(event.target.value)} placeholder="填写示例单位" maxLength={40} /></label><p className="subtext">请使用虚构信息体验。正式绑定需管理员审核与权限配置。</p>{validation && <p className="form-error" role="alert">{validation}</p>}<button className="primary full-width" type="submit">预览提交申请</button></form>
      : status === '待审核' ? <div className="empty-panel"><ClockIcon /><h3>等待管理员审核</h3><p>{name || '示例用户'} · {org || '示例指挥中心'}<br />正式审核结果将在账号页面显示。</p><button className="secondary" onClick={() => setStatus('已绑定')}>查看审核通过后的界面</button></div>
      : status === '已停用' ? <div className="empty-panel"><LockClosedIcon /><h3>请联系本单位管理员</h3><p>账号停用期间无法访问组织业务数据。</p><button className="secondary" onClick={() => setStatus('未登录')}>返回登录预览</button></div>
      : <div className="card padded"><CheckCircledIcon className="large-icon green-text" /><h2>{name || '巡护值班领导'}</h2><dl className="info-grid"><Info label="所属单位" value={org || '示例指挥中心'} /><Info label="角色" value="领导 · 只读" /><Info label="工作范围" value="已授权林区" /><Info label="账号状态" value="正常" /></dl><button className="secondary full-width" onClick={() => setStatus('未登录')}>退出预览身份</button></div>}
    <BottomSheet open={privacy} onOpenChange={setPrivacy} title="账号与个人信息" description="当前为交互设计预览。" snap={0.48}><div className="sheet-copy"><p>预览表单不会上传，也不会持久保存。正式版本应提供单位主体信息、隐私政策及个人信息处理说明后再进行真实登录。</p><button className="primary full-width" onClick={() => setPrivacy(false)}>关闭说明</button></div></BottomSheet></>;
}
function Workspace() { const { zone } = useApp(); return <><div className="detail-heading"><h1>选择关注区域</h1><p>切换后，首页与列表将展示对应的示例记录。</p></div><ZonePicker /><div className="card padded space-top"><h2>{zone}</h2><p className="body-copy">实际可访问范围由服务端根据账号权限决定。这里的区域切换用于体验列表联动。</p></div></>; }
function Devices() { const { scenario, zone } = useApp(); const devices = [{ name: '北岭巡护 01', zone: '北岭林区', model: 'Matrice 4T', power: '78%' }, { name: '北岭巡护 02', zone: '北岭林区', model: 'Matrice 4T', power: '92%' }, { name: '河谷巡护 01', zone: '河谷林区', model: 'Matrice 350 RTK', power: '86%' }, { name: '南山巡护 01', zone: '南山林区', model: 'Matrice 4T', power: '81%' }]; return <><div className="detail-heading"><h1>设备概览</h1><p>示例设备 · 不代表现场在线状态或兼容验收</p></div><StateGate noun="设备"><div className="card-list">{scoped(devices, zone).map(device => <div className="card padded" key={device.name}><div className="row-between"><h3>{device.name}</h3><Badge tone={scenario === 'stale' ? 'amber' : 'green'}>{scenario === 'stale' ? '状态待确认' : '示例在线'}</Badge></div><p className="subtext">{device.model} · {device.zone}</p><div className="card-footer"><span><LightningBoltIcon />电量 {scenario === 'stale' ? '—' : device.power}</span><span>仅查看</span></div></div>)}</div></StateGate></>; }
function About() { return <><div className="profile-card"><div className="avatar"><DashboardIcon /></div><h1>无人机火情智巡</h1><p>领导移动驾驶舱 · 交互预览 v0.2</p></div><div className="card padded"><h2>随时掌握巡护动态</h2><p className="body-copy">围绕今日态势、巡检任务、火情事件与巡护成果，让重要信息在手机上更容易查看。</p><dl className="info-grid"><Info label="当前形态" value="浏览器交互预览" /><Info label="数据来源" value="虚构示例数据" /><Info label="飞行控制" value="不提供" /><Info label="真实微信登录" value="待后续接入" /></dl></div><div className="read-only-note"><InfoCircledIcon />本预览用于页面与流程评审，不代表微信真机验收。</div></>; }
