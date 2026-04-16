<template>
  <div class="leadership-cockpit">
    <section class="hero-grid">
      <article class="shell-card hero-title">
        <p class="eyebrow">Emergency Leadership Cockpit</p>
        <h1>智能集群大载重无人机灭火系统</h1>
        <p class="hero-subtitle">陕西省森林消防应急指挥中心领导驾驶舱</p>
      </article>

      <article class="shell-card hero-brief">
        <div class="section-meta">处置简报</div>
        <h2>秦岭北坡 2 号山火处于可控压制阶段</h2>
        <p>
          当前主火点 1 处、次生火点 2 处，火线向东南缓慢扩展，已形成空地协同封控圈。
          现场无人机集群、补给、通信和道路管制均保持稳定。
        </p>
        <span class="status-pill danger">一级关注事件</span>
      </article>

      <article class="shell-card hero-clock">
        <div class="section-meta">当前时间</div>
        <div class="clock-value">14:26</div>
        <p>2026-03-30 周一</p>
        <p>指挥值守正常</p>
      </article>
    </section>

    <section class="summary-grid">
      <article
        v-for="item in summaryCards"
        :key="item.label"
        class="shell-card summary-card"
      >
        <div class="section-meta">{{ item.label }}</div>
        <div class="summary-value">{{ item.value }}</div>
        <p>{{ item.note }}</p>
      </article>
    </section>

    <section class="content-grid">
      <div class="column">
        <article class="shell-card panel-card">
          <header class="panel-header">
            <div>
              <h3>领导决策摘要</h3>
              <p>面向值班领导的核心结论，不展示飞控级操作细节。</p>
            </div>
          </header>

          <div class="decision-list">
            <section
              v-for="item in decisions"
              :key="item.title"
              class="decision-card"
            >
              <div class="decision-top">
                <h4>{{ item.title }}</h4>
                <span
                  class="status-pill"
                  :class="item.type"
                >
                  {{ item.tag }}
                </span>
              </div>
              <p>{{ item.content }}</p>
            </section>
          </div>
        </article>

        <article class="shell-card panel-card">
          <header class="panel-header">
            <div>
              <h3>现场风险与民生影响</h3>
              <p>突出群众、道路、设施、重点坡向等消防领导最关心的要点。</p>
            </div>
          </header>

          <div class="small-metric-grid">
            <section
              v-for="item in impactMetrics"
              :key="item.label"
              class="small-metric-card"
            >
              <div class="section-meta">{{ item.label }}</div>
              <div class="small-metric-value">{{ item.value }}</div>
            </section>
          </div>

          <div class="info-list">
            <section
              v-for="item in riskItems"
              :key="item.title"
              class="info-card"
            >
              <div class="info-top">
                <h4>{{ item.title }}</h4>
                <span
                  class="status-pill"
                  :class="item.type"
                >
                  {{ item.level }}
                </span>
              </div>
              <p>{{ item.content }}</p>
            </section>
          </div>
        </article>
      </div>

      <article class="shell-card panel-card map-panel">
        <header class="panel-header map-header">
          <div>
            <h3>森林火场综合态势图</h3>
            <p>领导视角聚焦火势范围、保护圈、力量投向、受威胁对象和处置效果。</p>
          </div>
          <span class="status-pill safe">空地协同封控中</span>
        </header>

        <div class="map-stage">
          <div class="mountain mountain-one"></div>
          <div class="mountain mountain-two"></div>
          <div class="mountain mountain-three"></div>
          <div class="fire-zone fire-major"></div>
          <div class="fire-zone fire-secondary"></div>
          <div class="protection-zone zone-one"></div>
          <div class="protection-zone zone-two"></div>
          <div class="route route-one"></div>
          <div class="route route-two"></div>
          <div class="route route-three"></div>

          <div
            v-for="node in mapNodes"
            :key="node.name"
            class="map-node"
            :style="{ top: node.top, left: node.left }"
          >
            <span class="map-node-dot"></span>
            <span class="map-node-label">{{ node.name }}</span>
          </div>
        </div>

        <div class="map-kpi-grid">
          <section
            v-for="item in mapKpis"
            :key="item.label"
            class="map-kpi"
          >
            <div class="section-meta">{{ item.label }}</div>
            <div class="map-kpi-value">{{ item.value }}</div>
          </section>
        </div>
      </article>

      <div class="column">
        <article class="shell-card panel-card">
          <header class="panel-header">
            <div>
              <h3>力量与保障资源</h3>
              <p>面向连续作战场景，突出力量、药剂、电池与补能保障状态。</p>
            </div>
          </header>

          <div class="info-list">
            <section
              v-for="item in resourceItems"
              :key="item.title"
              class="info-card"
            >
              <div class="info-top">
                <h4>{{ item.title }}</h4>
                <span
                  class="status-pill"
                  :class="item.type"
                >
                  {{ item.level }}
                </span>
              </div>
              <p>{{ item.content }}</p>
            </section>
          </div>
        </article>

        <article class="shell-card panel-card">
          <header class="panel-header">
            <div>
              <h3>重点告警与处置状态</h3>
              <p>只保留对决策有价值的高等级告警和处置结果。</p>
            </div>
          </header>

          <div class="info-list">
            <section
              v-for="item in alertItems"
              :key="item.title"
              class="info-card"
            >
              <div class="info-top">
                <h4>{{ item.title }}</h4>
                <span
                  class="status-pill"
                  :class="item.type"
                >
                  {{ item.level }}
                </span>
              </div>
              <p>{{ item.content }}</p>
            </section>
          </div>
        </article>
      </div>
    </section>

    <section class="footer-grid">
      <article class="shell-card footer-card">
        <h3>处置成效趋势</h3>
        <p>火场受控比例持续提升，说明当前策略有效。</p>
        <div class="trend-bars">
          <span
            v-for="(height, index) in trendBars"
            :key="index"
            :class="{ active: index >= 3 }"
            :style="{ height }"
          ></span>
        </div>
      </article>

      <article class="shell-card footer-card">
        <h3>火势扩展预测</h3>
        <p>未来 30 分钟整体向东南缓慢扩展，仍处可压制区间。</p>
        <div class="progress-track">
          <i class="progress-danger" style="width: 42%;"></i>
        </div>
      </article>

      <article class="shell-card footer-card">
        <h3>无人机轮换健康度</h3>
        <p>主力机队状态良好，轮换节奏平稳。</p>
        <div class="progress-track">
          <i class="progress-safe" style="width: 86%;"></i>
        </div>
      </article>

      <article class="shell-card footer-card">
        <h3>建议领导关注事项</h3>
        <ul class="focus-list">
          <li v-for="item in focusItems" :key="item">{{ item }}</li>
        </ul>
      </article>
    </section>
  </div>
