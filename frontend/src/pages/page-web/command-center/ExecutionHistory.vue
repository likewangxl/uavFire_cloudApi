<template><section class="cc-page cc-history"><div class="cc-heading"><div><span class="cc-eyebrow">任务中心 / 执行记录</span><h1>执行与历史</h1><p>查看航线最近一次执行及巡检计划记录；再次执行生成新的执行编号。</p></div><router-link class="cc-button primary" to="/wayline">选择航线 / 再次执行</router-link></div><div class="cc-toolbar"><div class="cc-filters"><button :class="{active:kind==='planned'}" @click="switchKind('planned')">航线最近执行</button><button :class="{active:kind==='jobs'}" @click="switchKind('jobs')">巡检计划记录</button></div><button class="cc-button" :disabled="loading" @click="load">刷新记录</button></div><p class="cc-muted">{{ kind==='planned'?'按航线列出当前状态和最近执行编号，草稿没有执行编号。更早的逐次执行日志仍需通过原任务记录查询。':'按巡检计划执行实例分页查询。' }}</p><div v-if="error" class="cc-error" role="alert">{{ error }}<button class="cc-text-button" @click="load">重试</button></div><a-table :columns="columns" :data-source="records" row-key="id" :loading="loading" :scroll="{x:1000}" :pagination="{current:page,pageSize:20,total,showSizeChanger:false}" :locale="{emptyText:'暂无记录'}" @change="changePage"><template #status="{record}"><span class="cc-status">{{ record.statusLabel }}</span></template><template #time="{record}">{{ formatTime(record.time) }}</template><template #action><router-link :to="kind==='planned'?'/wayline':'/task'">{{ kind==='planned'?'航线管理':'任务管理' }}</router-link></template></a-table></section></template>
<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { getWaylineJobs, getPlannedWaylines } from '/@/api/wayline'
import { TaskStatusMap } from '/@/types/task'
import { ELocalStorageKey } from '/@/types'
import { formatPlannedWaylineStatus } from '/@/components/wayline-planner/wayline-format'
import { formatTime, createLatestRequest } from '/@/components/command-center/event-model.mjs'
const records = ref<any[]>([]); const page = ref(1); const total = ref(0); const loading = ref(false); const error = ref(''); const latest = createLatestRequest(); const kind = ref('planned')
const columns = [{ title: '航线 / 任务名称', dataIndex: 'name', width: 210 }, { title: '执行编号', dataIndex: 'executionId', width: 220 }, { title: '执行设备', dataIndex: 'device', width: 160 }, { title: '状态', key: 'status', slots: { customRender: 'status' } }, { title: '执行 / 更新时间', key: 'time', slots: { customRender: 'time' } }, { title: '结果 / 说明', dataIndex: 'reason' }, { title: '操作', key: 'action', slots: { customRender: 'action' } }]
async function load () {
  loading.value = true; const source = kind.value
  await latest.run(async () => { const r = await (source === 'planned' ? getPlannedWaylines : getWaylineJobs)(localStorage.getItem(ELocalStorageKey.WorkspaceId) || '', { page: page.value, page_size: 20, total: 0 }); if (r.code !== 0) throw new Error(); return r.data }, data => {
    records.value = (data?.list || []).map((row:any) => source === 'planned' ? { id: row.plannedWaylineId, name: row.name, executionId: row.flightId || '尚未执行', device: row.droneSn || row.dockSn || '未分配', statusLabel: formatPlannedWaylineStatus(row), time: row.taskStatusUpdatedAt || row.updateTime, reason: row.taskStatusReason || '—' } : { id: row.job_id, name: row.job_name, executionId: row.job_id, device: row.dock_name || '未记录', statusLabel: TaskStatusMap[row.status] || '未知状态', time: row.execute_time || row.begin_time, reason: row.code ? '错误码 ' + row.code : '—' })
    total.value = data?.pagination?.total || 0; error.value = ''; loading.value = false
  }, () => { error.value = '执行记录读取失败，请重试'; loading.value = false })
}
function switchKind (value:string) { kind.value = value; page.value = 1; records.value = []; total.value = 0; load() }
function changePage (p:any) { page.value = p.current; load() }
onMounted(load); onBeforeUnmount(() => latest.invalidate())
</script>
