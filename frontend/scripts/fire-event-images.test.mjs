import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const fireEventListPath = new URL('../src/pages/page-web/projects/fire/FireEventList.vue', import.meta.url)
const source = readFileSync(fireEventListPath, 'utf8')

test('fire event list renders visible and thermal recognition images separately', () => {
  assert.match(source, /record\.visibleImageUrl/)
  assert.match(source, /record\.thermalImageUrl/)
  assert.match(source, /可见光/)
  assert.match(source, /红外/)
  assert.match(source, /openPreview\(record\.visibleImageUrl\)/)
  assert.match(source, /openPreview\(record\.thermalImageUrl\)/)
  assert.doesNotMatch(source, /:src="record\.thermalImageUrl \|\| record\.visibleImageUrl"/)
  assert.doesNotMatch(source, /openPreview\(record\.thermalImageUrl \|\| record\.visibleImageUrl\)/)
})

test('fire event list formats thermal temperature to one decimal place', () => {
  assert.match(source, /formatThermalTemperature/)
  assert.match(source, /formatThermalTemperature\(record\.thermalTemperature\)/)
  assert.match(source, /Number\(value\)/)
  assert.match(source, /\.toFixed\(1\)/)
})

test('fire event list relies on annotated infrared images for temperature placement', () => {
  assert.match(source, /thermal-image-wrap/)
  assert.doesNotMatch(source, /thermal-temp-badge/)
  assert.doesNotMatch(source, /preview-temp-badge/)
  assert.doesNotMatch(source, /previewThermalTemperature/)
  assert.doesNotMatch(source, /openPreview\(record\.thermalImageUrl, record\.thermalTemperature\)/)
  assert.doesNotMatch(source, /openPreview\(record\.thermalImageUrl!, record\.thermalTemperature\)/)
})

test('fire event preview preserves cache-busting query while switching raw and annotated images', () => {
  assert.match(source, /function recognitionImageUrlForMode/)
  assert.match(source, /const hashIndex = url\.indexOf\('#'\)/)
  assert.match(source, /const queryIndex = withoutHash\.indexOf\('\?'\)/)
  assert.ok(source.includes("path.replace(/-raw\\.jpg$/, '-annotated.jpg')"))
  assert.ok(source.includes("annotatedPath.replace(/-annotated\\.jpg$/, '-raw.jpg')"))
  assert.ok(source.includes('return nextPath + query + hash'))
})

test('fire event list wraps long device serial numbers inside their column', () => {
  assert.match(source, /#deviceSnCell/)
  assert.match(source, /class="table-text-wrap"/)
  assert.match(source, /word-break: break-all/)
  assert.match(source, /overflow-wrap: anywhere/)
  assert.match(source, /slots: \{ customRender: 'deviceSnCell' \}/)
})

test('fire event list localizes event status and explains partial history snapshots', () => {
  assert.match(source, /displayFireLevel/)
  assert.match(source, /HIGH: '高'/)
  assert.match(source, /MEDIUM: '中'/)
  assert.match(source, /LOW: '低'/)
  assert.match(source, /displayEventStatus/)
  assert.match(source, /LOW_CONFIDENCE: '低置信度'/)
  assert.match(source, /MISSION_CREATED: '已创建任务'/)
  assert.match(source, /displayHistoryAction/)
  assert.match(source, /CREATED: '新建事件'/)
  assert.match(source, /MERGED: '合并命中'/)
  assert.ok(source.includes('已记录 ${historyEvents.value.length} 条 / 识别 ${total} 次'))
  assert.match(source, /暂无历史记录/)
})