</template>

<script lang="ts" setup>
const summaryCards = [
  { label: '受控火场面积', value: '68%', note: '较 30 分钟前提升 14%' },
  { label: '受威胁群众点位', value: '2', note: '均已完成提前疏散' },
  { label: '投入无人机力量', value: '12', note: '侦察 4 / 灭火 6 / 中继 2' },
  { label: '累计投送灭火弹', value: '29', note: '有效命中率 92%' },
  { label: '预计扑灭窗口', value: '23', note: '分钟内进入残火清理' },
  { label: '保障资源到位率', value: '96%', note: '电池、药剂、通信均充足' }
]

const decisions = [
  {
    title: '总体判断',
    tag: '态势可控',
    type: 'safe',
    content: '现阶段火势已被主封控圈限制，未出现跨山脊跃迁，建议维持当前空中压制强度。'
  },
  {
    title: '下一步建议',
    tag: '建议批示',
    type: 'default',
    content: '保持 2 个侦察架次持续巡查东南回燃区，同时提前调度补给组进入待命，不建议新增地面冒进扑救。'
  }
]

const impactMetrics = [
  { label: '受威胁村组', value: '1' },
  { label: '重要设施点', value: '3' },
  { label: '道路管制段', value: '2' },
  { label: '需重点盯防坡向', value: '东南坡' }
]

