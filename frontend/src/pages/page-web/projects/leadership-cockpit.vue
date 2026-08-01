<template>
  <div class="leadership-cockpit">
    <section class="cockpit-shell">
      <header class="cockpit-topbar">
        <div class="cockpit-title">
          <h1>森林灭火综合驾驶舱</h1>
        </div>
        <div class="cockpit-actions">
          <span class="status-pill default" :title="cockpitLastRefreshText">{{ cockpitRefreshLabel }}</span>
          <span class="status-pill" :class="cockpitDataStatusClass">{{ cockpitDataStatusText }}</span>
        </div>
      </header>

      <section class="summary-grid">
        <template
          v-for="item in cockpitSummary.metrics"
          :key="item.key"
        >
          <a-popover
            v-if="item.detailPopover"
            trigger="hover"
            placement="bottomLeft"
            overlayClassName="cockpit-summary-popover"
          >
            <template #content>
              <template
                v-for="popover in [item.detailPopover]"
                :key="popover.title"
              >
                <section class="summary-popover-panel">
                  <header class="summary-popover-header">
                    <div>
                      <span>{{ popover.subtitle }}</span>
                      <h4>{{ popover.title }}</h4>
                    </div>
                    <span class="status-pill" :class="popover.statusTone || item.tone">
                      {{ popover.statusLabel }}
                    </span>
                  </header>

                  <div
                    v-if="popover.stats && popover.stats.length"
                    class="summary-popover-stats"
                  >
                    <section
                      v-for="stat in popover.stats"
                      :key="stat.key"
                      class="summary-popover-stat"
                      :class="stat.tone"
                    >
                      <span>{{ stat.label }}</span>
                      <strong>{{ stat.value }}</strong>
                    </section>
                  </div>

                  <div v-if="popover.error" class="summary-popover-empty error">
                    {{ popover.error }}
                  </div>
                  <div v-else-if="popover.rows && popover.rows.length" class="summary-popover-list">
                    <section
                      v-for="row in popover.rows"
                      :key="row.key"
                      class="summary-popover-row"
                    >
                      <div class="summary-popover-row-head">
                        <div>
                          <strong>{{ row.title }}</strong>
                          <span>{{ row.meta }}</span>
                        </div>
                        <span class="status-pill" :class="row.statusTone">
                          {{ row.status }}
                        </span>
                      </div>
                      <p>{{ row.detail }}</p>
                      <div
                        v-if="row.metrics && row.metrics.length"
                        class="summary-popover-row-metrics"
                      >
                        <span
                          v-for="metric in row.metrics"
                          :key="metric.key"
                        >
                          {{ metric.label }} {{ metric.value }}
                        </span>
                      </div>
                    </section>
                  </div>
                  <div v-else class="summary-popover-empty">
                    {{ popover.emptyText }}
                  </div>
                </section>
              </template>
            </template>

            <article
              class="shell-card summary-card interactive"
              :class="item.tone"
              @click="handleSummaryMetricFocus(item)"
            >
              <div class="section-meta">{{ item.label }}</div>
              <div class="summary-value">{{ item.value }}</div>
              <p>{{ item.note }}</p>
            </article>
          </a-popover>

          <article
            v-else
            class="shell-card summary-card"
            :class="item.tone"
            :title="item.title || item.source"
          >
            <div class="section-meta">{{ item.label }}</div>
            <div class="summary-value">{{ item.value }}</div>
            <p>{{ item.note }}</p>
          </article>
        </template>
      </section>

      <section class="content-grid">
      <div class="column column-scroll left-ops-column">
        <article class="shell-card panel-card ai-radar-panel">
          <header class="panel-header ai-radar-header">
            <div>
              <h3>AI 风险识别雷达</h3>
            </div>
            <span class="status-pill" :class="aiRiskPillClass">{{ aiRiskPillText }}</span>
          </header>

          <div class="ai-radar-channel-strip">
            <span class="channel-chip visible">
              <VideoCameraOutlined class="channel-icon" />
              <span>可见光</span>
            </span>
            <span class="channel-chip thermal">
              <FireOutlined class="channel-icon" />
              <span>红外</span>
            </span>
            <span class="channel-chip fusion">
              <DeploymentUnitOutlined class="channel-icon" />
              <span>融合识别</span>
            </span>
          </div>

          <div class="ai-radar-stage" :class="{ active: recentAiRiskEvents.length > 0 }">
            <div class="ai-radar-scope" aria-hidden="true">
              <i class="radar-ring ring-one"></i>
              <i class="radar-ring ring-two"></i>
              <i class="radar-ring ring-three"></i>
              <i class="radar-sweep"></i>
              <span
                v-for="(event, index) in recentAiRiskEvents.slice(0, 4)"
                :key="`${event.sourceTs || 'scope'}-${index}`"
                class="radar-blip"
                :class="aiRiskLevelClass(event.riskLevel)"
              ></span>
            </div>
            <div class="ai-radar-readout">
              <span class="section-meta">AI Fire Recognition</span>
              <strong>{{ recentAiRiskEvents.length ? '风险扫描进行中' : '等待识别事件' }}</strong>
              <p>{{ recentAiRiskEvents.length ? '按融合置信度与复核状态滚动呈现。' : cockpitSummary.emptyStateHints.aiRisk }}</p>
            </div>
          </div>

          <div v-if="aiRiskState.error" class="ai-risk-empty error">
            {{ aiRiskState.error }}
          </div>
          <div v-else-if="recentAiRiskEvents.length > 0" class="ai-radar-feed">
            <div
              v-for="event in recentAiRiskEvents"
              :key="`${event.sourceTs || 'no-ts'}-${event.analysisChannel || 'unknown'}-${event.fusionScore || 0}`"
              class="ai-radar-feed-row"
              :class="aiRiskCardClass(event)"
            >
              <span class="feed-time">{{ formatAiEventTime(event.sourceTs) }}</span>
              <div class="feed-main">
                <strong>{{ formatAiChannel(event.analysisChannel) }}</strong>
                <small>{{ formatAiReviewStatus(event.reviewStatus) }}</small>
              </div>
              <span class="feed-score">{{ formatAiScore(event.fusionScore) }}</span>
            </div>
          </div>
        </article>

        <article class="shell-card panel-card fire-priority-panel">
          <header class="panel-header">
            <div class="fire-events-panel-title">
              <h3>火情事件优先队列</h3>
            </div>
            <router-link class="fire-events-panel-header-action" to="/fire-events">
              查看更多
              <span>{{ cockpitSummary.activeFireEvents.length }}</span>
            </router-link>
          </header>

          <div class="fire-priority-metrics">
            <section
              v-for="stat in cockpitSummary.fireQueueStats"
              :key="stat.key"
              class="queue-stat"
            >
              <span>{{ stat.label }}</span>
              <strong>{{ stat.value }}</strong>
            </section>
          </div>

          <div v-if="fireEventState.error" class="ai-risk-empty error">
            {{ fireEventState.error }}
          </div>
          <div v-else-if="cockpitSummary.recentFireEvents.length === 0" class="ai-risk-empty">
            {{ cockpitSummary.emptyStateHints.fireEvents }}
          </div>
          <div v-else class="fire-priority-queue">
            <div class="fire-priority-table-head" aria-hidden="true">
              <span>优先级</span>
              <span>置信度</span>
              <span>位置 / 区域</span>
              <span>任务状态</span>
            </div>
            <a-popover
              v-for="event in cockpitSummary.recentFireEvents"
              :key="event.eventId"
              trigger="hover"
              placement="rightTop"
              overlayClassName="fire-event-detail-popover"
            >
              <template #content>
                <section class="fire-event-detail-panel">
                  <header class="fire-event-detail-header">
                    <div>
                      <span>事件 ID</span>
                      <h4>{{ event.eventId }}</h4>
                    </div>
                    <span class="status-pill" :class="fireEventLevelClass(event.fireLevel)">
                      {{ formatFireStatusLabel(event.status) }}
                    </span>
                  </header>
                  <div class="fire-event-detail-stats">
                    <section>
                      <span>优先级</span>
                      <strong>{{ event.fireLevel || '--' }}</strong>
                    </section>
                    <section>
                      <span>置信度</span>
                      <strong>{{ formatFireConfidence(event.confidence) }}</strong>
                    </section>
                    <section>
                      <span>误差半径</span>
                      <strong>{{ formatFireErrorRadius(event) }}</strong>
                    </section>
                    <section>
                      <span>通知版本</span>
                      <strong>{{ event.notificationVersion ?? 1 }}</strong>
                    </section>
                  </div>
                  <div class="fire-event-detail-list">
                    <p><span>完整坐标</span><strong>{{ formatFireEventLocation(event) }}</strong></p>
                    <p><span>位置质量</span><strong>{{ formatFireGeoQuality(event) }}</strong></p>
                    <p><span>任务编号</span><strong>{{ event.missionNo || '未关联任务' }}</strong></p>
                    <p><span>事件来源</span><strong>{{ formatEventSource(event.source) }}</strong></p>
                    <p><span>更新时间</span><strong>{{ formatDateTime(event.updatedAt || event.createdAt) }}</strong></p>
                  </div>
                </section>
              </template>
              <button
                class="fire-priority-row"
                :class="[fireEventLevelClass(event.fireLevel), { selected: situationFocusKey === event.eventId }]"
                type="button"
                @click="focusSituationFire(event)"
                @keydown.enter="focusSituationFire(event)"
              >
                <span class="fire-priority-level">{{ formatFireLevelShort(event.fireLevel) }}</span>
                <span class="fire-priority-confidence">{{ formatFireConfidence(event.confidence) }}</span>
                <div class="fire-priority-location">
                  <strong>{{ formatFireLocationSummary(event) }}</strong>
                  <small>{{ formatFireErrorRadius(event) }} · {{ formatFireGeoQuality(event) }}</small>
                </div>
                <span class="fire-priority-status-text" :class="fireEventLevelClass(event.fireLevel)">
                  {{ formatFireStatusLabel(event.status) }}
                </span>
              </button>
            </a-popover>
          </div>
        </article>

        <article class="shell-card panel-card response-closure-panel">
          <header class="panel-header">
            <div>
              <h3>处置闭环</h3>
            </div>
            <span class="status-pill" :class="leftResponseReady ? 'safe' : 'default'">
              {{ leftResponseReady ? '可推进' : '待补齐' }}
            </span>
          </header>

          <div class="response-closure-list">
            <section
              v-for="item in leftResponseItems"
              :key="item.key"
              class="response-closure-item"
              :class="item.tone"
            >
              <span>{{ item.label }}</span>
              <strong>{{ item.value }}</strong>
              <small>{{ item.note }}</small>
            </section>
          </div>
        </article>
      </div>

      <article class="shell-card panel-card map-panel">
        <header class="panel-header map-header">
          <div>
            <h3>{{ visualPanelTitle }}</h3>
          </div>
          <div class="map-header-actions">
            <div class="visual-tabs">
              <button
                v-for="tab in visualTabs"
                :key="tab.key"
                class="visual-tab"
                :class="{ active: activeVisualTab === tab.key }"
                type="button"
                @click="switchVisualTab(tab.key)"
              >
                {{ tab.label }}
              </button>
            </div>
            <span
              v-if="activeVisualTab !== 'delivery-execution'"
              class="status-pill"
              :class="visualPanelPillClass"
            >
              {{ visualPanelPillText }}
            </span>
          </div>
        </header>

        <div class="visual-stage">
          <CockpitSituationMap
            v-if="activeVisualTab === 'map'"
            :layers="situationLayers"
            :focus-key="situationFocusKey"
            @select="handleSituationLayerSelect"
          />

          <div v-else-if="activeVisualTab === 'fire-monitor'" class="livestream-stage dual-stream-stage">
            <div
              ref="fireMonitorFullscreenShell"
              class="dual-stream-shell"
              :class="{ fullscreen: fireMonitorFullscreen }">
              <div class="dual-stream-stage-head">
                <CockpitAircraftStreamSelector
                  v-model:value="selectedFireMonitorTargetKey"
                  role="fire-monitor"
                  :targets="fireMonitorTargets"
                  :loading="dualStreamState.loading"
                  @change="handleFireMonitorTargetChange"
                />
                <span class="status-pill" :class="dualStreamPillClass">{{ dualStreamPillText }}</span>
                <button
                  class="fire-detect-btn"
                  :class="{ active: fireDetectionState.running }"
                  :disabled="fireDetectionState.loading"
                  @click="onToggleFireDetection"
                >
                  {{ fireDetectionButtonText }}
                </button>
              </div>

            <div class="dual-stream-player-stage">
              <div ref="primaryPlayerShell" class="dual-stream-player primary"></div>

              <div v-if="!livePaneState.primary.url" class="dual-stream-overlay">
                <div class="stream-label">{{ primaryPaneMeta.title }}</div>
                <div class="stream-value">{{ primaryPaneMeta.status }}</div>
                <p>{{ primaryPaneMeta.unavailableHint }}</p>
              </div>
              <div v-else-if="primaryPlayerState.loading" class="dual-stream-status-card loading">
                <div class="stream-label">{{ primaryPaneMeta.title }}</div>
                <div class="stream-value">播放器加载中</div>
                <p>{{ livePaneState.primary.url }}</p>
              </div>
              <div v-else-if="primaryPlayerState.error" class="dual-stream-status-card error">
                <div class="stream-label">{{ primaryPaneMeta.title }}</div>
                <div class="stream-value">播放失败</div>
                <p>{{ primaryPlayerState.error }}</p>
              </div>
              <div v-if="focusSwitching" class="dual-stream-switch-overlay">
                <span class="dual-stream-switch-spinner"></span>
                <strong>{{ focusSwitchLabel }}</strong>
                <small>正在切换直播画面</small>
              </div>

              <div class="live-badge" :class="{ idle: !primaryPlayerState.playing }">
                <span class="live-dot"></span>{{ fireDetectionLiveFeedbackText }}
              </div>

              <div class="flight-hud-overlay">
                <div class="flight-hud-row mode-row">
                  <span class="mode" :class="{ warn: flightHudData.modeWarn }">{{ flightHudData.modeText }}</span>
                </div>
                <div class="flight-hud-row">
                  <span class="flight-hud-item battery">⚡ {{ flightHudData.battery }}%</span>
                  <span class="flight-hud-item" :class="{ fixed: flightHudData.isFixed, unfixed: !flightHudData.isFixed }">
                    <span class="dot"></span>{{ flightHudData.isFixed ? '定点' : '浮动' }}
                  </span>
                  <span class="flight-hud-item">GPS {{ flightHudData.gps }}</span>
                  <span class="flight-hud-item">RTK {{ flightHudData.rtk }}</span>
                  <span class="flight-hud-item">热点 {{ hudHotspotTemp }}℃</span>
                </div>
                <div class="flight-hud-row">
                  <span class="flight-hud-item">ASL {{ flightHudData.asl }} m</span>
                  <span class="flight-hud-item">H {{ flightHudData.height }} m</span>
                  <span class="flight-hud-item">返航点 {{ flightHudData.homeDist }} 米</span>
                </div>
                <div class="flight-hud-row">
                  <span class="flight-hud-item">纬度 {{ flightHudData.lat }}</span>
                  <span class="flight-hud-item">经度 {{ flightHudData.lng }}</span>
                </div>
                <div class="flight-hud-row">
                  <span class="flight-hud-item">H.S {{ flightHudData.hSpeed }} m/s</span>
                  <span class="flight-hud-item">V.S {{ flightHudData.vSpeed }} m/s</span>
                  <span class="flight-hud-item">W.S {{ flightHudData.wSpeed }} m/s</span>
                </div>
              </div>

              <button
                class="dual-stream-preview"
                :class="{ clickable: livePaneState.preview.clickable && !focusSwitching, switching: focusSwitching }"
                type="button"
                :disabled="!livePaneState.preview.clickable || focusSwitching"
                @click="handlePreviewSwap"
              >
                <div ref="previewPlayerShell" class="dual-stream-player preview"></div>

                <div v-if="!livePaneState.preview.url" class="dual-stream-preview-overlay placeholder">
                  <span class="preview-title">{{ previewPaneMeta.title }}</span>
                  <strong v-if="previewPaneMeta.status">{{ previewPaneMeta.status }}</strong>
                  <small>{{ previewPaneMeta.helper }}</small>
                </div>
                <div v-else-if="previewPlayerState.loading" class="dual-stream-preview-overlay">
                  <span class="preview-title">{{ previewPaneMeta.title }}</span>
                  <strong>加载中</strong>
                  <small>{{ previewPaneMeta.helper }}</small>
                </div>
                <div v-else-if="previewPlayerState.error" class="dual-stream-preview-overlay error">
                  <span class="preview-title">{{ previewPaneMeta.title }}</span>
                  <strong>播放失败</strong>
                  <small>{{ previewPlayerState.error }}</small>
                </div>

                <div
                  v-if="livePaneState.preview.url && !previewPlayerState.loading && !previewPlayerState.error"
                  class="dual-stream-preview-label"
                >
                  <span>{{ previewPaneMeta.title }}</span>
                  <small>{{ previewPaneMeta.helper }}</small>
                </div>
              </button>

              <button
                class="dual-stream-fullscreen-btn"
                type="button"
                @click="toggleFireMonitorFullscreen"
              >
                {{ fireMonitorFullscreen ? '退出全屏' : '全屏' }}
              </button>

              <CockpitFlightControlPanel
                v-if="activeVisualTab === 'fire-monitor'"
                class="fire-monitor-flight-panel"
                :target="selectedFireMonitorTarget"
                :msdk-device="selectedFireMonitorMsdkDevice"
                :osd="selectedFireMonitorOsd"
              />
            </div>

            </div>
          </div>

          <div v-else class="livestream-stage delivery-stage">
            <div class="dual-stream-shell">
              <div class="dual-stream-stage-head">
                <CockpitAircraftStreamSelector
                  v-model:value="selectedDeliveryTargetKey"
                  role="delivery"
                  :targets="deliveryExecutionTargets"
                  :loading="deliveryTargetsLoading"
                />
              </div>
              <CockpitDeliveryExecutionPanel
                :target="selectedDeliveryTarget"
                :delivery-targets="deliveryExecutionTargets"
                :loading="deliveryTargetsLoading"
                @refresh-targets="loadDeliveryExecutionTargets"
              />
            </div>
          </div>
        </div>

        <div class="visual-instrument-belt">
          <div class="instrument-section-list">
            <section
              v-for="section in visualInstrumentBelt.sections"
              :key="section.key"
              class="instrument-section"
              :class="`status-${section.status || 'idle'}`"
            >
              <header class="instrument-section-head">
                <span>{{ section.title }}</span>
                <strong>{{ section.summary }}</strong>
              </header>

              <div v-if="section.key === 'videoLink'" class="instrument-signal-body">
                <div class="signal-row">
                  <span
                    v-for="signal in section.signals"
                    :key="signal.key"
                    class="signal-dot-label"
                    :class="`status-${signal.status || 'idle'}`"
                  >
                    <i></i>{{ signal.label }}
                  </span>
                </div>
                <div class="signal-wave" aria-hidden="true">
                  <i v-for="n in 18" :key="n" :style="{ height: `${18 + ((n * 13) % 36)}px` }"></i>
                </div>
                <div class="instrument-footer-row">
                  <span v-for="item in section.footers" :key="item.label">{{ item.label }} {{ item.value }}</span>
                </div>
              </div>

              <div v-else-if="section.key === 'flightReadouts'" class="flight-readout-grid">
                <div
                  v-for="readout in section.readouts"
                  :key="readout.key"
                  class="flight-readout"
                >
                  <span>{{ readout.label }}</span>
                  <strong>{{ readout.value }}</strong>
                  <small v-if="readout.unit">{{ readout.unit }}</small>
                </div>
              </div>

              <div v-else class="ai-observation-body">
                <div class="ai-tick-line">
                  <span
                    v-for="tick in section.ticks"
                    :key="tick.key"
                    :class="`status-${tick.status || 'idle'}`"
                  ></span>
                </div>
                <p>{{ section.note }}</p>
              </div>
            </section>
          </div>

          <div class="instrument-event-strip">
            <span class="event-strip-title">系统事件</span>
            <span
              v-for="event in visualInstrumentBelt.events"
              :key="event.key"
              class="event-strip-item"
              :class="`status-${event.status || 'idle'}`"
            >
              <small>{{ event.time }}</small>{{ event.label }}
            </span>
          </div>
        </div>
      </article>

      <div class="column column-scroll right-status-column">
        <article class="shell-card panel-card aircraft-status-panel">
          <header class="panel-header">
            <div>
              <h3>飞机与直播状态</h3>
            </div>
          </header>

          <div class="aircraft-status-overview">
            <section>
              <span>在线节点</span>
              <strong>{{ cockpitSummary.aircraftRows.filter(item => item.online).length }}/{{ cockpitSummary.aircraftRows.length }}</strong>
            </section>
            <section>
              <span>直播链路</span>
              <strong>{{ dualStreamPillText }}</strong>
            </section>
          </div>

          <div v-if="cockpitSummary.aircraftRows.length === 0" class="ai-risk-empty">
            {{ cockpitSummary.emptyStateHints.aircraft }}
          </div>
          <div v-else class="aircraft-node-list">
            <template
              v-for="group in cockpitSummary.aircraftGroups"
              :key="group.key"
            >
              <div class="aircraft-node-group">{{ group.label }} · {{ group.rows.length }}</div>
              <section
                v-for="aircraft in group.rows"
                :key="aircraft.key"
                class="aircraft-node-card"
                :class="{ online: aircraft.online }"
              >
                <div class="aircraft-node-main">
                  <div>
                    <h4>{{ aircraft.name }}</h4>
                  </div>
                  <span class="status-pill" :class="aircraft.online ? 'safe' : 'danger'">
                    {{ aircraft.online ? '在线' : '离线' }}
                  </span>
                </div>
                <div class="aircraft-node-meter">
                  <span :style="{ width: formatAircraftBatteryWidth(aircraft.battery) }"></span>
                </div>
                <div class="aircraft-node-meta">
                  <span>{{ aircraft.status }}</span>
                  <span>电量 {{ formatAircraftBattery(aircraft.battery) }}</span>
                </div>
                <p>{{ aircraft.detail }}</p>
              </section>
            </template>
          </div>
        </article>

        <article class="shell-card panel-card link-status-panel">
          <header class="panel-header">
            <div>
              <h3>投放与链路状态</h3>
            </div>
          </header>

          <div class="link-status-timeline">
            <section
              v-if="cockpitSummary.taskRows.length === 0"
              class="link-node-card primary"
            >
              <div class="link-node-head">
                <span>投放任务</span>
                <strong>待命</strong>
              </div>
              <p>{{ cockpitSummary.emptyStateHints.deliveryTasks }}</p>
            </section>
            <section
              v-for="task in cockpitSummary.taskRows"
              :key="task.taskId || task.missionId || task.deviceSn"
              class="link-node-card primary"
            >
              <div class="link-node-head">
                <span>{{ task.taskName || '投放任务' }}</span>
                <strong>{{ formatMissionStatus(task.status || task.phase) }}</strong>
              </div>
              <div
                v-if="task.taskId || task.missionId"
                class="link-node-id"
                :title="task.taskId || task.missionId"
              >
                任务编号 {{ formatMiddleEllipsis(task.taskId || task.missionId, 8, 4, '--') }}
              </div>
              <div class="link-node-progress">
                <span :style="{ width: formatTaskProgress(task.progressPercent) }"></span>
              </div>
              <p>阶段 {{ formatMissionStatus(task.phase) }} · 进度 {{ formatTaskProgress(task.progressPercent) }}</p>
              <p :title="task.message || task.displayMessage || task.reason">
                {{ formatDeliveryTaskMessage(task) }}
              </p>
            </section>
            <section
              v-for="row in cockpitSummary.sideHealthRows"
              :key="row.key"
              class="link-node-card"
            >
              <div class="link-node-head">
                <span>{{ row.label }}</span>
                <strong>{{ row.value }}</strong>
              </div>
              <p>{{ row.note }}</p>
            </section>
          </div>
        </article>
      </div>
      </section>
    </section>
  </div>
