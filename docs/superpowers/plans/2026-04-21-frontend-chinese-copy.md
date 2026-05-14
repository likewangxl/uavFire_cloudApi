# Frontend Chinese Copy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将前端所有用户可见英文、前端拼接的接口报错和关键调试日志替换为中文，同时不影响现有接口契约和业务逻辑。

**Architecture:** 保持现有组件结构与页面结构不变，直接在展示层做字符串中文化。先做扫描和测试基线，再按页面与组件分批替换，最后通过复扫和构建确认无遗漏与无编译回归。

**Tech Stack:** Vue 3, Vite, TypeScript, Ant Design Vue, Node test runner

---

### Task 1: 建立中文化扫描与回归基线

**Files:**
- Modify: `frontend/scripts/leadership-cockpit-livestream.test.mjs`
- Create: `frontend/scripts/frontend-chinese-copy.test.mjs`

- [ ] **Step 1: 写失败测试**

```js
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const targets = [
  new URL('../src/pages/page-web/index.vue', import.meta.url),
  new URL('../src/pages/page-web/home.vue', import.meta.url),
  new URL('../src/pages/page-web/projects/livestream.vue', import.meta.url),
  new URL('../src/components/WorkspaceLivestreamPanel.vue', import.meta.url)
]

const sources = targets.map(path => readFileSync(path, 'utf8')).join('\n')

test('core web pages no longer expose common English UI copy', () => {
  assert.doesNotMatch(sources, /Login|Select Drone|Select Camera|Quality|Play|Stop|Refresh Capacity|Waiting for stream/)
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `node --test frontend/scripts/frontend-chinese-copy.test.mjs`
Expected: FAIL，命中现有英文文案

- [ ] **Step 3: 补充最小实现文件**

```js
// 仅新增扫描测试文件，此步不改生产代码
```

- [ ] **Step 4: 记录测试入口**

Run: `ls frontend/scripts`
Expected: 能看到 `frontend-chinese-copy.test.mjs`

- [ ] **Step 5: Commit**

```bash
git add frontend/scripts/frontend-chinese-copy.test.mjs
git commit -m "test: add frontend chinese copy regression baseline"
```

### Task 2: 中文化 Web 端框架与高频页面

**Files:**
- Modify: `frontend/src/pages/page-web/index.vue`
- Modify: `frontend/src/pages/page-web/home.vue`
- Modify: `frontend/src/pages/page-web/projects/leadership-cockpit.vue`
- Modify: `frontend/src/pages/page-web/projects/livestream.vue`
- Modify: `frontend/src/pages/page-web/projects/workspace.vue`
- Modify: `frontend/src/components/common/topbar.vue`
- Modify: `frontend/src/components/common/sidebar.vue`
- Modify: `frontend/src/components/WorkspaceLivestreamPanel.vue`

- [ ] **Step 1: 写失败测试**

```js
test('web shell and livestream surfaces use Chinese UI copy', () => {
  assert.doesNotMatch(sources, /Emergency Leadership Cockpit|Livestream|Select Drone|Select Camera|Quality|Play|Stop|Apply Quality|Refresh Capacity/)
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `node --test frontend/scripts/frontend-chinese-copy.test.mjs`
Expected: FAIL，命中工作台/直播/驾驶舱相关英文

- [ ] **Step 3: 写最小实现**

```vue
<!-- 示例：WorkspaceLivestreamPanel.vue -->
<div v-if="props.showHeader" class="header">直播画面</div>
```

```ts
const clarityList = [
  { value: 0, label: '自适应' },
  { value: 1, label: '流畅' },
  { value: 2, label: '标准' },
  { value: 3, label: '高清' },
  { value: 4, label: '超清' }
]
```

- [ ] **Step 4: 运行测试确认通过**

Run: `node --test frontend/scripts/frontend-chinese-copy.test.mjs`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/page-web/index.vue frontend/src/pages/page-web/home.vue frontend/src/pages/page-web/projects/leadership-cockpit.vue frontend/src/pages/page-web/projects/livestream.vue frontend/src/pages/page-web/projects/workspace.vue frontend/src/components/common/topbar.vue frontend/src/components/common/sidebar.vue frontend/src/components/WorkspaceLivestreamPanel.vue frontend/scripts/frontend-chinese-copy.test.mjs
git commit -m "feat: translate core web pages to Chinese"
```

### Task 3: 中文化业务页面与通用组件

**Files:**
- Modify: `frontend/src/pages/page-web/projects/devices.vue`
- Modify: `frontend/src/pages/page-web/projects/dock.vue`
- Modify: `frontend/src/pages/page-web/projects/wayline.vue`
- Modify: `frontend/src/pages/page-web/projects/task.vue`
- Modify: `frontend/src/pages/page-web/projects/layer.vue`
- Modify: `frontend/src/pages/page-web/projects/media.vue`
- Modify: `frontend/src/pages/page-web/projects/members.vue`
- Modify: `frontend/src/pages/page-web/projects/Firmwares.vue`
- Modify: `frontend/src/components/**/*.vue` 中含明显界面英文的文件

- [ ] **Step 1: 写失败测试**

```js
test('admin pages no longer expose common English table and action labels', () => {
  const adminSources = readFileSync(new URL('../src/pages/page-web/projects/devices.vue', import.meta.url), 'utf8')
    + readFileSync(new URL('../src/pages/page-web/projects/members.vue', import.meta.url), 'utf8')
  assert.doesNotMatch(adminSources, /Account|Joined|Last Online|Release Date/)
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `node --test frontend/scripts/frontend-chinese-copy.test.mjs`
Expected: FAIL，命中后台业务页英文列名或按钮

- [ ] **Step 3: 写最小实现**

```ts
const columns = [
  { title: '账号', dataIndex: 'username' },
  { title: '加入时间', dataIndex: 'create_time' }
]
```

- [ ] **Step 4: 运行测试确认通过**

Run: `node --test frontend/scripts/frontend-chinese-copy.test.mjs`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/page-web/projects/devices.vue frontend/src/pages/page-web/projects/dock.vue frontend/src/pages/page-web/projects/wayline.vue frontend/src/pages/page-web/projects/task.vue frontend/src/pages/page-web/projects/layer.vue frontend/src/pages/page-web/projects/media.vue frontend/src/pages/page-web/projects/members.vue frontend/src/pages/page-web/projects/Firmwares.vue frontend/src/components
git commit -m "feat: translate admin pages and shared components to Chinese"
```

### Task 4: 中文化 Pilot 端与前端错误提示/日志

**Files:**
- Modify: `frontend/src/pages/page-pilot/pilot-index.vue`
- Modify: `frontend/src/pages/page-pilot/pilot-home.vue`
- Modify: `frontend/src/pages/page-pilot/pilot-media.vue`
- Modify: `frontend/src/pages/page-pilot/pilot-liveshare.vue`
- Modify: `frontend/src/api/manage.ts`
- Modify: `frontend/src/api/pilot-bridge.ts`
- Modify: `frontend/src/components/livestream-agora.vue`
- Modify: `frontend/src/components/livestream-others.vue`

- [ ] **Step 1: 写失败测试**

```js
test('pilot pages and api-side user-facing errors are Chinese', () => {
  const pilotSources = readFileSync(new URL('../src/pages/page-pilot/pilot-index.vue', import.meta.url), 'utf8')
    + readFileSync(new URL('../src/api/pilot-bridge.ts', import.meta.url), 'utf8')
  assert.doesNotMatch(pilotSources, /Login|Pilot platform stopped|Failed|Success|Refresh/)
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `node --test frontend/scripts/frontend-chinese-copy.test.mjs`
Expected: FAIL，命中 Pilot 页与接口提示英文

- [ ] **Step 3: 写最小实现**

```ts
console.info('Pilot 平台已停止。')
message.error('请求失败，请稍后重试')
```

- [ ] **Step 4: 运行测试确认通过**

Run: `node --test frontend/scripts/frontend-chinese-copy.test.mjs`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/page-pilot/pilot-index.vue frontend/src/pages/page-pilot/pilot-home.vue frontend/src/pages/page-pilot/pilot-media.vue frontend/src/pages/page-pilot/pilot-liveshare.vue frontend/src/api/manage.ts frontend/src/api/pilot-bridge.ts frontend/src/components/livestream-agora.vue frontend/src/components/livestream-others.vue
git commit -m "feat: translate pilot pages and user-facing errors to Chinese"
```

### Task 5: 全量复扫与构建验证

**Files:**
- Modify: `frontend/scripts/frontend-chinese-copy.test.mjs`

- [ ] **Step 1: 扩展复扫断言**

```js
test('remaining frontend source no longer contains high-confidence English UI phrases', () => {
  const source = readFileSync(new URL('../src/components/livestream-agora.vue', import.meta.url), 'utf8')
  assert.doesNotMatch(source, /Select|Waiting|Loading|Apply|Switch Lens/)
})
```

- [ ] **Step 2: 运行中文化回归测试**

Run: `node --test frontend/scripts/frontend-chinese-copy.test.mjs`
Expected: PASS

- [ ] **Step 3: 运行既有直播/驾驶舱测试**

Run: `node --test frontend/scripts/leadership-cockpit-livestream.test.mjs`
Expected: PASS

- [ ] **Step 4: 运行前端构建**

Run: `npm --prefix frontend run build`
Expected: exit 0，可出现仓库原有 Sass 弃用警告和 chunk warning，但不能有新增构建错误

- [ ] **Step 5: Commit**

```bash
git add frontend/scripts/frontend-chinese-copy.test.mjs
git commit -m "test: verify frontend chinese copy rollout"
```