const riskItems = [
  {
    title: '东南坡回燃风险',
    level: '高',
    type: 'danger',
    content: '地表温升回弹明显，若风向继续偏东，20 分钟内有局部复燃可能。'
  },
  {
    title: '通信链路冗余',
    level: '中',
    type: 'default',
    content: '中继链路整体稳定，但建议继续保留专网备份，避免山谷遮挡造成盲区。'
  }
]

const mapNodes = [
  { name: '侦察组 A-01', top: '23%', left: '28%' },
  { name: '投送组 B-02', top: '52%', left: '23%' },
  { name: '中继组 C-01', top: '38%', left: '61%' },
  { name: '封控组 D-03', top: '60%', left: '55%' },
  { name: '补位组 E-02', top: '16%', left: '72%' }
]

const mapKpis = [
  { label: '当前主火点', value: '1' },
  { label: '次生火点', value: '2' },
  { label: '封控圈完整度', value: '91%' },
  { label: '预计稳控时间', value: '23 分钟' }
]

const resourceItems = [
  {
    title: '空中作战力量',
    level: '充足',
    type: 'safe',
    content: '在线 12 架，可立即补位 2 架，核心灭火力量满足连续两轮压制要求。'
  },
  {
    title: '药剂与投送载荷',
    level: '充足',
    type: 'safe',
    content: '可支持后续 31 次标准投送，满足本次事件全程处置。'
  },
  {
    title: '电池与补能保障',
    level: '可持续',
    type: 'default',
    content: '轮换电池 24 组，现场补能车 1 台，预计可支撑 4 小时连续作战。'
  }
]

const alertItems = [
  {
    title: '东南坡热成像温升回弹',
    level: '需持续关注',
    type: 'danger',
    content: '已安排 2 架侦察无人机轮巡，暂未触发新的扩大蔓延。'
  },
  {
    title: '山谷链路抖动',
    level: '已采取备份',
    type: 'default',
    content: '专网备链已启用，中继高度已调整，未对当前任务造成实质影响。'
  },
  {
    title: '群众点位风险',
    level: '已解除',
    type: 'safe',
    content: '下风向村组已完成疏散和交通管制，目前无人员被困报告。'
  }
]

const trendBars = ['24%', '36%', '48%', '62%', '74%', '86%']

const focusItems = [
  '东南坡复燃风险',
  '保持交通管制',
  '视风向变化决定是否增援'
]
</script>

<style lang="scss" scoped>
.leadership-cockpit {
  min-height: calc(100vh - 60px);
  padding: 16px;
  background:
    radial-gradient(circle at 14% 18%, rgba(69, 221, 255, 0.12), transparent 18%),
    radial-gradient(circle at 88% 12%, rgba(255, 97, 114, 0.12), transparent 16%),
    radial-gradient(circle at 50% 85%, rgba(103, 184, 255, 0.08), transparent 24%),
    linear-gradient(180deg, #081424 0%, #07111d 52%, #030912 100%);
  color: #f0f6ff;
  overflow: auto;
}

.leadership-cockpit,
.leadership-cockpit * {
  word-break: break-word;
  overflow-wrap: anywhere;
}

.hero-grid,
.summary-grid,
.content-grid,
.footer-grid,
.small-metric-grid,
.map-kpi-grid {
  display: grid;
  gap: 14px;
}

.hero-grid {
  grid-template-columns: 420px minmax(0, 1fr) 260px;
  margin-bottom: 14px;
}

.summary-grid {
  grid-template-columns: repeat(6, minmax(0, 1fr));
  margin-bottom: 14px;
}

.content-grid {
  grid-template-columns: 340px minmax(0, 1fr) 340px;
  align-items: start;
  margin-bottom: 14px;
}

.footer-grid {
  grid-template-columns: 1.2fr 1fr 1fr 1fr;
}

.column {
  display: grid;
  gap: 14px;
  min-width: 0;
}

.shell-card {
  position: relative;
  overflow: hidden;
  background: linear-gradient(180deg, rgba(17, 36, 60, 0.84), rgba(5, 15, 27, 0.9));
  border: 1px solid rgba(113, 179, 255, 0.2);
  box-shadow: 0 14px 40px rgba(0, 0, 0, 0.26), inset 0 1px 0 rgba(255, 255, 255, 0.04);
  backdrop-filter: blur(16px);
}

.shell-card::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(120deg, rgba(103, 184, 255, 0.08), transparent 18%, transparent 84%, rgba(69, 221, 255, 0.06));
  pointer-events: none;
}

