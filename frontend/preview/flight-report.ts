import { createApp, h } from 'vue'
import Antd from 'ant-design-vue'
import 'ant-design-vue/dist/antd.css'
import Report from '../src/components/wayline-planner/FlightReportPanel.vue'
import request from '../src/api/http/request'
import fixtures from './flight-report-fixture.json'
const scenario = new URLSearchParams(location.search).get('scenario') || 'normal'
request.defaults.adapter = async config => ({ data: { code: 0, data: (fixtures as any)[scenario][Number(config.params?.page || 1) - 1] }, status: 200, statusText: 'OK', headers: {}, config })
createApp({ render: () => h(Report, { workspaceId: 'preview', taskId: 'task-0' }) }).use(Antd).mount('#app')