</template>

<script lang="ts" setup>
import { computed, h, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { notification } from 'ant-design-vue'
import { DeploymentUnitOutlined, FireOutlined, VideoCameraOutlined } from '@ant-design/icons-vue'
import {
  getDualStreamGroup,
  getDualStreamTaskEvents,
  getLiveCapacity,
  requestDualStreamFocus,
  requestFireDetectionStart,
  requestFireDetectionStop,
  getFireDetectionStatus,
  parseFireEventUpdateMessage,
  type DualStreamEvent,
  type DualStreamGroup
} from '/@/api/manage'
import { eventApi as fireEventApi } from '/@/api/fire/event'
import { waypointApi } from '/@/api/fire/waypoint'
import { deliveryApi, type DeliveryDeviceDTO } from '/@/api/fire/delivery'
import { listMsdkDevices, type MsdkDeviceState } from '/@/api/msdk-device'
import type { FireEventDTO } from '/@/types/fire/event'
import type { WaypointDTO } from '/@/types/fire/waypoint'
import { useMyStore } from '/@/store'
import { useConnectWebSocket } from '/@/hooks/use-connect-websocket'
import { EModeCode } from '/@/types/device'
import CockpitAircraftStreamSelector, { type CockpitStreamTarget } from '/@/components/cockpit/CockpitAircraftStreamSelector.vue'
import CockpitDeliveryExecutionPanel from '/@/components/cockpit/CockpitDeliveryExecutionPanel.vue'
import CockpitFlightControlPanel from '/@/components/cockpit/CockpitFlightControlPanel.vue'
import CockpitSituationMap from './CockpitSituationMap.vue'
import {
  LIVE_RECONNECT_DELAY_MS,
  buildDualStreamCandidateSns,
  buildLivePaneState,
  buildLivePlaybackKey,
  resolveAppliedFocusPreference,
  shouldAutoRestoreVisibleFocus,
  shouldReconnectLivePlayer,
  swapPrimaryPreference
} from './leadership-cockpit-live-layout.mjs'
import { buildCockpitSummary } from './leadership-cockpit-summary.mjs'
import { buildSituationLayers } from './leadership-cockpit-situation.mjs'
import { formatFireLocation, isUsableFireLocation } from './fire/fire-event-location.mjs'
import {
  fireEventNotificationKey,
  formatDetectionKind,
  formatDetectionStatus,
  formatEventSource,
  formatEventStatus,
  formatGeoQuality,
  formatLocationExplanation,
  formatMissionStatus,
  reconcileFireEventUpdate
} from './fire/fire-event-status.mjs'

const store = useMyStore()
const FIELD_AGENT_AIRCRAFT_SN = (import.meta.env.VITE_AGENT_AIRCRAFT_SN as string | undefined) || '1581F7K3D249E00AM3Q3'

// Flight HUD: 复用 WorkspaceLivestreamPanel 同款 OSD 展示。sn 来源优先级：
// fireDetectionState.droneSn -> store.currentSn -> deviceInfo 第一个可用。
const fmtHud = (v: any, digits = 2) => {
  const n = Number(v)
  return Number.isFinite(n) ? n.toFixed(digits) : '—'
}

function toDeviceOsdFromMsdk (device: MsdkDeviceState) {
  const batteryPercent = device.batteryPercent ?? 0
  return {
    longitude: Number(device.longitude ?? 0),
    latitude: Number(device.latitude ?? 0),
    gear: -1,
    mode_code: device.online ? EModeCode.Manual : EModeCode.Disconnected,
    height: String(device.height ?? 0),
    home_distance: String(device.homeDistance ?? 0),
    horizontal_speed: String(device.horizontalSpeed ?? 0),
    vertical_speed: String(device.verticalSpeed ?? 0),
    wind_speed: String(device.windSpeed ?? 0),
    wind_direction: '--',
    elevation: String(device.elevation ?? 0),
    position_state: {
      gps_number: String(device.gpsCount ?? '--'),
      is_fixed: device.positionFixed === true ? 1 : 0,
      rtk_number: String(device.rtkCount ?? '--')
    },
    battery: {
      capacity_percent: String(batteryPercent),
      landing_power: '0',
      remain_flight_time: 0,
      return_home_power: '0'
    }
  }
}

async function refreshMsdkHudDevices () {
  const res = await listMsdkDevices()
  if (res.code !== 0) return
  const devices = res.data || []
  msdkDeviceSnapshots.value = devices
  for (const device of devices) {
    if (!device.aircraftSn) continue
    store.commit('SET_DEVICE_INFO', {
      sn: device.aircraftSn,
      host: toDeviceOsdFromMsdk(device)
    })
  }
}

const flightHudSn = computed<string | undefined>(() => {
  const fromFire = fireDetectionState?.droneSn
  if (fromFire && store.state.deviceState.deviceInfo[fromFire]) return fromFire
  const cur = store.state.deviceState.currentSn
  if (cur && store.state.deviceState.deviceInfo[cur]) return cur
  const keys = Object.keys(store.state.deviceState.deviceInfo || {})
  return keys.length > 0 ? keys[0] : undefined
})
const flightHudVisible = computed(() => !!flightHudSn.value)
const flightHud = computed(() => {
  const sn = flightHudSn.value
  const osd = sn ? store.state.deviceState.deviceInfo[sn] : undefined
  const hasOsd = !!osd
  const mode = osd?.mode_code
  const modeText = !hasOsd
    ? '等待 OSD 数据'
    : (mode != null && EModeCode[mode] ? EModeCode[mode].replace(/_/g, ' ') : '—')
  const modeWarn = !hasOsd || mode === EModeCode.Disconnected || mode === EModeCode.Forced_Landing
  const hasFix = Number(osd?.latitude ?? 0) !== 0 || Number(osd?.longitude ?? 0) !== 0
  return {
    sn,
    hasOsd,
    modeText,
    modeWarn,
    battery: osd?.battery?.capacity_percent ?? '—',
    isFixed: osd?.position_state?.is_fixed === 1,
    gps: osd?.position_state?.gps_number ?? '—',
    rtk: osd?.position_state?.rtk_number ?? '—',
    asl: fmtHud(osd?.elevation),
    height: fmtHud(osd?.height),
    homeDist: fmtHud(osd?.home_distance),
    // (0,0) 是 MSDK 无定位解时的原生占位值，显示成 0.000000 会被误读为真坐标
    lat: hasFix ? fmtHud(osd?.latitude, 6) : '—',
    lng: hasFix ? fmtHud(osd?.longitude, 6) : '—',
    hSpeed: fmtHud(osd?.horizontal_speed),
    vSpeed: fmtHud(osd?.vertical_speed),
    wSpeed: fmtHud(osd?.wind_speed),
  }
})

// 左下角 HUD 的红外热点温度：探针每 2s 测画面最热点，经 dual-stream group 透传。
// 仅火情监测开启时持续刷新，其余时间为最近一次起流/测温的静态值。
const hudHotspotTemp = computed(() => {
  const t = Number(dualStreamState.group?.thermalCenterTemperatureC)
  return Number.isFinite(t) ? t.toFixed(1) : '—'
})
const flightHudData = computed(() => flightHud.value || {
  modeText: '等待 OSD 数据',
  modeWarn: true,
  battery: '--',
  isFixed: false,
  gps: '--',
  rtk: '--',
  asl: '--',
  height: '--',
  homeDist: '--',
  lat: '--',
  lng: '--',
  hSpeed: '--',
  vSpeed: '--',
  wSpeed: '--'
})

const AI_EVENT_TASK_ID = 'manual-ai-001'

const fireEventState = reactive({
  loading: false,
  error: '',
  events: [] as FireEventDTO[]
})
const deliveryTaskStatuses = ref<any[]>([])
const missionWaypoints = ref<Record<string, WaypointDTO[]>>({})
const situationFocusKey = ref('')
const cockpitLastRefreshAt = ref(0)

const visualTabs = [
  { key: 'map', label: '态势图' },
  { key: 'fire-monitor', label: '火情监测画面' },
  { key: 'delivery-execution', label: '投放执行画面' }
] as const

type VisualTabKey = typeof visualTabs[number]['key']

const activeVisualTab = ref<VisualTabKey>('map')
const msdkDeviceSnapshots = ref<MsdkDeviceState[]>([])
const selectedFireMonitorTargetKey = ref('')
const deliveryExecutionTargets = ref<CockpitStreamTarget[]>([])
const selectedDeliveryTargetKey = ref('')
const deliveryTargetsLoading = ref(false)
const fireMonitorFullscreenShell = ref<HTMLElement | null>(null)
const fireMonitorFullscreen = ref(false)
const primaryPlayerShell = ref<HTMLElement | null>(null)
const previewPlayerShell = ref<HTMLElement | null>(null)
const primaryPreference = ref<'visible' | 'thermal'>('visible')
const focusSwitching = ref(false)
const focusSwitchAction = ref<'focus-visible' | 'focus-thermal' | null>(null)

const dualStreamState = reactive({
  loading: false,
  error: '',
  group: null as DualStreamGroup | null
})

const cockpitSummary = computed(() => buildCockpitSummary({
  fireEvents: fireEventState.events,
  aiEvents: aiRiskState.events,
  msdkDevices: msdkDeviceSnapshots.value,
  deliveryTargets: deliveryExecutionTargets.value,
  deliveryTaskStatuses: deliveryTaskStatuses.value,
  dualStreamGroup: dualStreamState.group,
  deliveryTargetsLoading: deliveryTargetsLoading.value,
  fireEventsError: fireEventState.error,
  aiEventsError: aiRiskState.error,
  dualStreamError: dualStreamState.error
}))

const leftResponseItems = computed(() => {
  const summary = cockpitSummary.value
  const activeCount = summary.activeFireEvents.length
  const pending = responseStatValue('pending')
  const missionLinked = responseStatValue('missionLinked')
  const routeReady = responseStatValue('routeReady')
  const onlineAircraft = summary.aircraftRows.filter((item: any) => item.online).length
  return [
    {
      key: 'fireScope',
      label: '重点火情',
      value: `${pending}/${activeCount}`,
      note: activeCount > 0 ? '按优先级持续处置' : '等待火情上报',
      tone: activeCount > 0 ? 'danger' : 'default'
    },
    {
      key: 'missionBinding',
      label: '任务关联',
      value: `${missionLinked}/${activeCount}`,
      note: missionLinked > 0 ? '已有任务关联' : '等待任务创建',
      tone: missionLinked > 0 ? 'safe' : 'default'
    },
    {
      key: 'routeReady',
      label: '航线准备',
      value: `${routeReady}/${activeCount}`,
      note: routeReady > 0 ? '可生成航线' : '定位质量待确认',
      tone: routeReady > 0 ? 'safe' : 'warning'
    },
    {
      key: 'forceReady',
      label: '力量就绪',
      value: `${onlineAircraft}/${summary.aircraftRows.length}`,
      note: summary.aircraftRows.length > 0 ? '监测/投放设备在线' : '等待设备接入',
      tone: onlineAircraft > 0 ? 'safe' : 'default'
    }
  ]
})

const leftResponseReady = computed(() => leftResponseItems.value.some(item => item.tone === 'safe'))

function responseStatValue (key: string) {
  const value = cockpitSummary.value.fireQueueStats.find((item: any) => item.key === key)?.value
  const numeric = Number(value)
  return Number.isFinite(numeric) ? numeric : 0
}

const situationLayers = computed(() => buildSituationLayers({
  fireEvents: fireEventState.events,
  missionWaypoints: missionWaypoints.value,
  msdkDevices: msdkDeviceSnapshots.value,
  deliveryTargets: deliveryExecutionTargets.value
}))

const cockpitDataStatusText = computed(() => {
  if (fireEventState.error || aiRiskState.error || dualStreamState.error) return '部分接口异常'
  if (fireEventState.loading || aiRiskState.loading || dualStreamState.loading || deliveryTargetsLoading.value) return '数据同步中'
  return '已接入现有接口'
})

const cockpitDataStatusClass = computed(() => {
  if (fireEventState.error || aiRiskState.error || dualStreamState.error) return 'danger'
  return 'safe'
})

const cockpitRefreshLabel = computed(() => {
  if (!cockpitLastRefreshAt.value) return '等待'
  const seconds = Math.max(0, Math.floor((Date.now() - cockpitLastRefreshAt.value) / 1000))
  return `${seconds}s`
})

const cockpitLastRefreshText = computed(() => (
  cockpitLastRefreshAt.value
    ? `最近火情同步 ${new Date(cockpitLastRefreshAt.value).toLocaleTimeString('zh-CN', { hour12: false })}`
    : '等待后端返回火情数据'
))

const fireMonitorTargets = computed<CockpitStreamTarget[]>(() => {
  const targets = new Map<string, CockpitStreamTarget>()
  for (const device of msdkDeviceSnapshots.value.filter(isFireMonitorAircraftDevice)) {
    if (!device.aircraftSn) continue
    const groupForDevice = dualStreamState.group?.droneSn === device.aircraftSn ? dualStreamState.group : null
    const deviceName = device.deviceName || device.model || `火情监测 ${device.aircraftSn.slice(-4)}`
    const modelSuffix = device.model && device.model !== deviceName ? ` ${device.model}` : ''
    targets.set(device.aircraftSn, {
      key: `fire-monitor:${device.aircraftSn}`,
      role: 'fire-monitor',
      deviceSn: device.aircraftSn,
      callsign: `${deviceName}${modelSuffix}`,
      online: device.online,
      taskStatus: groupForDevice?.sessionState || device.connectionState || device.mode,
      primaryPlayUrl: groupForDevice?.visiblePlayUrl || '',
      thermalPlayUrl: groupForDevice?.thermalPlayUrl || '',
      streamStatus: device.online
        ? (groupForDevice?.visiblePlayUrl ? 'running' : 'idle')
        : 'offline',
      message: groupForDevice?.statusMessage || groupForDevice?.statusReason || device.mode
    })
  }

  const group = dualStreamState.group
  if (group?.droneSn && !targets.has(group.droneSn)) {
    targets.set(group.droneSn, {
      key: `fire-monitor:${group.droneSn}`,
      role: 'fire-monitor',
      deviceSn: group.droneSn,
      callsign: `火情监测 ${group.droneSn.slice(-4)}`,
      online: group.connectionState !== 'offline',
      taskStatus: group.sessionState || group.currentMode,
      primaryPlayUrl: group.visiblePlayUrl || '',
      thermalPlayUrl: group.thermalPlayUrl || '',
      streamStatus: group.visiblePlayUrl ? 'running' : 'idle',
      message: group.statusMessage || group.statusReason
    })
  }

  // 仅当没有任何真实设备上报时，才回退到默认占位飞机用于引导 UI。
  // 有真机连接（如 AF7PE）时不再展示这个占位机——否则它会一直显示成"在线"的幽灵设备。
  if (targets.size === 0) {
    targets.set(FIELD_AGENT_AIRCRAFT_SN, {
      key: `fire-monitor:${FIELD_AGENT_AIRCRAFT_SN}`,
      role: 'fire-monitor',
      deviceSn: FIELD_AGENT_AIRCRAFT_SN,
      callsign: `火情监测 ${FIELD_AGENT_AIRCRAFT_SN.slice(-4)}`,
      online: true,
      taskStatus: '默认监测机',
      streamStatus: 'idle',
      message: '默认 Agent 飞机'
    })
  }

  return Array.from(targets.values())
})

function isFireMonitorAircraftDevice (device: MsdkDeviceState) {
  const identity = [
    device.gatewaySn,
    device.aircraftSn,
    device.deviceName,
    device.model
  ].filter(Boolean).join(' ').toUpperCase()
  if (!device.aircraftSn) return false
  if (device.aircraftSn === 'RC_PLUS_LOCAL') return false
  return !/(^|[_\s-])RC\s*PLUS|RC_PLUS|REMOTE\s*CONTROL|遥控器/.test(identity)
}

const selectedFireMonitorTarget = computed(() => {
  return fireMonitorTargets.value.find(target => target.key === selectedFireMonitorTargetKey.value) ||
    fireMonitorTargets.value[0] ||
    null
})

const selectedFireMonitorMsdkDevice = computed(() => {
  const sn = selectedFireMonitorTarget.value?.deviceSn
  if (!sn) return null
  return msdkDeviceSnapshots.value.find(device => device.aircraftSn === sn) || null
})

const selectedFireMonitorOsd = computed(() => {
  const sn = selectedFireMonitorTarget.value?.deviceSn
  return sn ? store.state.deviceState.deviceInfo[sn] || null : null
})

const selectedDeliveryTarget = computed(() => {
  return deliveryExecutionTargets.value.find(target => target.key === selectedDeliveryTargetKey.value) ||
    deliveryExecutionTargets.value[0] ||
    null
})

const visualPanelTitle = computed(() => {
  if (activeVisualTab.value === 'map') return '森林火场综合态势图'
  if (activeVisualTab.value === 'fire-monitor') return '森林火场火情监测画面'
  return 'FC100 投放执行画面'
})

const visualPanelPillText = computed(() => {
  if (activeVisualTab.value === 'map') return '空地协同封控中'
  if (activeVisualTab.value === 'fire-monitor') return livestreamStatusPill.value
  return deliveryPanelPillText.value
})

const visualPanelPillClass = computed(() => {
  if (activeVisualTab.value === 'map') return 'safe'
  if (activeVisualTab.value === 'fire-monitor') return dualStreamPillClass.value
  return deliveryPanelPillClass.value
})

const deliveryPanelPillText = computed(() => {
  if (deliveryTargetsLoading.value) return '投放机同步中'
  const target = selectedDeliveryTarget.value
  if (!target) return '等待 FC100 投放机'
  if (target.streamStatus === 'running') return 'FC100 直播在线'
  return target.online ? 'FC100 待播放' : 'FC100 离线'
})

const deliveryPanelPillClass = computed(() => {
  const target = selectedDeliveryTarget.value
  if (!target || !target.online || target.streamStatus === 'error') return 'danger'
  if (target.streamStatus === 'running') return 'safe'
  return 'default'
})

async function switchVisualTab (tab: VisualTabKey) {
  activeVisualTab.value = tab
  if (tab === 'delivery-execution' && deliveryExecutionTargets.value.length === 0) {
    loadDeliveryExecutionTargets()
  }
  if (tab === 'fire-monitor') {
    if (!dualStreamState.group && !dualStreamState.loading) {
      loadDualStreamState()
    }
    ensureVisibleDefaultZoom(selectedFireMonitorTarget.value?.deviceSn)
    await nextTick()
    syncLivePlayers()
    ensureThermalPreviewMode()
  }
}

const visualInstrumentBelt = computed(() => {
  return cockpitSummary.value.visualInstrumentBelt?.[activeVisualTab.value] || {
    sections: [],
    events: []
  }
})

const aiRiskState = reactive({
  loading: false,
  error: '',
  events: [] as DualStreamEvent[]
})
const lastSeenAiRiskEventTs = ref(0)
const aiRiskEventsBootstrapped = ref(false)
const AI_RISK_NOTIFY_SUPPRESS_MS = 5 * 60 * 1000
const lastAiRiskNotificationByKey = new Map<string, number>()

const primaryPlayerState = reactive({
  loading: false,
  playing: false,
  error: ''
})

const previewPlayerState = reactive({
  loading: false,
  playing: false,
  error: ''
})

type PlayerRuntimeState = typeof primaryPlayerState

let dualStreamTimer: number | undefined
let msdkHudTimer: number | undefined
let aiRiskEventTimer: number | undefined
let fireDetectionStatusTimer: number | undefined
let livePlayerRetryTimer: number | undefined
let liveReconnectTimer: number | undefined
let liveReconnectAttempts = 0
let primaryPlayer: any = null
let previewPlayer: any = null
let zlmClientLoader: Promise<any> | null = null
let lastMirroredFocusCommand = ''

const syncFireMonitorFullscreenState = () => {
  fireMonitorFullscreen.value = document.fullscreenElement === fireMonitorFullscreenShell.value
}

const toggleFireMonitorFullscreen = async () => {
  const shell = fireMonitorFullscreenShell.value
  if (!shell) return
  try {
    if (document.fullscreenElement === shell) {
      await document.exitFullscreen?.()
      return
    }
    await shell.requestFullscreen()
  } catch (error: any) {
    notification.warning({
      message: '无法进入全屏',
      description: error?.message || '当前浏览器未允许页面进入全屏。'
    })
  }
}

const loadZlmRtcClient = (streamUrl: string) => {
  const existing = (window as any).ZLMRTCClient
  if (existing) {
    return Promise.resolve(existing)
  }
  if (zlmClientLoader) {
    return zlmClientLoader
  }

  const scriptBaseUrl = new URL(streamUrl.replace(/^webrtc:\/\//, 'http://'))
  const scriptUrl = `${scriptBaseUrl.protocol}//${scriptBaseUrl.host}/ZLMRTCClient.js`

  zlmClientLoader = new Promise((resolve, reject) => {
    const script = document.createElement('script')
    script.src = scriptUrl
    script.async = true
    script.onload = () => resolve((window as any).ZLMRTCClient)
    script.onerror = () => reject(new Error(`failed-to-load-zlmrtc-client:${scriptUrl}`))
    document.head.appendChild(script)
  })

  return zlmClientLoader
}

const buildZlmRtcApiUrl = (streamUrl: string) => {
  const url = new URL(streamUrl.replace(/^webrtc:\/\//, 'http://'))
  const segments = url.pathname.split('/').filter(Boolean)
  if (segments.length < 2) {
    throw new Error(`invalid-zlm-stream-url:${streamUrl}`)
  }

  const app = segments[segments.length - 2]
  const stream = segments[segments.length - 1]
  return `${url.protocol}//${url.host}/index/api/webrtc?app=${encodeURIComponent(app)}&stream=${encodeURIComponent(stream)}&type=play`
}

const resetPlayerState = (state: PlayerRuntimeState) => {
  state.loading = false
  state.playing = false
  state.error = ''
}

const destroyPlayerInstance = (
  player: any,
  state: PlayerRuntimeState,
  shell: HTMLElement | null
) => {
  if (player) {
    // 主动销毁会触发 endpoint 的 closed 事件，不能被断流看门狗当成需要重连的断开
    player.__cockpitDisposed = true
  }
  if (player?.close) {
    player.close()
  } else if (player?.destroy) {
    player.destroy()
  }
  resetPlayerState(state)
  if (shell) {
    shell.innerHTML = ''
  }
  return null
}

const cancelLiveReconnect = () => {
  if (liveReconnectTimer != null) {
    window.clearTimeout(liveReconnectTimer)
    liveReconnectTimer = undefined
  }
}

// 断流看门狗：agent 切镜头会 restartLiveStream，ZLM 踢掉 WebRTC 会话后画面定格，
// 这里统一调度重建（syncLivePlayers 会同时重建两个画面）。
const scheduleLiveReconnect = (hasPlayed: boolean, reason: string) => {
  if (activeVisualTab.value !== 'fire-monitor') return
  if (liveReconnectTimer != null) return
  if (!shouldReconnectLivePlayer({ hasPlayed, attempts: liveReconnectAttempts })) return
  liveReconnectAttempts += 1
  console.warn('[cockpit] live stream broken, scheduling reconnect', reason, 'attempt', liveReconnectAttempts)
  liveReconnectTimer = window.setTimeout(() => {
    liveReconnectTimer = undefined
    const primaryHealthy = !livePaneState.value.primary.url || primaryPlayerState.playing
    const previewHealthy = !livePaneState.value.preview.url || previewPlayerState.playing
    if (primaryHealthy && previewHealthy) return
    syncLivePlayers()
  }, LIVE_RECONNECT_DELAY_MS)
}

const applyFullFrameStyles = (video: HTMLVideoElement) => {
  Object.assign(video.style, {
    width: '100%',
    height: '100%',
    objectFit: 'contain',
    objectPosition: '50% 50%',
    transform: ''
  })
}

const mountPlayerInstance = async (
  url: string,
  shell: HTMLElement | null,
  state: PlayerRuntimeState
) => {
  if (!url || activeVisualTab.value !== 'fire-monitor') {
    resetPlayerState(state)
    if (shell) {
      shell.innerHTML = ''
    }
    return null
  }

  await nextTick()
  const mountPoint = shell
  if (!mountPoint) {
    state.loading = false
    state.playing = false
    state.error = 'player-shell-unavailable'
    return null
  }

  const video = document.createElement('video')
  video.autoplay = true
  video.muted = true
  video.playsInline = true
  video.controls = false
  video.className = 'dual-stream-video'
  Object.assign(video.style, {
    display: 'block',
    width: '100%',
    height: '100%',
    minHeight: '100%',
    objectFit: 'contain',
    background: '#02070d'
  })
  applyFullFrameStyles(video)
  mountPoint.appendChild(video)

  state.loading = true
  try {
    const apiUrl = buildZlmRtcApiUrl(url)
    const ZLMRTCClient = await loadZlmRtcClient(url)

    const markPlaying = () => {
      state.loading = false
      state.playing = true
      state.error = ''
      // 两路都健康才清零重试计数：单路持续失败时不能被另一路的成功无限续命，
      // 否则重试上限失效，健康画面会被反复陪跑重建
      const primaryHealthy = !livePaneState.value.primary.url || primaryPlayerState.playing
      const previewHealthy = !livePaneState.value.preview.url || previewPlayerState.playing
      if (primaryHealthy && previewHealthy) {
        liveReconnectAttempts = 0
      }
    }

    video.addEventListener('loadeddata', markPlaying, { once: true })
    video.addEventListener('playing', markPlaying, { once: true })
    video.addEventListener('error', () => {
      state.loading = false
      if (state.playing) {
        console.warn('[cockpit] ignore non-fatal video error after playback started', url)
        return
      }
      state.error = 'zlm-video-element-error'
      scheduleLiveReconnect(false, 'zlm-video-element-error')
    }, { once: true })

    const endpoint = new ZLMRTCClient.Endpoint({
      element: video,
      zlmsdpUrl: apiUrl,
      recvOnly: true,
      useCamera: false,
      audioEnable: false,
      videoEnable: true
    })

    endpoint.on?.(ZLMRTCClient.Events.WEBRTC_ON_CONNECTION_STATE_CHANGE, (connectionState: string) => {
      if (endpoint.__cockpitDisposed) return
      if (connectionState === 'connected') {
        markPlaying()
        return
      }
      if (connectionState === 'failed' || connectionState === 'disconnected' || connectionState === 'closed') {
        state.loading = false
        if (state.playing) {
          // 播通后断开 = 推流重建/网络中断，ZLM 已踢掉本会话，不重连画面会定格在最后一帧
          state.playing = false
          state.loading = true
          scheduleLiveReconnect(true, `zlm-connection-${connectionState}`)
          return
        }
        state.error = `zlm-connection-${connectionState}`
        scheduleLiveReconnect(false, `zlm-connection-${connectionState}`)
      }
    })

    endpoint.on?.(ZLMRTCClient.Events.WEBRTC_OFFER_ANWSER_EXCHANGE_FAILED, (payload: any) => {
      if (endpoint.__cockpitDisposed) return
      state.loading = false
      if (state.playing) {
        console.warn('[cockpit] ignore late zlm offer/answer error after playback started', payload)
        return
      }
      state.error = payload?.msg || payload?.message || 'zlm-offer-answer-exchange-failed'
      scheduleLiveReconnect(false, 'zlm-offer-answer-exchange-failed')
    })
    return endpoint
  } catch (error: any) {
    state.loading = false
    state.playing = false
    state.error = error?.message || String(error)
    return null
  }
}

const destroyAllPlayers = () => {
  primaryPlayer = destroyPlayerInstance(primaryPlayer, primaryPlayerState, primaryPlayerShell.value)
  previewPlayer = destroyPlayerInstance(previewPlayer, previewPlayerState, previewPlayerShell.value)
  if (livePlayerRetryTimer != null) {
    window.clearTimeout(livePlayerRetryTimer)
    livePlayerRetryTimer = undefined
  }
  cancelLiveReconnect()
}

const syncLivePlayers = async () => {
  if (livePlayerRetryTimer != null) {
    window.clearTimeout(livePlayerRetryTimer)
    livePlayerRetryTimer = undefined
  }
  cancelLiveReconnect()
  primaryPlayer = destroyPlayerInstance(primaryPlayer, primaryPlayerState, primaryPlayerShell.value)
  previewPlayer = destroyPlayerInstance(previewPlayer, previewPlayerState, previewPlayerShell.value)

  if (activeVisualTab.value !== 'fire-monitor') {
    return
  }

  if (!primaryPlayerShell.value || !previewPlayerShell.value) {
    livePlayerRetryTimer = window.setTimeout(() => {
      syncLivePlayers()
    }, 120)
    return
  }

  primaryPlayer = await mountPlayerInstance(
    livePaneState.value.primary.url,
    primaryPlayerShell.value,
    primaryPlayerState
  )

  previewPlayer = await mountPlayerInstance(
    livePaneState.value.preview.url,
    previewPlayerShell.value,
    previewPlayerState
  )
}

type FireDetectionPhase = 'idle' | 'starting' | 'switching' | 'running' | 'stopping' | 'switch_failed'

// 火情识别手动开关 (#4)
const fireDetectionState = reactive({
  running: false,
  loading: false,
  droneSn: '',
  phase: 'idle' as FireDetectionPhase
})

const resolveFireDetectionDroneSn = async () => {
  const candidateSns = [
    selectedFireMonitorTarget.value?.deviceSn,
    fireDetectionState.droneSn,
    dualStreamState.group?.droneSn,
    FIELD_AGENT_AIRCRAFT_SN,
    store.state.deviceState.currentSn,
    flightHudSn.value,
    ...Object.keys(store.state.deviceState.deviceInfo || {})
  ].filter((sn, index, arr): sn is string => !!sn && sn !== 'RC_PLUS_LOCAL' && arr.indexOf(sn) === index)

  if (candidateSns.length > 0) {
    return candidateSns[0]
  }

  const cap = await getLiveCapacity({} as any)
  const dev: any = (cap.data || [])[0]
  return dev?.sn || ''
}

const onToggleFireDetection = async () => {
  if (fireDetectionState.loading) return
  try {
    fireDetectionState.loading = true
    if (!fireDetectionState.droneSn) {
      fireDetectionState.droneSn = await resolveFireDetectionDroneSn()
    }
    if (!fireDetectionState.droneSn) {
      notification.warning({
        message: '无法启动火情监测',
        description: '未找到可用无人机 SN，请确认遥控器 Agent 已连接并上报状态。'
      })
      return
    }
    if (fireDetectionState.running) {
      fireDetectionState.phase = 'stopping'
      const res = await requestFireDetectionStop(fireDetectionState.droneSn)
      if (res.code !== 0) {
        throw new Error(res.message || 'ai-service stop failed')
      }
      fireDetectionState.running = false
      fireDetectionState.phase = 'idle'
      primaryPreference.value = 'visible'
      if (isThermalFocusActive()) {
        await switchFireMonitorFocus('focus-visible', { bestEffort: true })
      }
    } else {
      fireDetectionState.phase = 'starting'
      const res = await requestFireDetectionStart(fireDetectionState.droneSn)
      if (res.code !== 0) {
        throw new Error(res.message || 'ai-service start failed')
      }
      fireDetectionState.running = true
      // 纯可见光识别：镜头本来就在可见光时不发 focus-visible——
      // agent 收到镜头命令会 restartLiveStream，无操作切换也会让直播卡一下。
      if (isThermalFocusActive()) {
        fireDetectionState.phase = 'switching'
        const switched = await switchFireMonitorFocus('focus-visible', { bestEffort: true })
        fireDetectionState.phase = switched ? 'running' : 'switch_failed'
      } else {
        fireDetectionState.phase = 'running'
      }
    }
  } catch (e) {
    console.warn('[cockpit] fire-detection toggle failed', e)
    fireDetectionState.phase = fireDetectionState.running ? 'switch_failed' : 'idle'
    notification.error({
      message: fireDetectionState.running ? '停止火情监测失败' : '启动火情监测失败',
      description: (e as any)?.message || '请检查后端和 ai-service 是否正常运行。'
    })
  } finally {
    fireDetectionState.loading = false
  }
}

// agent 上报的当前镜头模式：只有真在红外时才需要切回可见光。
const isThermalFocusActive = () => {
  return String(dualStreamState.group?.currentMode || '').toLowerCase().includes('thermal')
}

// 轮询后端真实监测状态，让按钮同步航线自动启停（第一航点自动开、返航完成自动关）。
// 手动启停过程中（loading）跳过，避免覆盖中间态。
const syncFireDetectionStatus = async () => {
  if (fireDetectionState.loading) return
  const sn = fireDetectionState.droneSn || selectedFireMonitorTarget.value?.deviceSn
  if (!sn) return
  try {
    const res = await getFireDetectionStatus(sn)
    if (res?.code !== 0) return
    const running = Boolean(res.data?.running)
    if (running === fireDetectionState.running) return
    fireDetectionState.droneSn = sn
    fireDetectionState.running = running
    fireDetectionState.phase = running ? 'running' : 'idle'
  } catch (e) {
    // 轮询失败忽略，下次再试
  }
}

const loadDualStreamState = async () => {
  dualStreamState.loading = true
  try {
    const selectedSn = selectedFireMonitorTarget.value?.deviceSn
    const candidateSns = buildDualStreamCandidateSns({
      flightHudSn: flightHudSn.value,
      agentAircraftSn: selectedSn || FIELD_AGENT_AIRCRAFT_SN,
      currentSn: store.state.deviceState.currentSn,
      fireDetectionSn: fireDetectionState.droneSn
    })

    let lastError: any = null
    let selectedGroup: DualStreamGroup | null = null
    for (const sn of candidateSns) {
      try {
        const response = await getDualStreamGroup(sn)
        if (response.data) {
          selectedGroup = response.data
          break
        }
      } catch (error: any) {
        lastError = error
      }
    }
    dualStreamState.group = selectedGroup
    mirrorAppliedFocusCommand(selectedGroup)
    maybeRestoreDefaultVisibleFocus(selectedGroup)
    if (!selectedGroup && lastError) throw lastError
    dualStreamState.error = ''
  } catch (error: any) {
    dualStreamState.error = error?.message || 'dual-stream-state-unavailable'
  } finally {
    dualStreamState.loading = false
  }
}

function handleFireMonitorTargetChange (target: CockpitStreamTarget) {
  fireDetectionState.droneSn = target.deviceSn
  fireDetectionState.running = false
  fireDetectionState.phase = 'idle'
  primaryPreference.value = 'visible'
  loadDualStreamState()
}

function resolveDeliveryDeviceModel (device: DeliveryDeviceDTO) {
  const explicit = device.displayName || device.model
  if (explicit) return explicit
  const raw = `${device.deviceType || ''} ${device.deviceModelKey || ''} ${device.deviceModelClass || ''}`.toLowerCase()
  if (raw.includes('0-122-0') || raw.includes('fc100') || raw.includes('flycart')) return 'DJI Flycart100'
  if (raw.includes('fc30')) return 'DJI FlyCart 30'
  return device.deviceType || 'DJI Flycart100'
}

function toDeliveryTarget (device: DeliveryDeviceDTO): CockpitStreamTarget {
  const onlineText = String(device.online || '').toLowerCase()
  const online = onlineText === 'true' || onlineText === 'online' || onlineText === '1'
  const model = resolveDeliveryDeviceModel(device)
  return {
    key: `delivery:${device.deviceSn}`,
    role: 'delivery',
    deviceSn: device.deviceSn,
    callsign: model,
    model,
    displayName: model,
    online,
    streamStatus: online ? 'idle' : 'offline',
    compactLabel: true
  }
}

function isFc100DeliveryAircraftDevice (device: DeliveryDeviceDTO) {
  const deviceType = String(device.deviceType || '').trim().toLowerCase()
  const bindStatus = String(device.bindStatus || '').trim().toLowerCase()
  return Boolean(device.deviceSn) && deviceType !== 'rc' && bindStatus !== 'rc'
}

async function loadDeliveryExecutionTargets () {
  deliveryTargetsLoading.value = true
  try {
    const response = await deliveryApi.listDevices()
    const devices = response.data?.data || []
    const targets = devices
      .filter(isFc100DeliveryAircraftDevice)
      .map(toDeliveryTarget)

    const enriched = await Promise.all(targets.map(async (target) => {
      try {
        const [liveRes, propsRes] = await Promise.all([
          deliveryApi.deviceLive(target.deviceSn),
          deliveryApi.deviceProps(target.deviceSn).catch(() => null)
        ])
        const live = liveRes.data?.data
        const props = propsRes?.data?.data
        const enrichedTarget: CockpitStreamTarget = {
          ...target,
          online: props?.onlineStatus ?? target.online,
          primaryPlayUrl: live?.playUrl || '',
          streamStatus: live?.streamStatus === 'running'
            ? 'running'
            : ((props?.onlineStatus ?? target.online) ? 'idle' : 'offline')
        } as CockpitStreamTarget
        ;(enrichedTarget as any).batteryPercent = props?.batteryPercent
        ;(enrichedTarget as any).latitude = props?.latitude
        ;(enrichedTarget as any).longitude = props?.longitude
        ;(enrichedTarget as any).altitude = props?.altitude
        ;(enrichedTarget as any).horizontalSpeed = props?.horizontalSpeed
        ;(enrichedTarget as any).verticalSpeed = props?.verticalSpeed
        ;(enrichedTarget as any).homeDistance = props?.homeDistance
        ;(enrichedTarget as any).windSpeed = props?.windSpeed
        return enrichedTarget
      } catch {
        return target
      }
    }))

    deliveryExecutionTargets.value = enriched
    if (!enriched.some(target => target.key === selectedDeliveryTargetKey.value)) {
      selectedDeliveryTargetKey.value = enriched[0]?.key || ''
    }
  } finally {
    deliveryTargetsLoading.value = false
  }
}

const mirrorAppliedFocusCommand = (group: DualStreamGroup | null) => {
  if (!group?.lastCommandAction || group.lastCommandStatus?.toLowerCase() !== 'applied') {
    return
  }
  const commandKey = [
    group.droneSn || '',
    group.lastCommandAction,
    group.lastCommandStatus,
    group.currentMode || '',
    group.visiblePlayUrl || '',
    group.thermalPlayUrl || ''
  ].join('|')
  if (commandKey === lastMirroredFocusCommand) {
    return
  }
  const nextPreference = resolveAppliedFocusPreference({
    currentPreference: primaryPreference.value,
    lastCommandAction: group.lastCommandAction,
    lastCommandStatus: group.lastCommandStatus,
    fireDetectionRunning: fireDetectionState.running
  })
  lastMirroredFocusCommand = commandKey
  if (nextPreference !== primaryPreference.value) {
    primaryPreference.value = nextPreference
  }
}

// 未运行火情监测时驾驶舱默认保持可见光：发现遗留的红外焦点状态就 best-effort 下发 focus-visible。
// 用冷却时间去抖（轮询 5s，冷却 4s 允许每个轮询周期最多补发一次）；一旦相机切回可见光，
// 后端 lastCommandAction 变成 focus-visible，shouldAutoRestoreVisibleFocus 返回 false 自动停止。
const visibleFocusRestoreAtByDrone = new Map<string, number>()
const VISIBLE_FOCUS_RESTORE_COOLDOWN_MS = 4000

const maybeRestoreDefaultVisibleFocus = (group: DualStreamGroup | null) => {
  const droneSn = group?.droneSn
  if (!droneSn) {
    return
  }
  if (!shouldAutoRestoreVisibleFocus({
    fireDetectionRunning: fireDetectionState.running,
    focusSwitching: focusSwitching.value,
    primaryPreference: primaryPreference.value,
    lastCommandAction: group?.lastCommandAction,
    lastCommandStatus: group?.lastCommandStatus,
    currentMode: group?.currentMode
  })) {
    return
  }
  const now = Date.now()
  const lastAttempt = visibleFocusRestoreAtByDrone.get(droneSn) || 0
  if (now - lastAttempt < VISIBLE_FOCUS_RESTORE_COOLDOWN_MS) {
    return
  }
  visibleFocusRestoreAtByDrone.set(droneSn, now)
  requestDualStreamFocus(droneSn, 'focus-visible').catch(() => {
    visibleFocusRestoreAtByDrone.delete(droneSn)
  })
}

// 进入可见光视图（切换监测飞机 / 进入火情监测 tab）时，主动下发 focus-visible：
// agent 会把镜头切到可见光并把变焦重置为 1x，保证"每次进去默认 1 倍焦距"。
// 仅在未运行火情监测时生效（监测中由复核流程接管画面），复用同一冷却避免重复下发。
const ensureVisibleDefaultZoom = (droneSn?: string) => {
  if (fireDetectionState.running || focusSwitching.value) {
    return
  }
  if (!droneSn || droneSn === FIELD_AGENT_AIRCRAFT_SN) {
    return
  }
  const now = Date.now()
  const lastAttempt = visibleFocusRestoreAtByDrone.get(droneSn) || 0
  if (now - lastAttempt < VISIBLE_FOCUS_RESTORE_COOLDOWN_MS) {
    return
  }
  visibleFocusRestoreAtByDrone.set(droneSn, now)
  requestDualStreamFocus(droneSn, 'focus-visible').catch(() => {
    visibleFocusRestoreAtByDrone.delete(droneSn)
  })
}

const loadAiRiskEvents = async () => {
  aiRiskState.loading = true
  try {
    const response = await getDualStreamTaskEvents(AI_EVENT_TASK_ID)
    const events = response.data ?? []
    notifyNewAiRiskEvents(events)
    aiRiskState.events = events
    aiRiskState.error = ''
  } catch (error: any) {
    aiRiskState.error = error?.message || 'ai-risk-events-unavailable'
  } finally {
    aiRiskState.loading = false
  }
}

function notifyNewAiRiskEvents (events: DualStreamEvent[]) {
  if (events.length === 0) return

  const maxTs = events.reduce((max, event) => Math.max(max, Number(event.sourceTs) || 0), lastSeenAiRiskEventTs.value)
  if (!aiRiskEventsBootstrapped.value) {
    lastSeenAiRiskEventTs.value = maxTs
    aiRiskEventsBootstrapped.value = true
    return
  }

  const fresh = events
    .filter(event => (Number(event.sourceTs) || 0) > lastSeenAiRiskEventTs.value)
    .filter(event => {
      const level = (event.riskLevel || '').toUpperCase()
      return level === 'LOW' || level === 'MEDIUM' || level === 'HIGH'
    })
    .filter(event => Number(event.fusionScore) > 0)
    .sort((a, b) => (Number(a.sourceTs) || 0) - (Number(b.sourceTs) || 0))

  for (const event of fresh) {
    const level = (event.riskLevel || '').toUpperCase()
    const notifyTs = normalizeAiEventTimestamp(event.sourceTs)
    const notifyKey = buildAiRiskNotificationKey(event)
    const lastNotifiedTs = lastAiRiskNotificationByKey.get(notifyKey)
    if (lastNotifiedTs != null && notifyTs - lastNotifiedTs < AI_RISK_NOTIFY_SUPPRESS_MS) {
      continue
    }
    lastAiRiskNotificationByKey.set(notifyKey, notifyTs)
    const levelLabel = level === 'HIGH' ? '高风险' : level === 'MEDIUM' ? '中等风险' : '低风险'
    notification.warning({
      message: `AI 识别提示：${levelLabel}火情`,
      description: `融合分数 ${formatAiScore(event.fusionScore)}，通道 ${formatAiChannel(event.analysisChannel)}，时间 ${formatAiEventTime(event.sourceTs)}`,
      duration: 8,
      placement: 'topRight'
    })
  }

  lastSeenAiRiskEventTs.value = maxTs
}

function normalizeAiEventTimestamp (sourceTs?: number) {
  const ts = Number(sourceTs) || Date.now()
  return ts > 10_000_000_000 ? ts : ts * 1000
}

function buildAiRiskNotificationKey (event: DualStreamEvent) {
  const level = (event.riskLevel || 'UNKNOWN').toUpperCase()
  return [
    event.taskId || AI_EVENT_TASK_ID,
    event.droneSn || fireDetectionState.droneSn || FIELD_AGENT_AIRCRAFT_SN,
    event.analysisChannel || 'unknown',
    level
  ].join(':')
}

const wait = (durationMs: number) => new Promise(resolve => {
  window.setTimeout(resolve, durationMs)
})

const waitForFocusCommandApplied = async (action: 'focus-visible' | 'focus-thermal') => {
  for (let attempt = 0; attempt < 16; attempt += 1) {
    await loadDualStreamState()
    const group = dualStreamState.group
    const lastCommandAction = group?.lastCommandAction
    const lastCommandStatus = group?.lastCommandStatus
    if (lastCommandAction === action && lastCommandStatus?.toLowerCase() === 'applied') {
      return true
    }
    if (lastCommandAction === action && lastCommandStatus?.toLowerCase() === 'failed') {
      dualStreamState.error = `${action}-failed`
      return false
    }
    await wait(500)
  }
  dualStreamState.error = `${action}-not-applied`
  return false
}

const ensureThermalPreviewMode = async () => {
  // Pilot2 and the web cockpit share the same RC Plus liveview source.
  // Do not auto-switch RC Plus to thermal/PIP just to populate the web preview.
}

function focusSituationFire (event: FireEventDTO) {
  activeVisualTab.value = 'map'
  situationFocusKey.value = event.eventId
}

function handleSituationLayerSelect (key: string) {
  situationFocusKey.value = key
}

function handleSummaryMetricFocus (item: any) {
  if (!['activeFireEvents', 'highestFireLevel', 'aiEvents', 'aircraftOnline'].includes(item?.key)) return
  activeVisualTab.value = 'map'
  const firstFire = situationLayers.value.fireMarkers[0]
  const firstAircraft = situationLayers.value.aircraftMarkers[0]
  situationFocusKey.value = item.key === 'aircraftOnline'
    ? (firstAircraft?.id || '')
    : (firstFire?.eventId || firstFire?.id || '')
}

// 火情事件弹窗：每 3 秒拉一次 backend /api/fire/events，新出现的 LOW/MEDIUM/HIGH
// 火情弹 antd notification 带带框的标注图缩略图。lastSeenFireEventId 防止首次进
// 页面把历史事件全弹出来。
let fireEventNotifyTimer: number | null = null
const lastSeenFireEventId = ref(0)
const fireEventsBootstrapped = ref(false)
const lastNotifiedFireEventVersions = new Map<string, number>()

useConnectWebSocket((payload: any) => {
  const update = parseFireEventUpdateMessage(payload)
  if (!update) return
  const reconciled = reconcileFireEventUpdate(fireEventState.events, update)
  if (!reconciled.accepted || !reconciled.event) return
  fireEventState.events = reconciled.events as FireEventDTO[]
  showFireEventNotification(reconciled.event as FireEventDTO)
  loadCockpitFireEvents()
})

async function loadCockpitFireEvents (): Promise<FireEventDTO[]> {
  fireEventState.loading = true
  try {
    const res = await fireEventApi.list()
    const events = res.data.data ?? []
    fireEventState.events = events
    fireEventState.error = ''
    cockpitLastRefreshAt.value = Date.now()
    const missionNos = Array.from(new Set(events.map(event => event.missionNo).filter(Boolean))) as string[]
    const visibleMissionNos = missionNos.slice(0, 6)
    const statuses = await Promise.all(visibleMissionNos.map(async (missionNo) => {
      try {
        const statusRes = await deliveryApi.status(missionNo)
        return statusRes.data?.data || null
      } catch {
        return null
      }
    }))
    deliveryTaskStatuses.value = statuses.filter(Boolean)
    const waypointEntries = await Promise.all(visibleMissionNos.map(async (missionNo) => {
      try {
        const waypointRes = await waypointApi.list(missionNo)
        return [missionNo, waypointRes.data?.data || []] as [string, WaypointDTO[]]
      } catch {
        return [missionNo, []] as [string, WaypointDTO[]]
      }
    }))
    missionWaypoints.value = Object.fromEntries(waypointEntries)
    return events
  } catch (e) {
    console.warn('[cockpit] fire event poll failed', e)
    fireEventState.error = '火情数据暂不可用'
    return []
  } finally {
    fireEventState.loading = false
  }
}

async function loadNewFireEvents (): Promise<void> {
  const events = await loadCockpitFireEvents()
  if (events.length === 0) {
    return
  }
  const maxId = events.reduce((m, e) => (e.id > m ? e.id : m), 0)
  if (!fireEventsBootstrapped.value) {
    lastSeenFireEventId.value = maxId
    for (const evt of events) {
      const current = lastNotifiedFireEventVersions.get(evt.eventId) ?? 0
      lastNotifiedFireEventVersions.set(evt.eventId, Math.max(current, evt.notificationVersion ?? 1))
    }
    fireEventsBootstrapped.value = true
    return
  }
  const fresh = events
    .filter((e) => {
      const level = (e.fireLevel || '').toUpperCase()
      return level === 'LOW' || level === 'MEDIUM' || level === 'HIGH'
    })
    .filter(shouldNotifyFireEvent)
    .sort((a, b) => a.id - b.id)
  for (const evt of fresh) {
    showFireEventNotification(evt)
  }
  lastSeenFireEventId.value = maxId
}

function showFireEventNotification (evt: FireEventDTO) {
  const level = (evt.fireLevel || '').toUpperCase()
  const conf = Number(evt.confidence)
  const imageUrl = evt.thermalImageUrl || evt.visibleImageUrl
  const imageKind = evt.thermalImageUrl ? '红外' : '可见光'
  const levelLabel = level === 'HIGH' ? '高' : level === 'MEDIUM' ? '中等' : level === 'LOW' ? '低' : ''
  const agentState = evt.detectionStatus || evt.state
  notification.warning({
    key: fireEventNotificationKey(evt),
    message: levelLabel ? `检测到${levelLabel}风险火情` : `${formatDetectionKind(evt.detectionKind)}即时提醒`,
    description: h('div', { style: 'display:flex;gap:12px;align-items:flex-start' }, [
      imageUrl
        ? h('a', {
          href: imageUrl,
          target: '_blank',
          rel: 'noopener',
          style: 'flex:none; display:block; position:relative'
        }, [
          h('img', {
            src: imageUrl,
            alt: imageKind + '识别图',
            style: 'width:120px;height:68px;object-fit:cover;border:1px solid #555;border-radius:4px;display:block;cursor:zoom-in'
          }),
          h('span', {
            style: 'position:absolute; top:2px; left:2px; padding:1px 6px; font-size:10px; background:rgba(0,0,0,0.6); color:#fff; border-radius:3px'
          }, imageKind)
        ])
        : null,
      h('div', { style: 'font-size:12px;line-height:1.6' }, [
        h('div', `事件: ${evt.eventId}`),
        Number.isFinite(conf) ? h('div', `置信度: ${conf.toFixed(2)}`) : null,
        h('div', `位置: ${formatFireLocation(evt, 4, '-')}`),
        h('div', `状态: ${agentState ? formatDetectionStatus(agentState) : formatEventStatus(evt.status)}`),
        h('div', `定位说明: ${formatLocationExplanation(evt)}`),
        h('div', `通知版本: ${evt.notificationVersion ?? 1}`),
        h('a', {
          href: '/fire-events',
          target: '_blank',
          rel: 'noopener',
          style: 'color:#69b1ff'
        }, '查看完整列表 →')
      ])
    ]),
    duration: 12,
    placement: 'topRight'
  })
  lastNotifiedFireEventVersions.set(evt.eventId, evt.notificationVersion ?? 1)
}

function shouldNotifyFireEvent (evt: FireEventDTO) {
  const version = evt.notificationVersion ?? 1
  const lastVersion = lastNotifiedFireEventVersions.get(evt.eventId)
  if (lastVersion == null) {
    return evt.id > lastSeenFireEventId.value
  }
  return version > lastVersion
}

onMounted(async () => {
  document.addEventListener('fullscreenchange', syncFireMonitorFullscreenState)
  refreshMsdkHudDevices()
  loadDualStreamState()
  loadDeliveryExecutionTargets()
  loadAiRiskEvents()
  loadNewFireEvents()
  msdkHudTimer = window.setInterval(refreshMsdkHudDevices, 2000)
  dualStreamTimer = window.setInterval(loadDualStreamState, 5000)
  aiRiskEventTimer = window.setInterval(loadAiRiskEvents, 2000)
  fireEventNotifyTimer = window.setInterval(loadNewFireEvents, 3000)
  syncFireDetectionStatus()
  fireDetectionStatusTimer = window.setInterval(syncFireDetectionStatus, 4000)
})

onBeforeUnmount(() => {
  document.removeEventListener('fullscreenchange', syncFireMonitorFullscreenState)
  if (msdkHudTimer != null) {
    window.clearInterval(msdkHudTimer)
  }
  if (dualStreamTimer != null) {
    window.clearInterval(dualStreamTimer)
  }
  if (aiRiskEventTimer != null) {
    window.clearInterval(aiRiskEventTimer)
  }
  if (fireEventNotifyTimer != null) {
    window.clearInterval(fireEventNotifyTimer)
  }
  if (fireDetectionStatusTimer != null) {
    window.clearInterval(fireDetectionStatusTimer)
  }
  destroyAllPlayers()
})

const dualStreamSummary = computed(() => {
  const group = dualStreamState.group
  return {
    droneSn: group?.droneSn || 'RC_PLUS_LOCAL',
    session: group?.sessionState || (dualStreamState.loading ? 'LOADING' : 'UNKNOWN'),
    connection: group?.connectionState || 'UNKNOWN',
    mode: group?.currentMode || 'IDLE',
    visible: group?.visibleState || 'idle',
    thermal: group?.thermalState || 'idle',
    playbackStatus: group?.playbackStatus || '待媒体地址接入',
    rawVisiblePlayUrl: group?.visiblePlayUrl || '',
    rawThermalPlayUrl: group?.thermalPlayUrl || '',
    visiblePlayUrl: group?.visiblePlayUrl || '未提供',
    thermalPlayUrl: group?.thermalPlayUrl || '未提供',
    thermalCenterTemperatureC: group?.thermalCenterTemperatureC,
    reason: group?.statusReason || dualStreamState.error || '',
    playbackHint: group?.visiblePlayUrl
      ? '驾驶舱已拿到可见光 WebRTC 地址，当前主画面直接从本机 ZLMediaKit 拉流。'
      : '驾驶舱当前只读取 RC Plus runtime 状态；新链路 Web 播放地址尚未由 backend/agent 提供。',
    capability: group == null
      ? '待探测'
      : `visible ${group.visibleSupported ? 'yes' : 'no'} / thermal ${group.thermalSupported ? 'yes' : 'no'}`,
    lastCommand: group?.lastCommandAction
      ? `${group.lastCommandAction} / ${group.lastCommandStatus || 'pending'}`
      : '无'
  }
})

const dualStreamPillText = computed(() => {
  if (dualStreamState.loading) return '状态同步中'
  if (dualStreamState.error) return '状态查询异常'
  if (dualStreamSummary.value.visible === 'running') return 'Visible 主通道在线'
  if (dualStreamSummary.value.session === 'RUNNING') return '双流会话运行中'
  return '等待 RC Plus 运行态'
})

const dualStreamPillClass = computed(() => {
  if (dualStreamState.error) return 'danger'
  if (dualStreamSummary.value.visible === 'running') return 'safe'
  return 'default'
})

const livestreamStatusPill = computed(() => dualStreamPillText.value)

const livePaneState = computed(() => buildLivePaneState({
  visiblePlayUrl: dualStreamSummary.value.rawVisiblePlayUrl,
  thermalPlayUrl: dualStreamSummary.value.rawThermalPlayUrl,
  primaryPreference: primaryPreference.value,
  appliedFocusAction: dualStreamState.group?.lastCommandAction,
  appliedFocusStatus: dualStreamState.group?.lastCommandStatus,
  allowSharedThermalPreview: true
}))

const livePlaybackKey = computed(() => buildLivePlaybackKey({
  primaryKind: livePaneState.value.primary.kind,
  primaryUrl: livePaneState.value.primary.url,
  primaryCrop: livePaneState.value.primary.crop,
  previewKind: livePaneState.value.preview.kind,
  previewUrl: livePaneState.value.preview.url,
  previewCrop: livePaneState.value.preview.crop,
  currentMode: dualStreamState.group?.currentMode
}))

const primaryPaneMeta = computed(() => {
  const isThermal = livePaneState.value.primary.kind.startsWith('thermal')
  return {
    title: isThermal ? '红外主画面' : '可见光主画面',
    badge: isThermal ? '红外主画面' : '可见光主画面',
    status: isThermal ? dualStreamSummary.value.thermal : dualStreamSummary.value.visible,
    unavailableHint: isThermal
      ? '红外独立播放地址尚未提供，当前无法切为主画面。'
      : dualStreamSummary.value.playbackHint
  }
})

const previewPaneMeta = computed(() => {
  const isThermal = livePaneState.value.preview.kind.startsWith('thermal')
  const isPlaceholder = livePaneState.value.preview.kind.endsWith('placeholder')
  const isClickableThermalPlaceholder = isThermal && isPlaceholder && livePaneState.value.preview.clickable
  return {
    title: isThermal ? '红外画面' : '可见光画面',
    status: isClickableThermalPlaceholder
      ? ''
      : (isThermal ? '红外画面' : dualStreamSummary.value.visible),
    helper: livePaneState.value.preview.clickable
      ? (isThermal ? '点击切换为红外画面' : '点击切换为可见光画面')
      : (isPlaceholder ? '切换入口暂不可用' : '点击切换为主画面')
  }
})

const focusSwitchLabel = computed(() => (
  focusSwitchAction.value === 'focus-thermal' ? '红外画面加载中' : '可见光画面加载中'
))

const fireDetectionButtonText = computed(() => {
  if (fireDetectionState.phase === 'starting') return '正在启动...'
  if (fireDetectionState.phase === 'stopping') return '正在停止...'
  if (fireDetectionState.running) return '火情监测中'
  return '开始火情监测'
})

const fireDetectionLiveFeedbackText = computed(() => {
  if (fireDetectionState.phase === 'starting') return '正在启动火情监测'
  if (fireDetectionState.phase === 'switching') return '火情监测已启动，正在切换红外画面'
  if (fireDetectionState.phase === 'switch_failed') return '火情监测已启动 · 红外切换失败，当前显示可见光'
  if (fireDetectionState.running && primaryPreference.value === 'thermal') return 'AI 火情监测中 · 红外识别'
  return primaryPaneMeta.value.badge
})

const recentAiRiskEvents = computed(() => aiRiskState.events.slice(-10).reverse())

const aiRiskPillText = computed(() => {
  if (aiRiskState.loading && aiRiskState.events.length === 0) return '同步中'
  if (aiRiskState.error) return '读取异常'
  return `${recentAiRiskEvents.value.length} 条记录`
})

const aiRiskPillClass = computed(() => {
  if (aiRiskState.error) return 'danger'
  return recentAiRiskEvents.value.some(event => ['LOW', 'MEDIUM', 'HIGH'].includes((event.riskLevel || '').toUpperCase()))
    ? 'danger'
    : 'default'
})

const formatAiScore = (score?: number) => {
  if (typeof score !== 'number' || Number.isNaN(score)) {
    return '--'
  }
  return score.toFixed(3)
}

const formatFireConfidence = (confidence: number | string) => {
  const n = Number(confidence)
  return Number.isFinite(n) ? n.toFixed(2) : '--'
}

const formatMiddleEllipsis = (
  value: string | number | null | undefined,
  head = 10,
  tail = 6,
  fallback = '--'
) => {
  const text = value == null ? '' : String(value)
  if (!text) return fallback
  if (text.length <= head + tail + 1) return text
  const start = text.slice(0, head)
  const end = text.slice(-tail)
  return `${start}…${end}`
}

const formatFireLevelShort = (level?: string | null) => {
  const normalized = String(level || '').toUpperCase()
  if (normalized === 'HIGH') return '高'
  if (normalized === 'MEDIUM') return '中'
  if (normalized === 'LOW') return '低'
  return '--'
}

const formatFireStatusLabel = (status?: string | null) => formatEventStatus(status)

const formatFireGeoQuality = (event: FireEventDTO) => {
  const explanation = formatLocationExplanation(event)
  return explanation !== '未知状态'
    ? explanation
    : formatGeoQuality(event.geoQuality || event.locationStatus)
}

const formatFireEventLocation = (event: FireEventDTO) => {
  if (!isUsableFireLocation(event)) return formatFireLocation(event)
  const errorRadius = Number(event.geoErrorRadiusM)
  const errorText = Number.isFinite(errorRadius) ? ` · 误差 ${errorRadius.toFixed(1)}m` : ''
  return `${formatFireLocation(event)}${errorText}`
}

const formatFireLocationSummary = (event: FireEventDTO) => {
  return formatFireLocation(event, 4)
}

const formatFireLatitude = (event: FireEventDTO) => {
  if (!isUsableFireLocation(event)) return '--'
  const lat = Number(event.lat)
  return Number.isFinite(lat) ? lat.toFixed(5) : '--'
}

const formatFireLongitude = (event: FireEventDTO) => {
  if (!isUsableFireLocation(event)) return '--'
  const lng = Number(event.lng)
  return Number.isFinite(lng) ? lng.toFixed(5) : '--'
}

const formatFireErrorRadius = (event: FireEventDTO) => {
  const errorRadius = Number(event.geoErrorRadiusM)
  return Number.isFinite(errorRadius) ? `误差 ${errorRadius.toFixed(1)}m` : '误差 --'
}

const formatDateTime = (value?: string | number | null) => {
  if (value == null || value === '') return '--'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  return date.toLocaleString('zh-CN', {
    hour12: false,
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  })
}

const fireEventLevelClass = (level?: string | null) => {
  const normalized = String(level || '').toUpperCase()
  if (normalized === 'HIGH' || normalized === 'MEDIUM') return 'danger'
  if (normalized === 'LOW') return 'default'
  return 'safe'
}

const formatAircraftBattery = (battery?: number) => {
  const n = Number(battery)
  return Number.isFinite(n) ? `${n.toFixed(0)}%` : '--'
}

const formatAircraftBatteryWidth = (battery?: number) => {
  const n = Number(battery)
  if (!Number.isFinite(n)) return '0%'
  return `${Math.min(100, Math.max(0, n)).toFixed(0)}%`
}

const formatTaskProgress = (progress?: number | null) => {
  const n = Number(progress)
  return Number.isFinite(n) ? `${n.toFixed(0)}%` : '--'
}

const formatDeliveryTaskMessage = (task: any) => {
  const status = String(task?.status || task?.phase || '').toUpperCase()
  if (status === 'NOT_FOUND') return '平台未返回该投放任务'
  if (task?.displayMessage) return task.displayMessage
  if (task?.reason) return task.reason
  const message = String(task?.message || '').trim()
  if (!message) return '等待投放平台返回任务消息'
  return message.replace(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/ig, '任务编号')
}

const formatAiEventTime = (sourceTs?: number) => {
  if (!sourceTs) {
    return '--:--:--'
  }
  const timestamp = sourceTs > 10_000_000_000 ? sourceTs : sourceTs * 1000
  return new Date(timestamp).toLocaleTimeString('zh-CN', { hour12: false })
}

const formatAiChannel = (channel?: string) => {
  if (channel === 'thermal') return '红外复核'
  if (channel === 'visible') return '可见光识别'
  if (channel === 'dual') return '双通道融合'
  return '未知通道'
}

const formatAiReviewStatus = (status?: string) => {
  if (status === 'VISIBLE_SUSPECTED') return '可见光疑似'
  if (status === 'THERMAL_CONFIRMED') return '红外确认'
  if (status === 'THERMAL_REJECTED') return '红外未确认'
  return status ? '未知状态' : '未进入复核流程'
}

const aiRiskLevelClass = (riskLevel?: string) => {
  const normalized = (riskLevel || '').toUpperCase()
  if (normalized === 'HIGH' || normalized === 'MEDIUM') return 'danger'
  if (normalized === 'LOW') return 'danger'
  return 'safe'
}

const aiReviewStatusClass = (status?: string) => {
  if (status === 'THERMAL_CONFIRMED' || status === 'VISIBLE_SUSPECTED') return 'danger'
  if (status === 'THERMAL_REJECTED') return 'safe'
  return 'default'
}

const aiRiskCardClass = (event: DualStreamEvent) => {
  const riskLevel = (event.riskLevel || '').toUpperCase()
  if (event.reviewStatus === 'THERMAL_CONFIRMED' || riskLevel === 'HIGH' || riskLevel === 'MEDIUM' || riskLevel === 'LOW') {
    return 'attention'
  }
  if (event.reviewStatus === 'THERMAL_REJECTED') {
    return 'resolved'
  }
  return ''
}

const switchFireMonitorFocus = async (
  action: 'focus-visible' | 'focus-thermal',
  options: { bestEffort?: boolean } = {}
) => {
  focusSwitching.value = true
  focusSwitchAction.value = action
  try {
    await requestDualStreamFocus(dualStreamSummary.value.droneSn, action)
    const focusApplied = await waitForFocusCommandApplied(action)
    if (!focusApplied) {
      return false
    }
    primaryPreference.value = action === 'focus-thermal' ? 'thermal' : 'visible'
    await loadDualStreamState()
    return true
  } catch (error: any) {
    dualStreamState.error = error?.message || `${action}-request-failed`
    return Boolean(options.bestEffort)
  } finally {
    focusSwitching.value = false
    focusSwitchAction.value = null
  }
}

const handlePreviewSwap = async () => {
  if (!livePaneState.value.preview.clickable || focusSwitching.value) {
    return
  }
  const action = livePaneState.value.preview.focusAction || (
    swapPrimaryPreference(primaryPreference.value) === 'thermal' ? 'focus-thermal' : 'focus-visible'
  )
  await switchFireMonitorFocus(action)
}

watch(
  fireMonitorTargets,
  (targets) => {
    const current = targets.find(target => target.key === selectedFireMonitorTargetKey.value)
    // 当前选中仍可用（在线且非离线流）时保留——离线项在下拉框里被禁用，无法被手动选中，
    // 因此一旦当前选中是离线，必然是自动默认产生的，可安全改选为最佳在线设备。
    if (current && current.online && current.streamStatus !== 'offline') {
      return
    }
    const best = targets.find(target => target.online && target.streamStatus === 'running') ||
      targets.find(target => target.online && target.streamStatus !== 'offline') ||
      targets[0]
    selectedFireMonitorTargetKey.value = best?.key || ''
  },
  { immediate: true }
)

watch(
  selectedFireMonitorTarget,
  (target, previous) => {
    if (!target || target.deviceSn === previous?.deviceSn) return
    fireDetectionState.droneSn = target.deviceSn
    fireDetectionState.running = false
    fireDetectionState.phase = 'idle'
    primaryPreference.value = 'visible'
    loadDualStreamState()
    ensureVisibleDefaultZoom(target.deviceSn)
  }
)

watch(
  activeVisualTab,
  (tab) => {
    if (tab === 'delivery-execution' && deliveryExecutionTargets.value.length === 0) {
      loadDeliveryExecutionTargets()
    }
    if (tab === 'fire-monitor') {
      ensureVisibleDefaultZoom(selectedFireMonitorTarget.value?.deviceSn)
    }
  }
)

watch(
  [
    activeVisualTab,
    livePlaybackKey
  ],
  () => {
    syncLivePlayers()
    ensureThermalPreviewMode()
  },
  { immediate: true }
)

</script>

<style lang="scss" scoped>
.leadership-cockpit {
  box-sizing: border-box;
  min-height: calc(100vh - 60px);
  padding: 16px;
  background: linear-gradient(180deg, #07111d 0%, #030812 100%);
  color: #f0f6ff;
  overflow: auto;
}

.leadership-cockpit,
.leadership-cockpit * {
  word-break: break-word;
  overflow-wrap: anywhere;
}

.summary-grid,
.content-grid,
.footer-grid,
.small-metric-grid {
  display: grid;
  gap: 14px;
}

.cockpit-shell {
  box-sizing: border-box;
  min-height: calc(100vh - 92px);
  padding: 18px;
  border: 1px solid rgba(69, 221, 255, 0.42);
  border-radius: 18px;
  background: linear-gradient(180deg, #071321 0%, #050b14 100%);
  box-shadow:
    0 18px 45px rgba(0, 0, 0, 0.32),
    inset 0 1px 0 rgba(157, 237, 255, 0.08);
}

.cockpit-topbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  margin-bottom: 14px;
}

.cockpit-title h1 {
  margin: 0;
  color: #e9f8ff;
  font-size: 28px;
  line-height: 1.2;
}

.cockpit-title p {
  margin: 8px 0 0;
  color: #7f9eb7;
  font-size: 13px;
  line-height: 1.5;
}

.cockpit-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 10px;
}

.summary-grid {
  grid-template-columns: repeat(8, minmax(0, 1fr));
  margin-bottom: 14px;
}

.content-grid {
  grid-template-columns: 460px minmax(0, 1fr) 340px;
  align-items: stretch;
  min-height: clamp(860px, 82vh, 1120px);
  height: auto;
  margin-bottom: 14px;
}

.footer-grid {
  grid-template-columns: 1.2fr 1fr 1fr 1fr;
}

.column {
  display: grid;
  gap: 14px;
  min-width: 0;
  height: 100%;
  max-height: 100%;
  min-height: 0;
  align-content: start;
}

.column-scroll {
  overflow: visible;
  overscroll-behavior: contain;
  padding-right: 6px;
  padding-bottom: 0;
  scrollbar-width: thin;
  scrollbar-color: rgba(69, 221, 255, 0.42) rgba(255, 255, 255, 0.04);
}

.column-scroll::-webkit-scrollbar {
  width: 6px;
}

.column-scroll::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background: rgba(69, 221, 255, 0.42);
}

.column-scroll::-webkit-scrollbar-track {
  background: rgba(255, 255, 255, 0.04);
}

.right-status-column {
  grid-template-rows: auto minmax(0, 1fr);
  align-content: stretch;
  gap: 14px;
}

.right-status-column .link-status-panel {
  display: flex;
  min-height: 0;
  flex-direction: column;
}

.right-status-column .link-status-timeline {
  flex: 1 1 auto;
  align-content: start;
  gap: 8px;
}

.left-ops-column {
  grid-template-rows: auto minmax(0, 1fr) auto;
}

.shell-card {
  position: relative;
  overflow: hidden;
  background: #0a2130;
  border: 1px solid rgba(69, 221, 255, 0.28);
  box-shadow: inset 0 1px 0 rgba(168, 239, 255, 0.06);
}

.shell-card::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(120deg, rgba(103, 184, 255, 0.08), transparent 18%, transparent 84%, rgba(69, 221, 255, 0.06));
  pointer-events: none;
}

.summary-card,
.panel-card,
.footer-card {
  border-radius: 8px;
  padding: 16px;
}

.panel-header h3,
.footer-card h3,
.decision-card h4,
.info-card h4,
.ai-risk-alert-header h4 {
  margin: 0;
}

.eyebrow,
.section-meta {
  color: #45ddff;
  font-size: 12px;
  letter-spacing: 0.2em;
  text-transform: uppercase;
}

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

.ai-radar-panel {
  overflow: hidden;
  border-color: rgba(67, 215, 255, 0.26);
  background:
    linear-gradient(180deg, rgba(7, 30, 47, 0.92), rgba(6, 18, 32, 0.88)),
    rgba(9, 28, 43, 0.82);
}

.ai-radar-header {
  margin-bottom: 10px;
}

.ai-radar-channel-strip {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 12px;
}

.channel-chip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  min-width: 0;
  min-height: 36px;
  padding: 7px 9px;
  border: 1px solid rgba(69, 221, 255, 0.18);
  border-radius: 6px;
  color: #c9f4ff;
  font-size: 12px;
  font-weight: 800;
  line-height: 1.2;
  background: rgba(69, 221, 255, 0.06);
  white-space: nowrap;
}

.channel-chip.visible {
  border-color: rgba(255, 105, 120, 0.28);
  background: rgba(255, 105, 120, 0.08);
}

.channel-chip.thermal {
  border-color: rgba(255, 191, 77, 0.3);
  background: rgba(255, 191, 77, 0.08);
}

.channel-chip.fusion {
  border-color: rgba(66, 226, 157, 0.3);
  background: rgba(66, 226, 157, 0.08);
}

.channel-icon {
  flex: 0 0 auto;
  font-size: 15px;
}

.ai-radar-stage {
  display: grid;
  grid-template-columns: 112px minmax(0, 1fr);
  gap: 12px;
  align-items: center;
  min-height: 116px;
  margin-bottom: 12px;
  padding: 12px;
  border: 1px solid rgba(69, 221, 255, 0.18);
  border-radius: 8px;
  background:
    linear-gradient(90deg, rgba(69, 221, 255, 0.1), rgba(69, 221, 255, 0.025)),
    rgba(3, 17, 30, 0.52);
}

.ai-radar-scope {
  position: relative;
  width: 96px;
  height: 96px;
  border-radius: 50%;
  border: 1px solid rgba(69, 221, 255, 0.28);
  background:
    radial-gradient(circle at center, rgba(69, 221, 255, 0.16), rgba(69, 221, 255, 0.03) 48%, transparent 50%),
    linear-gradient(rgba(69, 221, 255, 0.08) 1px, transparent 1px),
    linear-gradient(90deg, rgba(69, 221, 255, 0.08) 1px, transparent 1px);
  background-size: auto, 24px 24px, 24px 24px;
  overflow: hidden;
}

.radar-ring {
  position: absolute;
  inset: 18px;
  border: 1px solid rgba(69, 221, 255, 0.18);
  border-radius: 50%;
}

.radar-ring.ring-two {
  inset: 31px;
}

.radar-ring.ring-three {
  inset: 43px;
}

.radar-sweep {
  position: absolute;
  inset: 0;
  background: conic-gradient(from -45deg, rgba(66, 226, 157, 0.42), transparent 20%, transparent);
  animation: radar-sweep 4s linear infinite;
  transform-origin: center;
}

@keyframes radar-sweep {
  to {
    transform: rotate(360deg);
  }
}

.radar-blip {
  position: absolute;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #42e29d;
  box-shadow: 0 0 14px rgba(66, 226, 157, 0.72);
}

.radar-blip:nth-of-type(1) {
  top: 28px;
  left: 60px;
}

.radar-blip:nth-of-type(2) {
  top: 54px;
  left: 30px;
}

.radar-blip:nth-of-type(3) {
  top: 64px;
  left: 68px;
}

.radar-blip:nth-of-type(4) {
  top: 22px;
  left: 34px;
}

.radar-blip.danger {
  background: #ff6978;
  box-shadow: 0 0 16px rgba(255, 105, 120, 0.74);
}

.ai-radar-readout {
  min-width: 0;
}

.ai-radar-readout strong {
  display: block;
  margin: 5px 0;
  color: #eefbff;
  font-size: 15px;
  line-height: 1.3;
}

.ai-radar-readout p,
.ai-risk-empty {
  color: #8fa6c1;
  font-size: 12px;
  line-height: 1.5;
}

.ai-radar-feed {
  display: grid;
  border-block: 1px solid rgba(69, 221, 255, 0.14);
}

.ai-radar-feed-row {
  display: grid;
  grid-template-columns: 58px minmax(0, 1fr) 54px;
  gap: 10px;
  align-items: center;
  min-width: 0;
  padding: 9px 0;
  border-bottom: 1px solid rgba(69, 221, 255, 0.1);
}

.ai-radar-feed-row:last-child {
  border-bottom: 0;
}

.ai-radar-feed-row.attention {
  color: #ffe1e6;
}

.ai-radar-feed-row.resolved {
  color: #d9ffed;
}

.feed-time,
.feed-main small,
.feed-score {
  color: #8fb8d0;
  font-size: 11px;
  line-height: 1.3;
}

.feed-main {
  min-width: 0;
}

.feed-main strong {
  display: block;
  color: #eafcff;
  font-size: 12px;
  line-height: 1.35;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.feed-main small {
  display: block;
  margin-top: 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.feed-score {
  color: #dff7ff;
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.ai-risk-empty {
  padding: 12px;
  margin-bottom: 12px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.035);
  border: 1px dashed rgba(113, 179, 255, 0.16);
}

.ai-risk-empty.error {
  color: #ffe1e6;
  border-color: rgba(255, 97, 114, 0.28);
  background: rgba(255, 97, 114, 0.08);
}

.alert-divider {
  height: 1px;
  margin: 2px 0 12px;
  background: linear-gradient(90deg, rgba(113, 179, 255, 0.2), transparent);
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
  min-height: 108px;
}

.summary-card.interactive {
  cursor: pointer;
  transition: border-color 0.16s ease, background 0.16s ease, box-shadow 0.16s ease;
}

.summary-card.interactive:hover {
  border-color: rgba(69, 221, 255, 0.64);
  background:
    linear-gradient(180deg, rgba(69, 221, 255, 0.09), rgba(12, 31, 48, 0.64)),
    rgba(10, 33, 48, 0.72);
  box-shadow: 0 0 0 1px rgba(69, 221, 255, 0.12), 0 16px 34px rgba(0, 16, 30, 0.22);
}

.summary-card p {
  overflow-wrap: anywhere;
  font-size: 11px;
  line-height: 1.4;
}

.summary-value {
  margin: 8px 0;
  color: #e9f8ff;
  font-size: 28px;
  font-weight: 700;
  line-height: 1.1;
}

.summary-card.danger .summary-value {
  color: #ff6978;
}

.summary-card.safe .summary-value {
  color: #72ff6a;
}

.summary-card.default .summary-value {
  color: #ffd866;
}

:global(.cockpit-summary-popover) {
  max-width: min(440px, calc(100vw - 32px));
}

:global(.cockpit-summary-popover .ant-popover-inner) {
  border: 1px solid rgba(69, 221, 255, 0.34);
  border-radius: 8px;
  background:
    linear-gradient(180deg, rgba(9, 28, 45, 0.98), rgba(5, 16, 29, 0.98));
  box-shadow: 0 24px 54px rgba(0, 8, 18, 0.54), inset 0 1px 0 rgba(146, 230, 255, 0.12);
}

:global(.cockpit-summary-popover .ant-popover-inner-content) {
  width: min(420px, calc(100vw - 40px));
  padding: 0;
}

:global(.cockpit-summary-popover .ant-popover-arrow-content) {
  background: #091c2d;
  border-color: rgba(69, 221, 255, 0.28);
}

:global(.summary-popover-panel) {
  min-width: 0;
  color: #e9f8ff;
}

:global(.summary-popover-header) {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  padding: 14px 16px 12px;
  border-bottom: 1px solid rgba(69, 221, 255, 0.18);
}

:global(.summary-popover-header span:first-child) {
  display: block;
  max-width: 270px;
  color: #7db8d8;
  font-size: 11px;
  line-height: 1.35;
  overflow-wrap: anywhere;
}

:global(.summary-popover-header h4) {
  margin: 4px 0 0;
  color: #effbff;
  font-size: 16px;
  line-height: 1.35;
  font-weight: 800;
  letter-spacing: 0;
}

:global(.summary-popover-stats) {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 1px;
  padding: 12px 16px 0;
}

:global(.summary-popover-stat) {
  min-width: 0;
  padding: 8px 9px;
  border: 1px solid rgba(82, 174, 223, 0.16);
  background: rgba(69, 221, 255, 0.06);
}

:global(.summary-popover-stat span) {
  display: block;
  color: #8fb8d0;
  font-size: 11px;
  line-height: 1.35;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

:global(.summary-popover-stat strong) {
  display: block;
  margin-top: 4px;
  color: #eafcff;
  font-size: 17px;
  line-height: 1.1;
  font-variant-numeric: tabular-nums;
}

:global(.summary-popover-stat.danger strong) {
  color: #ff6978;
}

:global(.summary-popover-stat.warning strong) {
  color: #ffd866;
}

:global(.summary-popover-stat.safe strong) {
  color: #72ff6a;
}

:global(.summary-popover-list) {
  display: grid;
  gap: 9px;
  max-height: min(52vh, 430px);
  overflow-y: auto;
  padding: 12px 16px 16px;
}

:global(.summary-popover-row) {
  min-width: 0;
  padding: 11px 12px;
  border: 1px solid rgba(69, 221, 255, 0.16);
  border-radius: 8px;
  background:
    linear-gradient(90deg, rgba(69, 221, 255, 0.08), rgba(69, 221, 255, 0.02)),
    rgba(11, 35, 52, 0.72);
}

:global(.summary-popover-row-head) {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

:global(.summary-popover-row-head strong) {
  display: block;
  color: #f2fbff;
  font-size: 13px;
  line-height: 1.35;
  overflow-wrap: anywhere;
}

:global(.summary-popover-row-head div > span) {
  display: block;
  margin-top: 2px;
  color: #77a6c0;
  font-size: 11px;
  line-height: 1.35;
}

:global(.summary-popover-row p) {
  margin: 8px 0 0;
  color: #a6c3d8;
  font-size: 12px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}

:global(.summary-popover-row-metrics) {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 9px;
}

:global(.summary-popover-row-metrics span) {
  max-width: 100%;
  padding: 3px 7px;
  border-radius: 999px;
  background: rgba(103, 184, 255, 0.1);
  color: #9dc7df;
  font-size: 11px;
  line-height: 1.35;
  overflow-wrap: anywhere;
}

:global(.summary-popover-empty) {
  margin: 12px 16px 16px;
  padding: 16px;
  border: 1px dashed rgba(69, 221, 255, 0.22);
  border-radius: 8px;
  color: #96b9cf;
  font-size: 12px;
  line-height: 1.6;
  background: rgba(69, 221, 255, 0.05);
}

:global(.summary-popover-empty.error) {
  border-color: rgba(255, 105, 120, 0.34);
  color: #ffc3c9;
  background: rgba(255, 105, 120, 0.08);
}

.panel-card {
  min-width: 0;
}

.panel-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.panel-header > div {
  min-width: 0;
}

.panel-header h3 {
  color: #7ee8ff;
}

.decision-list,
.info-list {
  display: grid;
  gap: 12px;
}

.fire-priority-panel {
  display: flex;
  min-height: 0;
  flex-direction: column;
  border-color: rgba(69, 221, 255, 0.28);
  background:
    linear-gradient(180deg, rgba(24, 31, 35, 0.68), rgba(7, 20, 33, 0.9)),
    rgba(9, 28, 43, 0.82);
}

.fire-priority-panel .fire-priority-queue {
  flex: 1 1 auto;
}

.fire-priority-metrics,
.queue-stat-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin: 0 0 12px;
}

.queue-stat {
  min-width: 0;
  padding: 10px;
  border: 1px solid rgba(69, 221, 255, 0.14);
  border-radius: 10px;
  background: rgba(69, 221, 255, 0.06);
}

.queue-stat span {
  display: block;
  color: #8bb8d7;
  font-size: 11px;
  line-height: 1.3;
}

.queue-stat strong {
  display: block;
  margin-top: 6px;
  color: #e8f7ff;
  font-size: 20px;
  line-height: 1;
  font-variant-numeric: tabular-nums;
}

.fire-priority-queue {
  display: grid;
  border-top: 1px solid rgba(255, 191, 77, 0.16);
  border-bottom: 1px solid rgba(255, 191, 77, 0.16);
}

.fire-priority-table-head,
.fire-priority-row {
  display: grid;
  grid-template-columns: 44px 68px minmax(126px, 1fr) 82px;
  gap: 10px;
  align-items: center;
  min-width: 0;
}

.fire-priority-table-head {
  padding: 9px 8px 9px 12px;
  color: #90b6cd;
  font-size: 12px;
  line-height: 1.2;
  border-bottom: 1px solid rgba(255, 255, 255, 0.07);
  background: rgba(3, 17, 30, 0.42);
}

.fire-priority-table-head span {
  min-width: 0;
  white-space: nowrap;
  word-break: keep-all;
  overflow-wrap: normal;
}

.fire-priority-row {
  appearance: none;
  position: relative;
  width: 100%;
  min-height: 66px;
  padding: 11px 8px 11px 12px;
  border: 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.07);
  border-radius: 0;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
  transition: background 0.16s ease, transform 0.16s ease;
}

.fire-priority-row:last-child {
  border-bottom: 0;
}

.fire-priority-row::before {
  content: '';
  position: absolute;
  top: 12px;
  bottom: 12px;
  left: 0;
  width: 3px;
  border-radius: 3px;
  background: #42e29d;
}

.fire-priority-row.danger::before {
  background: #ff6978;
  box-shadow: 0 0 12px rgba(255, 105, 120, 0.56);
}

.fire-priority-row.default::before {
  background: #ffbf4d;
  box-shadow: 0 0 10px rgba(255, 191, 77, 0.48);
}

.fire-priority-row:hover,
.fire-priority-row.selected {
  background: rgba(255, 255, 255, 0.045);
  transform: translateX(2px);
}

.fire-priority-id,
.fire-priority-location,
.fire-priority-mission {
  min-width: 0;
}

.fire-priority-level {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 6px;
  color: #ffbf4d;
  font-size: 14px;
  font-weight: 800;
  background: rgba(255, 191, 77, 0.12);
  border: 1px solid rgba(255, 191, 77, 0.28);
  white-space: nowrap;
  word-break: keep-all;
  overflow-wrap: normal;
}

.fire-priority-row.danger .fire-priority-level {
  color: #ff7f8c;
  background: rgba(255, 105, 120, 0.12);
  border-color: rgba(255, 105, 120, 0.34);
}

.fire-priority-row.default .fire-priority-level {
  color: #ffcf6e;
}

.fire-priority-id strong,
.fire-priority-location strong {
  display: block;
  color: #f4fbff;
  font-size: 12px;
  line-height: 1.32;
  overflow-wrap: anywhere;
  word-break: break-all;
}

.fire-priority-id strong,
.fire-priority-mission {
  display: block;
  max-width: 100%;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  overflow-wrap: normal;
  word-break: keep-all;
}

.fire-priority-id small,
.fire-priority-location small {
  display: block;
  margin-top: 3px;
  color: #91adc5;
  font-size: 11px;
  line-height: 1.3;
  overflow-wrap: anywhere;
}

.coordinate-pair {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 4px;
}

.coordinate-pair span {
  min-width: 0;
}

.coordinate-pair small {
  display: block;
  margin: 0 0 2px;
  color: #6f91aa;
  font-size: 10px;
  line-height: 1;
  white-space: nowrap;
  word-break: keep-all;
  overflow-wrap: normal;
}

.coordinate-pair strong {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  word-break: keep-all;
  overflow-wrap: normal;
}

.coordinate-error {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  word-break: keep-all;
  overflow-wrap: normal;
}

.fire-priority-confidence {
  color: #d7f4ff;
  font-size: 14px;
  font-weight: 800;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  word-break: keep-all;
  overflow-wrap: normal;
}

.fire-priority-location strong {
  color: #f4fbff;
  font-size: 13px;
  line-height: 1.25;
}

.fire-priority-location small {
  margin-top: 5px;
  font-size: 11px;
  line-height: 1.25;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  word-break: keep-all;
  overflow-wrap: normal;
}

.fire-priority-status-text {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 0;
  max-width: 100%;
  min-height: 28px;
  padding: 0 8px;
  border: 1px solid rgba(103, 184, 255, 0.22);
  border-radius: 999px;
  background: rgba(103, 184, 255, 0.1);
  color: #d9f3ff;
  font-size: 12px;
  font-weight: 700;
  line-height: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.fire-priority-status-text.danger {
  border-color: rgba(255, 105, 120, 0.34);
  background: rgba(255, 105, 120, 0.12);
  color: #ffdce1;
}

.fire-priority-status-text.safe {
  border-color: rgba(66, 226, 157, 0.26);
  background: rgba(66, 226, 157, 0.1);
  color: #dfffee;
}

:global(.fire-event-detail-popover) {
  max-width: min(420px, calc(100vw - 32px));
}

:global(.fire-event-detail-popover .ant-popover-inner) {
  border: 1px solid rgba(69, 221, 255, 0.34);
  border-radius: 8px;
  background:
    linear-gradient(180deg, rgba(9, 28, 45, 0.98), rgba(5, 16, 29, 0.98));
  box-shadow: 0 24px 54px rgba(0, 8, 18, 0.54), inset 0 1px 0 rgba(146, 230, 255, 0.12);
}

:global(.fire-event-detail-popover .ant-popover-inner-content) {
  width: min(400px, calc(100vw - 40px));
  padding: 0;
}

:global(.fire-event-detail-popover .ant-popover-arrow-content) {
  background: #091c2d;
  border-color: rgba(69, 221, 255, 0.28);
}

:global(.fire-event-detail-panel) {
  min-width: 0;
  color: #e9f8ff;
}

:global(.fire-event-detail-header) {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  padding: 14px 16px 12px;
  border-bottom: 1px solid rgba(69, 221, 255, 0.18);
}

:global(.fire-event-detail-header div) {
  min-width: 0;
}

:global(.fire-event-detail-header span:first-child) {
  display: block;
  color: #7db8d8;
  font-size: 11px;
  line-height: 1.35;
}

:global(.fire-event-detail-header h4) {
  margin: 4px 0 0;
  color: #effbff;
  font-size: 15px;
  line-height: 1.35;
  font-weight: 800;
  letter-spacing: 0;
  overflow-wrap: anywhere;
}

:global(.fire-event-detail-stats) {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 1px;
  padding: 12px 16px 0;
}

:global(.fire-event-detail-stats section) {
  min-width: 0;
  padding: 8px 9px;
  border: 1px solid rgba(82, 174, 223, 0.16);
  background: rgba(69, 221, 255, 0.06);
}

:global(.fire-event-detail-stats span) {
  display: block;
  color: #8fb8d0;
  font-size: 11px;
  line-height: 1.35;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

:global(.fire-event-detail-stats strong) {
  display: block;
  margin-top: 4px;
  color: #eafcff;
  font-size: 15px;
  line-height: 1.1;
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}

:global(.fire-event-detail-list) {
  display: grid;
  gap: 8px;
  padding: 12px 16px 16px;
}

:global(.fire-event-detail-list p) {
  display: grid;
  grid-template-columns: 66px minmax(0, 1fr);
  gap: 10px;
  align-items: start;
  margin: 0;
  color: #a6c3d8;
  font-size: 12px;
  line-height: 1.45;
}

:global(.fire-event-detail-list span) {
  color: #7fa8c3;
  white-space: nowrap;
}

:global(.fire-event-detail-list strong) {
  min-width: 0;
  color: #e9f8ff;
  font-weight: 700;
  overflow-wrap: anywhere;
}

.task-identifier {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.fire-events-panel-header-action {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  gap: 7px;
  min-height: 30px;
  padding: 0 11px;
  border: 1px solid rgba(69, 221, 255, 0.32);
  border-radius: 999px;
  background: rgba(69, 221, 255, 0.08);
  color: #bceeff;
  font-size: 12px;
  line-height: 1;
  text-decoration: none;
  white-space: nowrap;
  transition: color 0.16s ease, border-color 0.16s ease, background 0.16s ease;
}

.fire-events-panel-header-action:hover {
  border-color: rgba(69, 221, 255, 0.64);
  background: rgba(69, 221, 255, 0.16);
  color: #ffffff;
}

.fire-events-panel-header-action span {
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 999px;
  background: rgba(69, 221, 255, 0.16);
  color: #45ddff;
  font-size: 11px;
  line-height: 18px;
  text-align: center;
  font-variant-numeric: tabular-nums;
}

.view-all-fire-events {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 44px;
  margin-top: 12px;
  border-top: 1px solid rgba(69, 221, 255, 0.2);
  color: #91c9e8;
  font-size: 12px;
  line-height: 1.4;
  text-decoration: none;
  transition: color 0.18s ease, background 0.18s ease;
}

.view-all-fire-events:hover {
  color: #e6f8ff;
  background: rgba(69, 221, 255, 0.06);
}

.view-all-fire-events span {
  color: #45ddff;
  font-size: 16px;
  line-height: 1;
}

.response-closure-panel {
  border-color: rgba(69, 221, 255, 0.24);
  background:
    linear-gradient(135deg, rgba(69, 221, 255, 0.06), rgba(66, 226, 157, 0.035)),
    rgba(8, 26, 40, 0.88);
}

.response-closure-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.response-closure-item {
  min-width: 0;
  padding: 10px 11px;
  border: 1px solid rgba(69, 221, 255, 0.14);
  border-radius: 8px;
  background: rgba(7, 22, 36, 0.64);
}

.response-closure-item span,
.response-closure-item small {
  display: block;
  color: #8fb8d0;
  font-size: 11px;
  line-height: 1.3;
}

.response-closure-item strong {
  display: block;
  margin: 5px 0 3px;
  color: #effbff;
  font-size: 18px;
  line-height: 1;
  font-variant-numeric: tabular-nums;
}

.response-closure-item.safe {
  border-color: rgba(66, 226, 157, 0.24);
}

.response-closure-item.warning {
  border-color: rgba(255, 191, 77, 0.24);
}

.response-closure-item.danger {
  border-color: rgba(255, 105, 120, 0.28);
}

.aircraft-status-panel,
.link-status-panel {
  position: relative;
  overflow: hidden;
}

.aircraft-status-panel::before,
.link-status-panel::before {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
  background:
    linear-gradient(135deg, rgba(69, 221, 255, 0.08), transparent 38%),
    radial-gradient(circle at 88% 12%, rgba(66, 226, 157, 0.08), transparent 28%);
}

.aircraft-status-overview {
  position: relative;
  display: grid;
  grid-template-columns: 0.78fr 1.22fr;
  gap: 8px;
  margin-bottom: 12px;
}

.aircraft-status-overview section {
  min-width: 0;
  padding: 11px 12px;
  border: 1px solid rgba(69, 221, 255, 0.16);
  border-radius: 8px;
  background: rgba(5, 23, 38, 0.58);
}

.aircraft-status-overview span,
.aircraft-node-group {
  display: block;
  color: #81abc6;
  font-size: 11px;
  line-height: 1.3;
}

.aircraft-status-overview strong {
  display: block;
  margin-top: 5px;
  color: #e9fbff;
  font-size: 16px;
  line-height: 1.25;
  font-weight: 800;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.aircraft-node-list {
  position: relative;
  display: grid;
  gap: 9px;
}

.aircraft-node-group {
  margin-top: 2px;
  color: #45ddff;
  font-weight: 700;
}

.aircraft-node-card {
  min-width: 0;
  padding: 13px 14px;
  border: 1px solid rgba(255, 105, 120, 0.2);
  border-radius: 8px;
  background:
    linear-gradient(90deg, rgba(255, 105, 120, 0.08), rgba(69, 221, 255, 0.03)),
    rgba(13, 35, 52, 0.78);
}

.aircraft-node-card.online {
  border-color: rgba(66, 226, 157, 0.22);
  background:
    linear-gradient(90deg, rgba(66, 226, 157, 0.08), rgba(69, 221, 255, 0.03)),
    rgba(13, 35, 52, 0.78);
}

.aircraft-node-main,
.aircraft-node-meta,
.link-node-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  min-width: 0;
}

.aircraft-node-main div {
  min-width: 0;
}

.aircraft-node-main span:first-child {
  display: block;
  color: #81abc6;
  font-size: 11px;
  line-height: 1.3;
}

.aircraft-node-main h4 {
  margin: 3px 0 0;
  color: #e9fbff;
  font-size: 15px;
  line-height: 1.25;
  font-weight: 800;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.aircraft-node-meter,
.link-node-progress {
  height: 5px;
  margin: 11px 0 9px;
  border-radius: 999px;
  background: rgba(119, 164, 190, 0.16);
  overflow: hidden;
}

.aircraft-node-meter span,
.link-node-progress span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #42e29d, #45ddff);
  box-shadow: 0 0 12px rgba(69, 221, 255, 0.35);
}

.aircraft-node-meta {
  color: #9abbd1;
  font-size: 12px;
  line-height: 1.35;
}

.aircraft-node-card p,
.link-node-card p {
  margin: 7px 0 0;
  color: #8fb1c9;
  font-size: 12px;
  line-height: 1.45;
  overflow-wrap: anywhere;
}

.link-status-panel {
  border-color: rgba(69, 221, 255, 0.24);
}

.link-status-timeline {
  position: relative;
  display: grid;
  gap: 10px;
  padding-left: 12px;
}

.link-status-timeline::before {
  content: '';
  position: absolute;
  top: 12px;
  bottom: 12px;
  left: 3px;
  width: 2px;
  border-radius: 999px;
  background: linear-gradient(180deg, rgba(69, 221, 255, 0.72), rgba(66, 226, 157, 0.24));
}

.link-node-card {
  position: relative;
  min-width: 0;
  padding: 13px 14px;
  border: 1px solid rgba(69, 221, 255, 0.14);
  border-radius: 8px;
  background: rgba(13, 35, 52, 0.78);
}

.link-node-card::before {
  content: '';
  position: absolute;
  left: -14px;
  top: 20px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #45ddff;
  box-shadow: 0 0 10px rgba(69, 221, 255, 0.6);
}

.link-node-card.primary {
  border-color: rgba(255, 191, 77, 0.24);
  background:
    linear-gradient(90deg, rgba(255, 191, 77, 0.08), rgba(69, 221, 255, 0.03)),
    rgba(13, 35, 52, 0.78);
}

.link-node-card.primary::before {
  background: #ffbf4d;
  box-shadow: 0 0 10px rgba(255, 191, 77, 0.6);
}

.link-node-head span {
  min-width: 0;
  color: #e9fbff;
  font-size: 14px;
  line-height: 1.25;
  font-weight: 800;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.link-node-head strong {
  flex: 0 0 auto;
  max-width: 92px;
  padding: 5px 8px;
  border: 1px solid rgba(103, 184, 255, 0.22);
  border-radius: 999px;
  background: rgba(103, 184, 255, 0.1);
  color: #d9f3ff;
  font-size: 11px;
  line-height: 1;
  text-align: center;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.link-node-id {
  margin-top: 8px;
  color: #8fb1c9;
  font-size: 11px;
  line-height: 1.35;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
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
  color: #d8f3ff;
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
  grid-template-rows: auto minmax(0, 1fr) auto;
  gap: 14px;
  min-height: 0;
}

.map-header {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}

.map-header > div:first-child {
  flex: 1 1 360px;
  min-width: 0;
}

.map-header-actions {
  display: flex;
  min-width: 0;
  max-width: 100%;
  flex: 0 1 auto;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
}

.visual-tabs {
  display: inline-flex;
  min-width: 0;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 6px;
  border-radius: 16px 16px 10px 10px;
  background: rgba(7, 18, 33, 0.62);
  border: 1px solid rgba(113, 179, 255, 0.14);
}

.visual-tab {
  appearance: none;
  border: 0;
  border-radius: 12px 12px 8px 8px;
  padding: 9px 16px;
  background: rgba(26, 59, 92, 0.88);
  color: #8fc8ff;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
  transition: background 0.2s ease, color 0.2s ease, transform 0.2s ease;
}

.visual-tab.active {
  background: linear-gradient(180deg, #3296ff 0%, #2174d9 100%);
  color: #ffffff;
  box-shadow: 0 10px 18px rgba(37, 116, 217, 0.24);
}

.visual-tab:hover {
  transform: translateY(-1px);
}

.visual-stage {
  display: grid;
  min-width: 0;
  min-height: 0;
  height: 100%;
}

.visual-stage > .map-stage,
.visual-stage > .livestream-stage {
  min-width: 0;
  min-height: 0;
  height: 100%;
}

.map-stage {
  position: relative;
  min-height: 0;
  height: 100%;
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

.livestream-stage {
  min-height: 0;
  min-width: 0;
  height: 100%;
  overflow: visible;
}

.dual-stream-stage {
  display: block;
}

.dual-stream-shell {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  gap: 14px;
  min-height: 0;
  min-width: 0;
  height: 100%;
}

.dual-stream-shell.fullscreen {
  position: relative;
  width: 100vw;
  height: 100vh;
  min-height: 100vh;
  padding: 6px;
  background:
    radial-gradient(circle at top left, rgba(69, 221, 255, 0.12), transparent 34%),
    linear-gradient(180deg, #06111f 0%, #02070d 100%);
  grid-template-rows: minmax(0, 1fr);
  gap: 0;
}

.dual-stream-shell.fullscreen .dual-stream-stage-head {
  position: absolute;
  top: 10px;
  left: 14px;
  right: 104px;
  max-width: calc(100% - 132px);
  z-index: 10;
  pointer-events: none;
}

.dual-stream-shell.fullscreen .dual-stream-stage-head > * {
  pointer-events: auto;
}

.dual-stream-shell.fullscreen .dual-stream-player-stage {
  width: 100%;
  height: 100%;
  aspect-ratio: auto;
  border-radius: 12px;
}

.dual-stream-shell.fullscreen .fire-monitor-flight-panel {
  left: 50%;
  right: auto;
  bottom: 10px;
  width: min(1120px, calc(100% - 36px));
  transform: translateX(-50%);
}

.dual-stream-shell.fullscreen .dual-stream-preview {
  top: auto;
  right: 18px;
  bottom: 18px;
  z-index: 8;
}

.dual-stream-stage-head {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
}

.fire-detect-btn {
  background: rgba(69, 221, 255, 0.12);
  border: 1px solid rgba(69, 221, 255, 0.35);
  color: #9be7ff;
  padding: 4px 12px;
  border-radius: 999px;
  font-size: 12px;
  white-space: nowrap;
  cursor: pointer;
  transition: background 0.15s ease;
}
.fire-detect-btn:hover:not(:disabled) {
  background: rgba(69, 221, 255, 0.22);
}
.fire-detect-btn.active {
  background: rgba(255, 99, 71, 0.18);
  border-color: rgba(255, 99, 71, 0.55);
  color: #ffb3a3;
}
.fire-detect-btn:disabled {
  opacity: 0.5;
  cursor: progress;
}

.dual-stream-player-stage {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 0;
  border-radius: 26px;
  background:
    radial-gradient(circle at top left, rgba(69, 221, 255, 0.18), transparent 34%),
    linear-gradient(180deg, rgba(16, 34, 56, 0.9), rgba(6, 16, 28, 0.96));
  border: 1px solid rgba(69, 221, 255, 0.16);
  overflow: hidden;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.04);
}

.dual-stream-player {
  height: 100%;
}

.dual-stream-player.primary {
  position: absolute;
  inset: 0;
  min-height: 0;
}

.dual-stream-player.preview {
  position: absolute;
  inset: 0;
  min-height: 100%;
}

.dual-stream-video {
  display: block;
  width: 100%;
  height: 100%;
  min-height: 100%;
  object-fit: contain;
  background: #02070d;
}

.dual-stream-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  gap: 10px;
  padding: 22px;
  background:
    linear-gradient(180deg, rgba(5, 14, 24, 0.28) 0%, rgba(5, 14, 24, 0.88) 100%);
}

.dual-stream-status-card {
  position: absolute;
  inset: auto 24px 64px auto;
  z-index: 5;
  width: min(420px, calc(100% - 48px));
  min-height: 0;
  padding: 16px 18px;
  color: #f4f8ff;
  pointer-events: none;
  border: 1px solid rgba(103, 184, 255, 0.24);
  border-radius: 14px;
  background:
    linear-gradient(180deg, rgba(13, 31, 51, 0.92), rgba(6, 17, 31, 0.92));
  box-shadow: 0 18px 36px rgba(0, 0, 0, 0.34), inset 0 1px 0 rgba(255, 255, 255, 0.05);
}

.dual-stream-status-card.loading {
  border-color: rgba(69, 221, 255, 0.34);
}

.dual-stream-status-card.error {
  border-color: rgba(255, 190, 105, 0.34);
}

.dual-stream-status-card .stream-label {
  letter-spacing: 0;
  text-transform: none;
}

.dual-stream-status-card .stream-value {
  margin: 8px 0 8px;
  font-size: 26px;
  line-height: 1.12;
}

.dual-stream-status-card.error .stream-value {
  color: #ffd38a;
}

.dual-stream-status-card p {
  margin: 0;
  color: #9fb0c5;
  font-size: 12px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}

.live-badge {
  position: absolute;
  top: 18px;
  left: 18px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  border-radius: 999px;
  background: rgba(8, 22, 38, 0.78);
  border: 1px solid rgba(255, 255, 255, 0.08);
  color: #f7fbff;
  font-size: 13px;
  font-weight: 700;
  z-index: 3;
}

.live-badge.idle {
  background: rgba(8, 22, 38, 0.62);
}

.dual-stream-fullscreen-btn {
  position: absolute;
  top: 18px;
  right: 18px;
  z-index: 6;
  height: 34px;
  min-width: 46px;
  padding: 0 12px;
  border: 1px solid rgba(95, 165, 255, 0.42);
  border-radius: 999px;
  background: rgba(8, 22, 38, 0.82);
  color: #f3f8ff;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
  box-shadow: 0 10px 22px rgba(0, 0, 0, 0.24);
}

.dual-stream-fullscreen-btn:hover {
  background: rgba(17, 52, 86, 0.9);
  border-color: rgba(95, 165, 255, 0.72);
}

.dual-stream-shell.fullscreen .dual-stream-fullscreen-btn {
  right: 18px;
}

.live-dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: #ff6172;
  box-shadow: 0 0 12px rgba(255, 97, 114, 0.72);
}

.stream-label {
  color: #8fa6c1;
  font-size: 12px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.stream-value {
  margin: 18px 0 10px;
  font-size: 38px;
  line-height: 1.05;
  font-weight: 700;
}

.dual-stream-reason p {
  margin: 0;
  color: #8fa6c1;
  font-size: 13px;
  line-height: 1.7;
}

/* 左下角飞行 HUD（与 WorkspaceLivestreamPanel 同款），不拦截点击 */
.flight-hud-overlay {
  position: absolute;
  left: 18px;
  bottom: 40px;
  z-index: 5;
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-height: 118px;
  padding: 14px 16px;
  background: rgba(0, 0, 0, 0.32);
  border-radius: 6px;
  color: #e6e9ef;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  text-shadow: 0 1px 3px rgba(0, 0, 0, 0.72);
  pointer-events: none;
  max-width: 85%;

  .flight-hud-row {
    display: flex;
    gap: 14px;
    align-items: center;
    flex-wrap: wrap;
  }

  .mode-row .mode {
    color: #53d492;
    font-weight: 600;
    letter-spacing: 0.3px;

    &.warn {
      color: #ff7875;
    }
  }

  .flight-hud-item {
    display: inline-flex;
    align-items: center;
    gap: 4px;

    &.battery {
      color: #ffd666;
    }

    &.fixed .dot {
      background: #52c41a;
    }

    &.unfixed .dot {
      background: #ff4d4f;
    }

    .dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      display: inline-block;
    }
  }
}

.dual-stream-preview {
  position: absolute;
  top: 18px;
  right: 82px;
  width: 210px;
  height: 132px;
  padding: 0;
  border: 2px solid rgba(95, 165, 255, 0.42);
  border-radius: 18px;
  overflow: hidden;
  background: rgba(6, 16, 28, 0.28);
  box-shadow: 0 12px 28px rgba(0, 0, 0, 0.22);
  cursor: default;
  z-index: 4;
}

.dual-stream-preview.clickable {
  cursor: pointer;
  border-color: rgba(95, 165, 255, 0.58);
}

.dual-stream-preview.switching {
  cursor: wait;
}

.dual-stream-preview:disabled {
  opacity: 1;
}

.dual-stream-switch-overlay {
  position: absolute;
  inset: 0;
  z-index: 5;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  background: rgba(3, 10, 18, 0.68);
  color: #f5f9ff;
  text-align: center;
  backdrop-filter: blur(2px);
}

.dual-stream-switch-overlay strong {
  font-size: 20px;
  line-height: 1.2;
}

.dual-stream-switch-overlay small {
  color: #b7c7d9;
  font-size: 12px;
}

.dual-stream-switch-spinner {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  border: 3px solid rgba(143, 200, 255, 0.28);
  border-top-color: #8fc8ff;
  animation: dual-stream-spin 0.8s linear infinite;
}

@keyframes dual-stream-spin {
  to {
    transform: rotate(360deg);
  }
}

.dual-stream-preview-overlay,
.dual-stream-preview-label {
  position: absolute;
  left: 0;
  right: 0;
}

.dual-stream-preview-overlay {
  inset: 0;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  gap: 4px;
  padding: 12px;
  background: linear-gradient(180deg, rgba(5, 14, 24, 0.08) 0%, rgba(5, 14, 24, 0.42) 100%);
  color: #f4f8ff;
  text-shadow: 0 1px 3px rgba(0, 0, 0, 0.76);
}

.dual-stream-preview-overlay.error {
  background: linear-gradient(180deg, rgba(47, 10, 17, 0.32) 0%, rgba(25, 8, 11, 0.92) 100%);
}

.dual-stream-preview-overlay.placeholder {
  background:
    radial-gradient(circle at 50% 20%, rgba(255, 122, 122, 0.08), transparent 35%),
    linear-gradient(180deg, rgba(38, 25, 28, 0.18) 0%, rgba(15, 12, 16, 0.5) 100%);
  border: 1px solid rgba(255, 122, 122, 0.14);
}

.dual-stream-preview-overlay strong {
  font-size: 18px;
  line-height: 1.1;
}

.dual-stream-preview-overlay.placeholder strong {
  color: #fff0f0;
}

.dual-stream-preview-overlay small,
.dual-stream-preview-label small {
  color: #b7c7d9;
  font-size: 11px;
  line-height: 1.4;
}

.preview-title {
  color: #9fb4ca;
  font-size: 11px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.dual-stream-preview-label {
  bottom: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 12px;
  background: linear-gradient(180deg, rgba(5, 14, 24, 0) 0%, rgba(5, 14, 24, 0.46) 100%);
  color: #ffffff;
  text-align: left;
  text-shadow: 0 1px 3px rgba(0, 0, 0, 0.76);
  pointer-events: none;
}

.dual-stream-reason {
  border-radius: 18px;
  padding: 14px 16px;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.06);
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

.visual-instrument-belt {
  margin-top: 0;
  padding: 10px 12px;
  border: 1px solid rgba(33, 179, 235, 0.46);
  border-radius: 8px;
  background:
    linear-gradient(90deg, rgba(0, 180, 255, 0.08), rgba(5, 18, 32, 0.08) 18%, rgba(0, 180, 255, 0.05)),
    rgba(5, 18, 30, 0.72);
  box-shadow: inset 0 1px 0 rgba(133, 226, 255, 0.1);
}

.instrument-section-list {
  display: grid;
  grid-template-columns: 1.12fr 1.28fr 1fr;
  border: 1px solid rgba(36, 176, 228, 0.24);
  border-radius: 6px;
  overflow: hidden;
}

.instrument-section {
  min-width: 0;
  padding: 13px 16px 12px;
  border-right: 1px solid rgba(77, 190, 235, 0.18);
  background: rgba(9, 29, 45, 0.46);
}

.instrument-section:last-child {
  border-right: 0;
}

.instrument-section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.instrument-section-head span {
  color: #17d7ff;
  font-size: 13px;
  font-weight: 700;
}

.instrument-section-head strong {
  min-width: 0;
  color: #dff6ff;
  font-size: 13px;
  line-height: 1.35;
  text-align: right;
}

.signal-row,
.instrument-footer-row {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}

.signal-dot-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #b9d8ed;
  font-size: 12px;
  white-space: nowrap;
}

.signal-dot-label i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #6d8191;
}

.status-online i,
.event-strip-item.status-online::before {
  background: #42e29d;
  box-shadow: 0 0 10px rgba(66, 226, 157, 0.65);
}

.status-warning i,
.event-strip-item.status-warning::before {
  background: #ffbf4d;
  box-shadow: 0 0 10px rgba(255, 191, 77, 0.55);
}

.status-offline i,
.event-strip-item.status-offline::before {
  background: #ff6172;
  box-shadow: 0 0 10px rgba(255, 97, 114, 0.5);
}

.signal-wave {
  display: flex;
  align-items: center;
  gap: 5px;
  height: 46px;
  margin: 8px 0;
  padding-inline: 2px;
  border-block: 1px solid rgba(88, 194, 235, 0.1);
}

.signal-wave i {
  flex: 1;
  min-width: 2px;
  max-width: 10px;
  border-radius: 999px;
  background: linear-gradient(180deg, rgba(69, 221, 255, 0.94), rgba(66, 226, 157, 0.42));
}

.instrument-footer-row {
  justify-content: space-between;
  color: #85a9c4;
  font-size: 11px;
}

.flight-readout-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px 14px;
}

.flight-readout {
  min-width: 0;
  padding-bottom: 7px;
  border-bottom: 1px solid rgba(96, 190, 231, 0.14);
}

.flight-readout span {
  display: block;
  color: #8eabc3;
  font-size: 11px;
  font-weight: 700;
}

.flight-readout strong {
  display: inline-block;
  margin-top: 4px;
  color: #e9fbff;
  font-size: 18px;
  line-height: 1.1;
}

.flight-readout small {
  margin-left: 4px;
  color: #7fa2bb;
  font-size: 10px;
}

.ai-observation-body p {
  margin: 12px 0 0;
  color: #94b4cb;
  font-size: 12px;
  line-height: 1.45;
}

.ai-tick-line {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  align-items: center;
  gap: 10px;
  min-height: 46px;
  border-block: 1px solid rgba(88, 194, 235, 0.1);
}

.ai-tick-line span {
  height: 2px;
  border-radius: 999px;
  background: rgba(125, 163, 188, 0.42);
}

.ai-tick-line .status-high {
  height: 6px;
  background: #ff6172;
  box-shadow: 0 0 10px rgba(255, 97, 114, 0.55);
}

.ai-tick-line .status-medium {
  height: 5px;
  background: #ffbf4d;
}

.ai-tick-line .status-low,
.ai-tick-line .status-online {
  height: 4px;
  background: #42e29d;
}

.instrument-event-strip {
  display: grid;
  grid-template-columns: 92px repeat(3, minmax(0, 1fr));
  align-items: center;
  gap: 16px;
  min-height: 36px;
  margin-top: 9px;
  padding-top: 8px;
  border-top: 1px solid rgba(66, 181, 231, 0.24);
}

.event-strip-title {
  color: #b9d8ed;
  font-size: 12px;
}

.event-strip-item {
  position: relative;
  min-width: 0;
  padding-left: 14px;
  color: #9fbcd2;
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.event-strip-item::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  transform: translateY(-50%);
  background: #6d8191;
}

.event-strip-item small {
  margin-right: 10px;
  color: #80a7c2;
  font-size: 12px;
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
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }

  .content-grid {
    grid-template-columns: 430px minmax(0, 1fr) 300px;
  }

  .footer-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 1280px) {
  .content-grid,
  .footer-grid {
    grid-template-columns: 1fr;
  }

  .content-grid {
    height: auto;
  }

  .visual-stage {
    min-height: clamp(420px, 52vh, 620px);
    height: auto;
  }

  .column {
    height: auto;
    max-height: none;
  }

  .column-scroll {
    overflow: visible;
    padding-right: 0;
  }

  .summary-grid,
  .small-metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .instrument-section-list,
  .instrument-event-strip {
    grid-template-columns: 1fr;
  }

  .instrument-section {
    border-right: 0;
    border-bottom: 1px solid rgba(77, 190, 235, 0.18);
  }

  .instrument-section:last-child {
    border-bottom: 0;
  }

  .cockpit-topbar {
    flex-direction: column;
  }

  .map-header {
    flex-direction: column;
    align-items: flex-start;
  }

  .map-header-actions {
    flex-direction: column;
    align-items: flex-start;
  }

  .dual-stream-preview {
    width: 172px;
    height: 108px;
  }
}
</style>