.hero-title,
.hero-brief,
.hero-clock,
.summary-card,
.panel-card,
.footer-card {
  border-radius: 22px;
  padding: 18px 20px;
}

.hero-title h1,
.hero-brief h2,
.panel-header h3,
.footer-card h3,
.decision-card h4,
.info-card h4 {
  margin: 0;
}

.eyebrow,
.section-meta {
  color: #45ddff;
  font-size: 12px;
  letter-spacing: 0.2em;
  text-transform: uppercase;
}

.hero-title h1 {
  margin-top: 8px;
  font-size: 30px;
  line-height: 1.15;
}

.hero-subtitle,
.hero-brief p,
.hero-clock p,
.summary-card p,
.panel-header p,
.decision-card p,
.info-card p,
.footer-card p {
  margin: 0;
  color: #8fa6c1;
  font-size: 12px;
  line-height: 1.6;
}

.hero-brief {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.hero-brief h2 {
  color: #ffd36b;
  font-size: 18px;
  line-height: 1.4;
}

.hero-clock {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  justify-content: center;
  text-align: right;
}

.clock-value {
  font-size: 34px;
  font-weight: 700;
  line-height: 1.1;
  margin: 10px 0 6px;
}

.status-pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: fit-content;
  max-width: 100%;
  padding: 4px 10px;
  border-radius: 999px;
  border: 1px solid rgba(103, 184, 255, 0.2);
  background: rgba(103, 184, 255, 0.12);
  color: #e2f0ff;
  font-size: 11px;
  line-height: 1.4;
  white-space: normal;
  text-align: center;
}

.status-pill.danger {
  background: rgba(255, 97, 114, 0.12);
  border-color: rgba(255, 97, 114, 0.22);
  color: #ffe1e6;
}

.status-pill.safe {
  background: rgba(66, 226, 157, 0.12);
  border-color: rgba(66, 226, 157, 0.22);
  color: #e4fff3;
}

.status-pill.default {
  background: rgba(103, 184, 255, 0.12);
}

.summary-card {
  min-width: 0;
}

.summary-value {
  margin: 8px 0;
  font-size: 30px;
  font-weight: 700;
  line-height: 1.1;
}

.panel-card {
  min-width: 0;
}

.panel-header {
  margin-bottom: 14px;
}

.decision-list,
.info-list {
  display: grid;
  gap: 12px;
}

.decision-card,
.info-card,
.small-metric-card,
.map-kpi {
  border-radius: 16px;
  padding: 14px;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.06);
  min-width: 0;
}

.decision-top,
.info-top {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 8px;
}

.decision-card h4,
.info-card h4 {
  font-size: 15px;
  line-height: 1.4;
}

.small-metric-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  margin-bottom: 12px;
}

.small-metric-value,
.map-kpi-value {
  margin-top: 8px;
  font-size: 24px;
  font-weight: 700;
  line-height: 1.2;
}

.map-panel {
  display: grid;
  grid-template-rows: auto minmax(420px, 1fr) auto;
}

.map-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}

.map-stage {
  position: relative;
  min-height: 420px;
  border-radius: 28px;
  overflow: hidden;
  border: 1px solid rgba(113, 179, 255, 0.14);
  background:
    radial-gradient(circle at 48% 48%, rgba(103, 184, 255, 0.18), transparent 24%),
    linear-gradient(180deg, rgba(7, 18, 33, 0.94), rgba(4, 10, 19, 0.98));
}

.map-stage::before {
  content: '';
  position: absolute;
  inset: 0;
  background:
    linear-gradient(rgba(113, 179, 255, 0.07) 1px, transparent 1px),
    linear-gradient(90deg, rgba(113, 179, 255, 0.07) 1px, transparent 1px);
  background-size: 72px 72px;
  opacity: 0.42;
}

.mountain,
.protection-zone,
.fire-zone,
.route {
  position: absolute;
}

.mountain {
  inset: 12% 10% 18% 10%;
  border-radius: 46% 54% 48% 52% / 54% 48% 52% 46%;
  border: 1px dashed rgba(103, 184, 255, 0.18);
}

.mountain-two {
  inset: 20% 18% 24% 18%;
  opacity: 0.65;
}

.mountain-three {
  inset: 28% 25% 30% 25%;
  opacity: 0.4;
}

.fire-zone {
  border-radius: 999px;
  background: radial-gradient(circle, rgba(255, 211, 107, 0.96), rgba(255, 97, 114, 0.72) 56%, rgba(255, 97, 114, 0.06) 78%);
  box-shadow: 0 0 40px rgba(255, 97, 114, 0.34);
}

.fire-major {
  width: 190px;
  height: 190px;
  top: 24%;
  left: 49%;
}

.fire-secondary {
  width: 110px;
  height: 110px;
  top: 56%;
  left: 30%;
  opacity: 0.78;
}

.protection-zone {
  border: 2px dashed rgba(69, 221, 255, 0.7);
  border-radius: 50% 52% 46% 50%;
  opacity: 0.85;
}

.zone-one {
  width: 280px;
  height: 180px;
  top: 46%;
  left: 46%;
  transform: rotate(-12deg);
}

.zone-two {
  width: 250px;
  height: 150px;
  top: 26%;
  left: 18%;
  transform: rotate(18deg);
}

.route {
  border-top: 2px dashed rgba(69, 221, 255, 0.72);
  transform-origin: left center;
}

.route-one {
  width: 220px;
  top: 28%;
  left: 18%;
  transform: rotate(18deg);
}

.route-two {
  width: 200px;
  top: 63%;
  left: 20%;
  transform: rotate(-15deg);
}

.route-three {
  width: 150px;
  top: 28%;
  left: 64%;
  transform: rotate(120deg);
}

.map-node {
  position: absolute;
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.map-node-dot {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: radial-gradient(circle at 35% 35%, #ffffff, #45ddff);
  box-shadow: 0 0 18px rgba(69, 221, 255, 0.75);
  border: 2px solid rgba(255, 255, 255, 0.28);
}

.map-node-label {
  max-width: 112px;
  color: #e1efff;
  font-size: 11px;
  line-height: 1.4;
  text-align: center;
}

.map-kpi-grid {
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin-top: 14px;
}

.footer-card h3 {
  font-size: 15px;
  line-height: 1.4;
  margin-bottom: 8px;
}

.trend-bars {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  height: 86px;
  margin-top: 12px;
}

.trend-bars span {
  flex: 1;
  min-width: 0;
  border-radius: 10px 10px 0 0;
  background: linear-gradient(180deg, rgba(255, 171, 74, 0.9), rgba(255, 97, 114, 0.32));
}

.trend-bars span.active {
  background: linear-gradient(180deg, rgba(69, 221, 255, 0.88), rgba(103, 184, 255, 0.22));
}

.progress-track {
  margin-top: 14px;
  height: 8px;
  border-radius: 999px;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.08);
}

.progress-track i {
  display: block;
  height: 100%;
  border-radius: inherit;
}

.progress-danger {
  background: linear-gradient(90deg, #ffab4a, #ff6172);
}

.progress-safe {
  background: linear-gradient(90deg, #42e29d, #45ddff);
}

.focus-list {
  margin: 12px 0 0;
  padding-left: 18px;
  color: #8fa6c1;
  font-size: 12px;
  line-height: 1.7;
}

@media (max-width: 1680px) {
  .summary-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .content-grid {
    grid-template-columns: 300px minmax(0, 1fr) 300px;
  }

  .footer-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 1280px) {
  .hero-grid,
  .content-grid,
  .footer-grid {
    grid-template-columns: 1fr;
  }

  .summary-grid,
  .map-kpi-grid,
  .small-metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .hero-clock {
    align-items: flex-start;
    text-align: left;
  }

  .map-header {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
