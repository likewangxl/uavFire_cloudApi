<template>
  <div class="project-wayline-wrapper height-100">
    <a-spin :spinning="loading" :delay="300" tip="下载中" size="large">
    <div style="height: 50px; line-height: 50px; border-bottom: 1px solid #4f4f4f; font-weight: 450;">
      <a-row>
        <a-col :span="1"></a-col>
        <a-col :span="15">{{ isTaskRouteSelector ? '选择KMZ航线文件' : '航线库' }}</a-col>
        <a-col :span="8" v-if="importVisible" class="flex-row flex-justify-end flex-align-center">
          <a-upload
            name="file"
            :multiple="false"
            :before-upload="beforeUpload"
            :show-upload-list="false"
            :customRequest="uploadFile"
          >
            <a-button type="text" style="color: white;">
              <SelectOutlined />
            </a-button>
          </a-upload>
        </a-col>
      </a-row>
    </div>
    <div :style="{ height : height + 'px'}" class="scrollbar">
      <!-- Planned Wayline (click-to-fly), see WORK_RECORD.md §8 -->
      <div class="planning-panel" v-if="showPlanningTools">
        <div class="planning-panel-title">
          <span>规划航线（点选飞行）</span>
          <a-tooltip title="飞行器可选。可先布点并保存规划航线；生成文件不需要设备，下发准备前再选择或绑定目标机场/飞行器。">
            <QuestionCircleOutlined style="margin-left: 6px; color: #8c8c8c;" />
          </a-tooltip>
        </div>
        <div class="planning-row">
          <span class="planning-label">飞行器（可选）</span>
          <a-select
            size="small"
            style="width: 100%;"
            :value="selectedAircraftSn"
            :disabled="planningState.executing || planningState.active"
            placeholder="可不选；下发准备前再选择目标"
            @change="onSelectAircraft">
            <a-select-option v-for="d in onlineAircrafts" :key="d.sn" :value="d.sn">
              {{ d.callsign || d.sn }}<span v-if="d.aircraftModelKey"> · {{ d.aircraftModelKey }}</span>
            </a-select-option>
          </a-select>
        </div>
        <div class="planning-row planning-two-col">
          <div>
            <span class="planning-label">高度（米）</span>
            <a-input-number
              size="small"
              style="width: 100%;"
              :min="15"
              :step="1"
              :disabled="planningState.executing"
              v-model:value="planningState.defaultHeight" />
          </div>
          <div>
            <span class="planning-label">最大速度（米/秒）</span>
            <a-input-number
              size="small"
              style="width: 100%;"
              :min="2"
              :max="15"
              :step="1"
              :disabled="planningState.executing"
              v-model:value="planningState.maxSpeed" />
          </div>
        </div>
        <!-- L1: 全局 mission 配置 -->
        <div class="planning-row">
          <a-button
            size="small"
            type="link"
            style="padding: 0;"
            @click="advancedConfigOpen = !advancedConfigOpen">
            {{ advancedConfigOpen ? '▼ 高级配置' : '▶ 高级配置' }}
          </a-button>
        </div>
        <div class="planning-advanced" v-if="advancedConfigOpen">
          <div class="planning-row planning-two-col">
            <div>
              <span class="planning-label">完成动作</span>
              <a-select
                size="small"
                style="width: 100%;"
                :value="planningState.finishAction"
                placeholder="goHome (默认)"
                allow-clear
                :disabled="planningState.executing"
                @change="(v: any) => planningSetMissionConfig({ finishAction: v })">
                <a-select-option value="goHome">goHome 返航</a-select-option>
                <a-select-option value="autoLand">autoLand 原地降落</a-select-option>
                <a-select-option value="noAction">noAction 不动作</a-select-option>
                <a-select-option value="gotoFirstWaypoint">回到首点</a-select-option>
              </a-select>
            </div>
            <div>
              <span class="planning-label">RC 失联</span>
              <a-select
                size="small"
                style="width: 100%;"
                :value="planningState.exitOnRcLost"
                placeholder="goContinue (默认)"
                allow-clear
                :disabled="planningState.executing"
                @change="(v: any) => planningSetMissionConfig({ exitOnRcLost: v })">
                <a-select-option value="goContinue">goContinue 继续飞</a-select-option>
                <a-select-option value="executeLostAction">executeLostAction 执行失联动作</a-select-option>
              </a-select>
            </div>
          </div>
          <div class="planning-row planning-two-col">
            <div>
              <span class="planning-label">失联动作</span>
              <a-select
                size="small"
                style="width: 100%;"
                :value="planningState.rcLostAction"
                placeholder="goBack (默认)"
                allow-clear
                :disabled="planningState.executing"
                @change="(v: any) => planningSetMissionConfig({ rcLostAction: v })">
                <a-select-option value="hover">hover 悬停</a-select-option>
                <a-select-option value="goBack">goBack 返航</a-select-option>
                <a-select-option value="landing">landing 降落</a-select-option>
              </a-select>
            </div>
            <div>
              <span class="planning-label">起飞安全高度 (m)</span>
              <a-input-number
                size="small"
                style="width: 100%;"
                :min="2"
                :max="1500"
                :step="1"
                :value="planningState.takeoffSecurityHeight"
                placeholder="20 (默认)"
                :disabled="planningState.executing"
                @change="(v: any) => planningSetMissionConfig({ takeoffSecurityHeight: v })" />
            </div>
          </div>
          <div class="planning-row planning-two-col">
            <div>
              <span class="planning-label">转场速度 (m/s)</span>
              <a-input-number
                size="small"
                style="width: 100%;"
                :min="1"
                :max="15"
                :step="1"
                :value="planningState.globalTransitionalSpeed"
                placeholder="5 (默认)"
                :disabled="planningState.executing"
                @change="(v: any) => planningSetMissionConfig({ globalTransitionalSpeed: v })" />
            </div>
            <div>
              <span class="planning-label">RTH 高度 (m, 仅 dock)</span>
              <a-input-number
                size="small"
                style="width: 100%;"
                :min="2"
                :max="1500"
                :step="1"
                :value="planningState.rthAltitude"
                placeholder="飞机默认"
                :disabled="planningState.executing"
                @change="(v: any) => planningSetMissionConfig({ rthAltitude: v })" />
            </div>
          </div>
        </div>
        <div class="planning-row planning-actions">
          <a-button
            v-if="!planningState.active"
            size="small"
            type="primary"
            :disabled="planningState.executing"
            @click="onStartPlacing">
            开始布点
          </a-button>
          <a-button
            v-else
            size="small"
            @click="onStopPlacing">
            停止布点
          </a-button>
          <a-button
            size="small"
            :disabled="planningState.executing || planningState.waypoints.length === 0"
            @click="onClearWaypoints">
            清空
          </a-button>
        </div>
        <div class="planning-row">
          <span class="planning-label">航点（{{ planningState.waypoints.length }}）</span>
          <div class="planning-empty" v-if="planningState.waypoints.length === 0">
            暂无航点。请开始布点后在地图上点击添加。
          </div>
          <div class="planning-waypoints" v-else>
            <div
              v-for="(wp, idx) in planningState.waypoints"
              :key="wp.id"
              class="planning-wp"
              :class="{ active: planningState.executing && planningState.currentIndex === idx }">
              <div class="planning-wp-head">
                <span class="planning-wp-index">#{{ idx + 1 }}</span>
                <span class="planning-wp-coord">
                  {{ wp.wgsLat.toFixed(6) }}, {{ wp.wgsLng.toFixed(6) }}
                </span>
              </div>
              <div class="planning-wp-tail">
                <a-input-number
                  size="small"
                  :min="15"
                  :step="1"
                  :disabled="planningState.executing"
                  :value="wp.height"
                  @change="(v) => onUpdateHeight(wp.id, v)"
                  style="width: 70px;" />
                <span class="planning-wp-unit">m</span>
                <a-button size="small" :disabled="planningState.executing" @click="toggleWaypointExpand(wp.id)">
                  {{ expandedWaypointId === wp.id ? '收起' : '高级' }}
                </a-button>
                <a-button size="small" :disabled="planningState.executing || idx === 0" @click="onMove(wp.id, 'up')">↑</a-button>
                <a-button size="small" :disabled="planningState.executing || idx === planningState.waypoints.length - 1" @click="onMove(wp.id, 'down')">↓</a-button>
                <a-button size="small" danger :disabled="planningState.executing" @click="onRemove(wp.id)">✕</a-button>
              </div>
              <!-- L1 per-waypoint 高级编辑 -->
              <div class="planning-wp-advanced" v-if="expandedWaypointId === wp.id">
                <div class="planning-wp-row">
                  <span class="planning-wp-row-label">飞行速度</span>
                  <a-input-number
                    size="small"
                    :min="1"
                    :max="15"
                    :step="0.5"
                    :value="wp.speed"
                    placeholder="走全局"
                    :disabled="planningState.executing"
                    @change="(v: any) => planningUpdateField(wp.id, 'speed', v ?? undefined)"
                    style="width: 100%;" />
                </div>
                <div class="planning-wp-row planning-two-col">
                  <div>
                    <span class="planning-wp-row-label">云台俯仰°</span>
                    <a-input-number
                      size="small"
                      :min="-90"
                      :max="30"
                      :step="5"
                      :value="wp.gimbalPitch"
                      placeholder="0"
                      :disabled="planningState.executing"
                      @change="(v: any) => planningUpdateField(wp.id, 'gimbalPitch', v ?? undefined)"
                      style="width: 100%;" />
                  </div>
                  <div>
                    <span class="planning-wp-row-label">云台偏航°</span>
                    <a-input-number
                      size="small"
                      :min="-180"
                      :max="180"
                      :step="5"
                      :value="wp.gimbalYaw"
                      placeholder="跟随机头"
                      :disabled="planningState.executing"
                      @change="(v: any) => planningUpdateField(wp.id, 'gimbalYaw', v ?? undefined)"
                      style="width: 100%;" />
                  </div>
                </div>
                <div class="planning-wp-row planning-two-col">
                  <div>
                    <span class="planning-wp-row-label">朝向模式</span>
                    <a-select
                      size="small"
                      :value="wp.headingMode"
                      placeholder="followWayline"
                      allow-clear
                      :disabled="planningState.executing"
                      @change="(v: any) => planningUpdateField(wp.id, 'headingMode', v ?? undefined)"
                      style="width: 100%;">
                      <a-select-option value="followWayline">followWayline</a-select-option>
                      <a-select-option value="smoothTransition">smoothTransition</a-select-option>
                      <a-select-option value="fixed">fixed</a-select-option>
                      <a-select-option value="towardPOI">towardPOI</a-select-option>
                    </a-select>
                  </div>
                  <div>
                    <span class="planning-wp-row-label">朝向角°</span>
                    <a-input-number
                      size="small"
                      :min="-180"
                      :max="180"
                      :step="5"
                      :value="wp.headingAngle"
                      placeholder="0 (fixed 用)"
                      :disabled="planningState.executing || wp.headingMode !== 'fixed'"
                      @change="(v: any) => planningUpdateField(wp.id, 'headingAngle', v ?? undefined)"
                      style="width: 100%;" />
                  </div>
                </div>
                <div class="planning-wp-row planning-two-col">
                  <div>
                    <span class="planning-wp-row-label">转弯模式</span>
                    <a-select
                      size="small"
                      :value="wp.turnMode"
                      placeholder="默认平滑过弯"
                      allow-clear
                      :disabled="planningState.executing"
                      @change="(v: any) => planningUpdateField(wp.id, 'turnMode', v ?? undefined)"
                      style="width: 100%;">
                      <a-select-option value="coordinateTurn">协调转弯</a-select-option>
                      <a-select-option value="toPointAndStopWithDiscontinuityCurvature">停止转弯</a-select-option>
                      <a-select-option value="toPointAndStopWithContinuityCurvature">平滑停止</a-select-option>
                      <a-select-option value="toPointAndPassWithContinuityCurvature">平滑通过</a-select-option>
                    </a-select>
                  </div>
                  <div>
                    <span class="planning-wp-row-label">转弯阻尼 (m)</span>
                    <a-input-number
                      size="small"
                      :min="0"
                      :max="500"
                      :step="1"
                      :value="wp.turnDamping"
                      placeholder="10"
                      :disabled="planningState.executing"
                      @change="(v: any) => planningUpdateField(wp.id, 'turnDamping', v ?? undefined)"
                      style="width: 100%;" />
                  </div>
                </div>
                <div class="planning-wp-row">
                  <span class="planning-wp-row-label">动作 (执行到该航点时)</span>
                  <WaypointActionEditor
                    :actions="wp.actions"
                    @add="(fn: any) => planningAddAction(wp.id, fn)"
                    @remove="(i: number) => planningRemoveAction(wp.id, i)"
                    @updateParam="(i: number, k: string, v: any) => planningUpdateActionParam(wp.id, i, k, v)" />
                </div>
              </div>
            </div>
          </div>
        </div>
        <div class="planning-row planning-actions">
          <a-button
            v-if="!planningState.executing"
            size="small"
            type="primary"
            :disabled="planningState.waypoints.length === 0 || !selectedAircraftSn"
            @click="onStartExecution">
            执行
          </a-button>
          <a-button
            v-else
            size="small"
            danger
            @click="onStopExecution">
            停止执行
          </a-button>
        </div>
        <div class="planning-row planning-actions">
          <a-button
            size="small"
            :disabled="planningState.executing || planningState.waypoints.length === 0"
            @click="onSavePlannedWayline(false)">
            保存
          </a-button>
          <a-button
            size="small"
            :disabled="planningState.executing || planningState.waypoints.length === 0"
            @click="onSavePlannedWayline(true)">
            另存为
          </a-button>
        </div>
        <div class="planning-status" v-if="planningState.statusText">
          <span>{{ planningState.statusText }}</span>
        </div>
      </div>
      <div class="planning-section-gap" v-if="showPlanningTools"></div>
      <div class="planned-wayline-panel" v-if="showPlanningTools">
        <div class="planned-wayline-title">
          <span>已保存规划航线</span>
          <a-button size="small" type="link" :loading="plannedWaylinesLoading" @click="refreshPlannedWaylines">
            刷新
          </a-button>
        </div>
        <div class="planning-empty" v-if="!plannedWaylinesLoading && plannedWaylinesData.data.length === 0">
          暂无已保存规划航线。
        </div>
        <div v-else class="planned-wayline-list" @scroll="onPlannedWaylinesScroll">
          <div class="planned-wayline-card" v-for="record in plannedWaylinesData.data" :key="record.plannedWaylineId" @click="onPreviewPlannedWayline(record)">
            <div class="planned-wayline-card-head">
              <a-tooltip :title="record.name">
                <span class="planned-wayline-name">{{ record.name }}</span>
              </a-tooltip>
              <span class="planned-wayline-status">{{ formatPlannedWaylineStatus(record.status) }}</span>
            </div>
            <div class="planned-wayline-meta">
              <span>航点 {{ record.waypoints?.length || 0 }}</span>
              <span>高度 {{ formatNumber(record.defaultHeight) }} m</span>
              <span>速度 {{ formatNumber(record.maxSpeed) }} m/s</span>
            </div>
            <div class="planned-wayline-meta muted">
              <span>机型 {{ record.aircraftModelKey || '-' }}</span>
              <span>更新于 {{ formatTimestamp(record.updateTime) }}</span>
            </div>
            <!-- P2.c L2 任务监控:只在 task 已 prepare 后显示 -->
            <div class="planned-wayline-monitor" v-if="record.flightId" @click.stop>
              <WaylineMissionMonitor
                :workspace-id="monitorWorkspaceId"
                :record="record"
                @change="(updated: any) => onMissionMonitorChange(updated)" />
            </div>
            <div class="planned-wayline-actions">
              <a-button size="small" @click.stop="showPlannedWaylineDetail(record)">详情</a-button>
              <a-button size="small" :disabled="!canOverwritePlannedWayline(record)" @click.stop="onEditPlannedWayline(record)">编辑</a-button>
              <a-button
                v-for="action in getPlannedWaylineActions(record)"
                :key="action.key"
                size="small"
                :type="action.primary ? 'primary' : 'default'"
                :danger="action.danger"
                @click.stop="action.handler(record)">
                {{ action.label }}
              </a-button>
              <a-button size="small" danger @click.stop="onDeletePlannedWayline(record)">删除</a-button>
            </div>
          </div>
          <div class="planned-wayline-list-footer" v-if="plannedWaylinesLoading">加载中...</div>
          <div class="planned-wayline-list-footer" v-else-if="plannedWaylinesData.data.length > 0 && !plannedWaylinesCanRefresh">已加载全部</div>
        </div>
      </div>
      <div class="planning-section-gap" v-if="showPlanningTools"></div>
      <div id="data" class="height-100 uranus-scrollbar" v-if="waylinesData.data.length !== 0" @scroll="onScroll">
        <div v-for="wayline in waylinesData.data" :key="wayline.id">
          <div class="wayline-panel" style="padding-top: 5px;" @click="selectRoute(wayline)">
            <div class="title">
              <a-tooltip :title="wayline.name">
                <div class="pr10" style="width: 120px; white-space: nowrap; text-overflow: ellipsis; overflow: hidden;">{{ wayline.name }}</div>
              </a-tooltip>
              <div class="ml10"><UserOutlined /></div>
              <a-tooltip :title="wayline.user_name">
                <div class="ml5 pr10" style="width: 80px; white-space: nowrap; text-overflow: ellipsis; overflow: hidden;">{{ wayline.user_name }}</div>
              </a-tooltip>
              <div class="fz20">
                <a-dropdown>
                  <a style="color: white;">
                    <EllipsisOutlined />
                  </a>
                  <template #overlay>
                    <a-menu theme="dark" class="more" style="background: #3c3c3c;">
                      <a-menu-item @click="downloadWayline(wayline.id, wayline.name)">
                        <span>下载</span>
                      </a-menu-item>
                      <a-menu-item @click="showWaylineTip(wayline.id)">
                        <span>删除</span>
                      </a-menu-item>
                    </a-menu>
                  </template>
                </a-dropdown>
              </div>
            </div>
            <div class="ml10 mt5" style="color: hsla(0,0%,100%,0.65);">
              <span><RocketOutlined /></span>
              <span class="ml5">{{ DEVICE_NAME[wayline.drone_model_key] }}</span>
              <span class="ml10"><CameraFilled style="border-top: 1px solid; padding-top: -3px;" /></span>
              <span class="ml5" v-for="payload in wayline.payload_model_keys" :key="payload.id">
                {{ DEVICE_NAME[payload] }}
              </span>
            </div>
            <div class="mt5 ml10" style="color: hsla(0,0%,100%,0.35);">
              <span class="mr10">更新于 {{ new Date(wayline.update_time).toLocaleString() }}</span>
            </div>
          </div>
        </div>
      </div>
      <div v-else>
        <a-empty :description="isTaskRouteSelector ? '暂无可选KMZ航线文件' : undefined" :image-style="{ height: '60px', marginTop: '60px' }" />
      </div>
      <a-modal v-model:visible="deleteTip" width="450px" :closable="false" :maskClosable="false" centered :okButtonProps="{ danger: true }" @ok="deleteWayline">
          <p class="pt10 pl20" style="height: 50px;">航线文件删除后不可恢复，是否继续？</p>
          <template #title>
              <div class="flex-row flex-justify-center">
                  <span>删除</span>
              </div>
          </template>
      </a-modal>
      <a-modal
        v-model:visible="savePlannedWaylineModal.visible"
        width="520px"
        :confirmLoading="loading"
        :title="savePlannedWaylineModal.saveAs ? '另存为规划航线' : '保存规划航线'"
        @ok="confirmSavePlannedWayline">
        <div class="planned-wayline-form">
          <div class="planning-row">
            <span class="planning-label">航线名称</span>
            <a-input v-model:value="savePlannedWaylineModal.name" placeholder="请输入规划航线名称" />
          </div>
          <div class="planning-row planning-two-col">
            <div>
              <span class="planning-label">机型</span>
              <a-select
                size="small"
                style="width: 100%;"
                v-model:value="savePlannedWaylineModal.aircraftModelKey">
                <a-select-option v-for="model in PLANNED_WAYLINE_MODEL_OPTIONS" :key="model" :value="model">
                  {{ model }}
                </a-select-option>
              </a-select>
            </div>
            <div>
              <span class="planning-label">航点数</span>
              <a-input :value="String(savePlannedWaylineModal.waypointCount)" disabled />
            </div>
          </div>
          <div class="planning-row planning-two-col">
            <div>
              <span class="planning-label">默认高度（米）</span>
              <a-input-number size="small" style="width: 100%;" :min="15" :step="1" v-model:value="savePlannedWaylineModal.defaultHeight" />
            </div>
            <div>
              <span class="planning-label">最大速度（米/秒）</span>
              <a-input-number size="small" style="width: 100%;" :min="2" :max="15" :step="1" v-model:value="savePlannedWaylineModal.maxSpeed" />
            </div>
          </div>
        </div>
      </a-modal>
      <a-modal
        v-model:visible="plannedWaylineDetailVisible"
        width="760px"
        title="规划航线详情"
        :footer="null">
        <div v-if="selectedPlannedWayline" class="planned-wayline-detail">
          <div class="planned-wayline-detail-grid">
            <span>名称</span><strong>{{ selectedPlannedWayline.name }}</strong>
            <span>状态</span><strong>{{ formatPlannedWaylineStatus(selectedPlannedWayline.status) }}</strong>
            <span>机型</span><strong>{{ selectedPlannedWayline.aircraftModelKey || '-' }}</strong>
            <span>飞行器</span><strong>{{ selectedPlannedWayline.aircraftSn || '-' }}</strong>
            <span>网关</span><strong>{{ selectedPlannedWayline.gatewaySn || '-' }}</strong>
            <span>默认高度</span><strong>{{ formatNumber(selectedPlannedWayline.defaultHeight) }} m</strong>
            <span>最大速度</span><strong>{{ formatNumber(selectedPlannedWayline.maxSpeed) }} m/s</strong>
            <span>创建人</span><strong>{{ selectedPlannedWayline.creator || '-' }}</strong>
            <span>发布人</span><strong>{{ selectedPlannedWayline.publisher || '-' }}</strong>
            <span>发布时间</span><strong>{{ formatTimestamp(selectedPlannedWayline.publishTime) }}</strong>
            <span>KMZ地址</span><strong>{{ selectedPlannedWayline.kmzUrl || '-' }}</strong>
            <span>KMZ MD5</span><strong>{{ selectedPlannedWayline.kmzMd5 || '-' }}</strong>
            <span>任务ID</span><strong>{{ selectedPlannedWayline.flightId || '-' }}</strong>
            <span>目标机场</span><strong>{{ selectedPlannedWayline.dockSn || '-' }}</strong>
            <span>目标无人机</span><strong>{{ selectedPlannedWayline.droneSn || '-' }}</strong>
            <span>任务进度</span><strong>{{ selectedPlannedWayline.taskProgress ?? '-' }}</strong>
            <span>失败原因</span><strong>{{ selectedPlannedWayline.taskStatusReason || '-' }}</strong>
            <span>创建时间</span><strong>{{ formatTimestamp(selectedPlannedWayline.createTime) }}</strong>
            <span>更新时间</span><strong>{{ formatTimestamp(selectedPlannedWayline.updateTime) }}</strong>
          </div>
          <div class="planned-wayline-detail-actions">
            <a-button size="small" :disabled="!canOverwritePlannedWayline(selectedPlannedWayline)" @click="onEditPlannedWayline(selectedPlannedWayline)">编辑</a-button>
            <a-button size="small" @click="onSavePlannedWaylineAs(selectedPlannedWayline)">另存为</a-button>
            <a-button
              v-for="action in getPlannedWaylineActions(selectedPlannedWayline)"
              :key="action.key"
              size="small"
              :type="action.primary ? 'primary' : 'default'"
              :danger="action.danger"
              @click="action.handler(selectedPlannedWayline)">
              {{ action.label }}
            </a-button>
            <a-button size="small" danger @click="onDeletePlannedWayline(selectedPlannedWayline)">删除</a-button>
          </div>
          <div class="planned-wayline-waypoint-table">
            <div class="planned-wayline-waypoint-row head">
              <span>#</span><span>WGS84</span><span>GCJ02</span><span>高度</span>
            </div>
            <div class="planned-wayline-waypoint-row" v-for="wp in selectedPlannedWayline.waypoints" :key="wp.order">
              <span>{{ wp.order }}</span>
              <span>{{ wp.wgsLat.toFixed(6) }}, {{ wp.wgsLng.toFixed(6) }}</span>
              <span>{{ wp.gcjLat.toFixed(6) }}, {{ wp.gcjLng.toFixed(6) }}</span>
              <span>{{ formatNumber(wp.height) }} m</span>
            </div>
          </div>
        </div>
      </a-modal>
    </div>
    </a-spin>
  </div>
</template>

<script lang="ts" setup>
import { reactive } from '@vue/reactivity'
import { message, Modal } from 'ant-design-vue'
import { computed, onMounted, onUnmounted, onUpdated, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  createPlannedWayline,
  deletePlannedWayline,
  deleteWaylineFile,
  downloadWaylineFile,
  cancelPlannedWaylineTask,
  executePlannedWaylineTask,
  generatePlannedWaylineFile,
  getPlannedWayline,
  getPlannedWaylines,
  getWaylineFiles,
  importKmzFile,
  preparePlannedWaylineTask,
  updatePlannedWayline,
} from '/@/api/wayline'
import { ELocalStorageKey, ERouterName, EDeviceTypeName } from '/@/types'
import { EllipsisOutlined, RocketOutlined, CameraFilled, UserOutlined, SelectOutlined, QuestionCircleOutlined } from '@ant-design/icons-vue'
import { DEVICE_MODEL_KEY, DEVICE_NAME } from '/@/types/device'
import { useMyStore } from '/@/store'
import { CreatePlannedWaylineBody, PlannedWaypoint, PlannedWaylineRecord, PlannedWaylineStatus, WaylineFile } from '/@/types/wayline'
import { downloadFile } from '/@/utils/common'
import { IPage } from '/@/api/http/type'
import { CURRENT_CONFIG } from '/@/api/http/config'
import { load } from '@amap/amap-jsapi-loader'
import { getRoot } from '/@/root'
import { gcj02towgs84, wgs84togcj02 } from '/@/vendors/coordtransform'
import {
  getPlanningStateRaw,
  startPlanning as planningStart,
  stopPlanning as planningStop,
  clearWaypoints as planningClear,
  removeWaypoint as planningRemove,
  moveWaypoint as planningMove,
  updateWaypointHeight as planningUpdateHeight,
  updateWaypointField as planningUpdateField,
  setMissionConfig as planningSetMissionConfig,
  addWaypointAction as planningAddAction,
  removeWaypointAction as planningRemoveAction,
  updateWaypointActionParam as planningUpdateActionParam,
  startExecution as planningExecute,
  stopExecution as planningStopExec,
  setTargetAircraft as planningSetTarget,
  buildPlannedWaylineBody,
  loadPlannedWayline,
  previewPlannedWayline,
  resetPlanningDraft,
} from '/@/hooks/use-wayline-planning'
import { getDeviceTopo } from '/@/api/manage'
import WaypointActionEditor from '/@/components/WaypointActionEditor.vue'
import WaylineMissionMonitor from '/@/components/WaylineMissionMonitor.vue'

const loading = ref(false)
const store = useMyStore()
const route = useRoute()
const isTaskRouteSelector = computed(() => route.name === ERouterName.SELECT_PLAN)
const showPlanningTools = computed(() => !isTaskRouteSelector.value)

// ---------- Planned wayline (click-to-fly) ----------
const planningState = getPlanningStateRaw()
const selectedAircraftSn = ref('')
const advancedConfigOpen = ref(false)
const expandedWaypointId = ref<string | null>(null)
function toggleWaypointExpand (id: string) {
  expandedWaypointId.value = expandedWaypointId.value === id ? null : id
}
const monitorWorkspaceId = computed(() => localStorage.getItem(ELocalStorageKey.WorkspaceId) || '')
function onMissionMonitorChange (updated: PlannedWaylineRecord) {
  const idx = plannedWaylinesData.data.findIndex(r => r.plannedWaylineId === updated.plannedWaylineId)
  if (idx >= 0) plannedWaylinesData.data.splice(idx, 1, updated)
}

interface AircraftSummary {
  sn: string
  callsign: string
  gatewaySn: string
  aircraftModelKey: string
}

const onlineAircraftMap = reactive({} as Record<string, AircraftSummary>)
const onlineAircrafts = computed<AircraftSummary[]>(() => Object.values(onlineAircraftMap))
const plannedWaylinesLoading = ref(false)
const plannedWaylinesData = reactive({
  data: [] as PlannedWaylineRecord[]
})
const plannedWaylinesPagination: IPage = reactive({
  page: 1,
  total: -1,
  page_size: 10
})
const plannedWaylinesCanRefresh = ref(true)
const selectedPlannedWayline = ref<PlannedWaylineRecord | null>(null)
const plannedWaylineDetailVisible = ref(false)
const savePlannedWaylineModal = reactive({
  visible: false,
  saveAs: false,
  name: '',
  aircraftModelKey: '',
  defaultHeight: 80,
  maxSpeed: 10,
  waypointCount: 0,
})

let topoTimer: number | null = null

const AIRCRAFT_MODEL_KEY_MAP: Record<string, string> = {
  [DEVICE_MODEL_KEY.M30]: 'M30',
  [DEVICE_MODEL_KEY.M30T]: 'M30T',
  [DEVICE_MODEL_KEY.M3E]: 'M3E',
  [DEVICE_MODEL_KEY.M3T]: 'M3T',
  [DEVICE_MODEL_KEY.M300]: 'M300',
  [DEVICE_MODEL_KEY.M350]: 'M350',
  [DEVICE_MODEL_KEY.M3D]: 'M3D',
  [DEVICE_MODEL_KEY.M3TD]: 'M3TD',
  '0-99-0': 'M4E',
  '0-99-1': 'M4T',
  M30: 'M30',
  M30T: 'M30T',
  M3E: 'M3E',
  M3T: 'M3T',
  M300: 'M300',
  M350: 'M350',
  M3D: 'M3D',
  M3TD: 'M3TD',
  M4E: 'M4E',
  M4T: 'M4T',
}

const AIRCRAFT_MODEL_NAME_ORDER = ['M3TD', 'M30T', 'M4T', 'M3T', 'M350', 'M300', 'M30', 'M3E', 'M3D', 'M4E']
const PLANNED_WAYLINE_MODEL_OPTIONS = ['M30T', 'M30', 'M3T', 'M3E', 'M3TD', 'M3D', 'M350', 'M300']
const DEFAULT_PLANNED_WAYLINE_MODEL = 'M30T'

function normalizeAircraftModelKey (raw: any): string {
  if (raw === undefined || raw === null) return ''
  const text = String(raw).trim()
  if (!text) return ''
  const upper = text.toUpperCase().replace(/\s+/g, '')
  return AIRCRAFT_MODEL_KEY_MAP[text] || AIRCRAFT_MODEL_KEY_MAP[upper] || ''
}

function inferAircraftModelKey (child: any): string {
  const candidates = [
    child?.device_model_key,
    child?.device_model?.key,
    child?.device_model?.device,
    child?.device_model?.value,
    child?.device_model,
    child?.domain !== undefined && child?.type !== undefined && child?.sub_type !== undefined
      ? `${child.domain}-${child.type}-${child.sub_type}`
      : '',
    child?.domain !== undefined && child?.type !== undefined && child?.subType !== undefined
      ? `${child.domain}-${child.type}-${child.subType}`
      : '',
  ]

  for (const candidate of candidates) {
    const aircraftModelKey = normalizeAircraftModelKey(candidate)
    if (aircraftModelKey) return aircraftModelKey
  }

  const label = `${child?.device_name || ''} ${child?.nickname || ''} ${child?.callsign || ''}`.toUpperCase().replace(/\s+/g, '')
  return AIRCRAFT_MODEL_NAME_ORDER.find(name => label.includes(name)) || ''
}

function normalizePlannedWaylineModel (model: string): string {
  const normalized = normalizeAircraftModelKey(model)
  return PLANNED_WAYLINE_MODEL_OPTIONS.includes(normalized) ? normalized : DEFAULT_PLANNED_WAYLINE_MODEL
}

function formatSafePlannedWaylineTimestamp (date: Date): string {
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}${pad(date.getMonth() + 1)}${pad(date.getDate())} ${pad(date.getHours())}${pad(date.getMinutes())}${pad(date.getSeconds())}`
}

function sanitizeDjiWaylineName (name: string, fallback = '规划航线'): string {
  const sanitized = String(name || '')
    .trim()
    .replace(/[<>:"/|?*._\\]+/g, '-')
    .replace(/\s+/g, ' ')
    .replace(/^-+|-+$/g, '')
    .trim()
  return sanitized || fallback
}

function finiteSaveNumber (value: unknown): number | null {
  if (value === null || value === undefined || value === '') return null
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? numberValue : null
}

function positiveSaveNumber (value: unknown, fallback: number): number {
  const numberValue = finiteSaveNumber(value)
  return numberValue !== null && numberValue > 0 ? numberValue : fallback
}

function buildPagePlannedWaypointBody (wp: any, idx: number, defaultHeight: number): PlannedWaypoint {
  let gcjLng = finiteSaveNumber(wp?.gcjLng ?? wp?.lng ?? wp?.longitude)
  let gcjLat = finiteSaveNumber(wp?.gcjLat ?? wp?.lat ?? wp?.latitude)
  let wgsLng = finiteSaveNumber(wp?.wgsLng)
  let wgsLat = finiteSaveNumber(wp?.wgsLat)

  if ((wgsLng === null || wgsLat === null) && gcjLng !== null && gcjLat !== null) {
    const [convertedWgsLng, convertedWgsLat] = gcj02towgs84(gcjLng, gcjLat) as [number, number]
    wgsLng = finiteSaveNumber(convertedWgsLng)
    wgsLat = finiteSaveNumber(convertedWgsLat)
  }
  if ((gcjLng === null || gcjLat === null) && wgsLng !== null && wgsLat !== null) {
    const [convertedGcjLng, convertedGcjLat] = wgs84togcj02(wgsLng, wgsLat) as [number, number]
    gcjLng = finiteSaveNumber(convertedGcjLng)
    gcjLat = finiteSaveNumber(convertedGcjLat)
  }
  if (gcjLng === null || gcjLat === null || wgsLng === null || wgsLat === null) {
    throw new Error(`waypoint ${idx + 1} coordinates required`)
  }

  return {
    order: idx + 1,
    gcjLng,
    gcjLat,
    wgsLng,
    wgsLat,
    height: positiveSaveNumber(wp?.height, defaultHeight),
    // L1 per-航点定制字段透传 (null 时由后端默认值兜底)
    speed: wp?.speed ?? undefined,
    gimbalPitch: wp?.gimbalPitch ?? undefined,
    gimbalYaw: wp?.gimbalYaw ?? undefined,
    headingMode: wp?.headingMode ?? undefined,
    headingAngle: wp?.headingAngle ?? undefined,
    poiLng: wp?.poiLng ?? undefined,
    poiLat: wp?.poiLat ?? undefined,
    poiAlt: wp?.poiAlt ?? undefined,
    turnMode: wp?.turnMode ?? undefined,
    turnDamping: wp?.turnDamping ?? undefined,
    actions: wp?.actions && wp.actions.length > 0 ? wp.actions : undefined,
  }
}

function buildPagePlannedWaylineBody (name: string, aircraftModelKey: string): CreatePlannedWaylineBody {
  const defaultHeight = positiveSaveNumber(savePlannedWaylineModal.defaultHeight, 30)
  const maxSpeed = positiveSaveNumber(savePlannedWaylineModal.maxSpeed, 5)
  return {
    name,
    aircraftModelKey: normalizePlannedWaylineModel(aircraftModelKey),
    gatewaySn: planningState.gatewaySn || '',
    aircraftSn: planningState.aircraftSn || '',
    defaultHeight,
    maxSpeed,
    // L1 全局 mission 配置 (undefined 时由后端 entity 默认值兜底)
    finishAction: planningState.finishAction ?? undefined,
    exitOnRcLost: planningState.exitOnRcLost ?? undefined,
    rcLostAction: planningState.rcLostAction ?? undefined,
    takeoffSecurityHeight: planningState.takeoffSecurityHeight ?? undefined,
    globalTransitionalSpeed: planningState.globalTransitionalSpeed ?? undefined,
    rthAltitude: planningState.rthAltitude ?? undefined,
    waypoints: planningState.waypoints.map((wp, idx) => buildPagePlannedWaypointBody(wp, idx, defaultHeight)),
  }
}

async function refreshOnlineAircrafts () {
  const workspaceIdForPlanning = localStorage.getItem(ELocalStorageKey.WorkspaceId) || ''
  if (!workspaceIdForPlanning) return
  try {
    const res = await getDeviceTopo(workspaceIdForPlanning)
    if (res.code !== 0) return
    const seen = new Set<string>()
    res.data.forEach((gateway: any) => {
      // Only consider Gateway domain (RC / RC2); Docks are handled separately elsewhere.
      if (gateway.domain !== EDeviceTypeName.Gateway) return
      if (!gateway.status) return
      const child = gateway.children
      if (!child?.device_sn) return
      seen.add(child.device_sn)
      onlineAircraftMap[child.device_sn] = {
        sn: child.device_sn,
        callsign: child.nickname || child.device_name || child.device_sn,
        gatewaySn: gateway.device_sn,
        aircraftModelKey: inferAircraftModelKey(child),
      }
    })
    // Drop entries that have gone offline.
    Object.keys(onlineAircraftMap).forEach(sn => {
      if (!seen.has(sn)) delete onlineAircraftMap[sn]
    })
    // Keep the selection valid.
    if (selectedAircraftSn.value && !onlineAircraftMap[selectedAircraftSn.value]) {
      if (planningState.active && planningState.aircraftSn === selectedAircraftSn.value) {
        planningStop()
      }
      if (!planningState.executing && planningState.aircraftSn === selectedAircraftSn.value) {
        planningSetTarget('', '')
      }
      selectedAircraftSn.value = ''
    }
  } catch (e) {
    // silent — topo will retry.
  }
}

function onSelectAircraft (sn: string) {
  selectedAircraftSn.value = sn
  const summary = onlineAircraftMap[sn]
  if (summary) {
    planningSetTarget(summary.gatewaySn, summary.sn)
  }
}

function onStartPlacing () {
  const summary = onlineAircraftMap[selectedAircraftSn.value]
  if (summary) {
    planningStart(summary.gatewaySn, summary.sn)
  } else {
    planningStart('', '')
  }
}

function onStopPlacing () {
  planningStop()
}

function onClearWaypoints () {
  planningClear()
}

function onRemove (id: string) {
  planningRemove(id)
}

function onMove (id: string, direction: 'up' | 'down') {
  planningMove(id, direction)
}

function onUpdateHeight (id: string, value: number | string | null) {
  const n = typeof value === 'number' ? value : Number(value)
  if (Number.isFinite(n)) planningUpdateHeight(id, n)
}

async function onStartExecution () {
  const summary = onlineAircraftMap[selectedAircraftSn.value]
  if (!summary) {
    message.warning('请先选择在线飞行器。')
    return
  }
  planningSetTarget(summary.gatewaySn, summary.sn)
  await planningExecute()
}

async function onStopExecution () {
  await planningStopExec()
}

function validatePlannedWaylineSave (): AircraftSummary {
  let summary = onlineAircraftMap[selectedAircraftSn.value]
  if (!summary && planningState.gatewaySn && planningState.aircraftSn && (planningState as any).aircraftModelKey) {
    summary = {
      sn: planningState.aircraftSn,
      callsign: planningState.aircraftSn,
      gatewaySn: planningState.gatewaySn,
      aircraftModelKey: (planningState as any).aircraftModelKey,
    }
  }
  if (planningState.waypoints.length === 0) {
    message.warning('请至少添加一个航点。')
    throw new Error('waypoints required')
  }
  const fallbackModel = normalizePlannedWaylineModel(savePlannedWaylineModal.aircraftModelKey || (planningState as any).aircraftModelKey || DEFAULT_PLANNED_WAYLINE_MODEL)
  const target = summary || {
    sn: planningState.aircraftSn || '',
    callsign: planningState.aircraftSn || '',
    gatewaySn: planningState.gatewaySn || '',
    aircraftModelKey: fallbackModel,
  }
  target.aircraftModelKey = normalizePlannedWaylineModel(target.aircraftModelKey || fallbackModel)
  if (!target.aircraftModelKey) {
    message.warning('请选择机型后再保存规划航线。')
    throw new Error('aircraft model required')
  }
  planningSetTarget(target.gatewaySn, target.sn)
  ;(planningState as any).aircraftModelKey = target.aircraftModelKey
  return target
}

function onSavePlannedWayline (saveAs: boolean) {
  openSavePlannedWaylineModal(saveAs)
}

function openSavePlannedWaylineModal (saveAs: boolean) {
  let summary: AircraftSummary
  try {
    summary = validatePlannedWaylineSave()
  } catch (e) {
    return
  }

  const editingId = (planningState as any).editingPlannedWaylineId
  const editingRecord = plannedWaylinesData.data.find(record => record.plannedWaylineId === editingId)
  if (!saveAs && editingId && !editingRecord) {
    message.warning('当前编辑的规划航线不在列表中，请刷新后重试或使用另存为。')
    return
  }
  if (!saveAs && editingRecord && !canOverwritePlannedWayline(editingRecord)) {
    message.warning('当前状态的规划航线不能直接覆盖，请使用另存为。')
    return
  }
  const editingName = editingRecord?.name || ''
  const defaultName = saveAs
    ? `${sanitizeDjiWaylineName(editingName || '规划航线')} 副本`
    : sanitizeDjiWaylineName(editingName || `规划航线 ${formatSafePlannedWaylineTimestamp(new Date())}`)
  savePlannedWaylineModal.visible = true
  savePlannedWaylineModal.saveAs = saveAs
  savePlannedWaylineModal.name = defaultName
  savePlannedWaylineModal.aircraftModelKey = normalizePlannedWaylineModel(summary.aircraftModelKey)
  savePlannedWaylineModal.defaultHeight = planningState.defaultHeight
  savePlannedWaylineModal.maxSpeed = planningState.maxSpeed
  savePlannedWaylineModal.waypointCount = planningState.waypoints.length
}

async function confirmSavePlannedWayline () {
  let summary: AircraftSummary
  try {
    summary = validatePlannedWaylineSave()
  } catch (e) {
    return
  }
  const name = sanitizeDjiWaylineName(savePlannedWaylineModal.name)
  if (!name) {
    message.warning('请输入规划航线名称。')
    return
  }

  const editingId = (planningState as any).editingPlannedWaylineId
  planningState.defaultHeight = Number(savePlannedWaylineModal.defaultHeight)
  planningState.maxSpeed = Number(savePlannedWaylineModal.maxSpeed)
  const aircraftModelKey = normalizePlannedWaylineModel(savePlannedWaylineModal.aircraftModelKey || summary.aircraftModelKey)
  let body
  try {
    body = buildPagePlannedWaylineBody(name, aircraftModelKey)
    try {
      buildPlannedWaylineBody(name, aircraftModelKey)
    } catch (e) {
      console.warn('planned wayline hook payload normalization failed; page payload was rebuilt from current waypoints', e)
    }
  } catch (e) {
    message.warning('航点坐标异常，请清空后重新布点。')
    return
  }
  loading.value = true
  try {
    const res = !savePlannedWaylineModal.saveAs && editingId
      ? await updatePlannedWayline(workspaceId, editingId, body)
      : await createPlannedWayline(workspaceId, body)
    if (res.code !== 0) return
    message.success(savePlannedWaylineModal.saveAs || !editingId ? '规划航线已保存' : '规划航线已更新')
    savePlannedWaylineModal.visible = false
    resetPlanningDraft()
    selectedAircraftSn.value = ''
    if (res.data?.plannedWaylineId) {
      previewPlannedWayline(res.data)
    }
    await refreshPlannedWaylines(true)
  } finally {
    loading.value = false
  }
}

async function refreshPlannedWaylines (reset = false) {
  if (plannedWaylinesLoading.value) return
  if (reset) {
    plannedWaylinesPagination.page = 1
    plannedWaylinesPagination.total = -1
    plannedWaylinesData.data = []
    plannedWaylinesCanRefresh.value = true
  }
  if (!plannedWaylinesCanRefresh.value) return
  plannedWaylinesLoading.value = true
  try {
    const res = await getPlannedWaylines(workspaceId, {
      page: plannedWaylinesPagination.page,
      total: plannedWaylinesPagination.total,
      page_size: plannedWaylinesPagination.page_size
    })
    if (res.code !== 0) return
    const list = res.data?.list || []
    plannedWaylinesData.data = reset ? list : [...plannedWaylinesData.data, ...list]
    plannedWaylinesPagination.total = res.data?.pagination?.total ?? list.length
    plannedWaylinesPagination.page = res.data?.pagination?.page ?? plannedWaylinesPagination.page
    plannedWaylinesCanRefresh.value = Math.ceil(plannedWaylinesPagination.total / plannedWaylinesPagination.page_size) > plannedWaylinesPagination.page
  } finally {
    plannedWaylinesLoading.value = false
  }
}

function onPlannedWaylinesScroll (e: any) {
  const element = e.srcElement
  if (element.scrollTop + element.clientHeight >= element.scrollHeight - 5 && plannedWaylinesCanRefresh.value && !plannedWaylinesLoading.value) {
    plannedWaylinesPagination.page++
    refreshPlannedWaylines()
  }
}

function onPreviewPlannedWayline (record: PlannedWaylineRecord) {
  resetPlanningDraft()
  selectedAircraftSn.value = ''
  previewPlannedWayline(record)
}

function onEditPlannedWayline (record: PlannedWaylineRecord) {
  loadPlannedWayline(record)
  selectedAircraftSn.value = record.aircraftSn
  const summary = onlineAircraftMap[record.aircraftSn]
  if (summary) {
    planningSetTarget(summary.gatewaySn, summary.sn)
  }
}

function onSavePlannedWaylineAs (record: PlannedWaylineRecord | null) {
  if (!record) return
  onEditPlannedWayline(record)
  openSavePlannedWaylineModal(true)
}

async function runPlannedWaylineAction (
  record: PlannedWaylineRecord,
  action: () => Promise<any>,
  successText: string,
) {
  loading.value = true
  try {
    const res = await action()
    if (res.code !== 0) return
    message.success(successText)
    await refreshPlannedWaylines(true)
    if (selectedPlannedWayline.value?.plannedWaylineId === record.plannedWaylineId) {
      await showPlannedWaylineDetail(record)
    }
  } finally {
    loading.value = false
  }
}

async function onGeneratePlannedWaylineFile (record: PlannedWaylineRecord) {
  Modal.confirm({
    title: '生成航线文件',
    content: `确认生成“${record.name}”的 KMZ 航线文件？生成航线文件后不可直接覆盖，只能另存为新规划航线。`,
    okText: '生成',
    cancelText: '取消',
    async onOk () {
      await runPlannedWaylineAction(
        record,
        () => generatePlannedWaylineFile(workspaceId, record.plannedWaylineId),
        '航线文件已生成')
      refreshWaylineFiles(true)
    }
  })
}

async function onPreparePlannedWaylineTask (record: PlannedWaylineRecord) {
  // Agent 路径 (M4T + RC,无机场) 不需要 dockSn,后端按 dockSn 是否非空自动路由。
  // 如果用户绑定了机场就走 dock 路径;否则走 agent 把 KMZ 推到 RC + MSDK。
  const body: any = {
    droneSn: record.aircraftSn,
    executeTime: 0,
    taskType: 'IMMEDIATE',
  }
  // 仅当 record.dockSn 存在 (用户显式选了机场) 才透传,gatewaySn 是 RC 的 SN,不能当 dockSn 用
  if (record.dockSn) {
    body.dockSn = record.dockSn
  }
  await runPlannedWaylineAction(
    record,
    () => preparePlannedWaylineTask(workspaceId, record.plannedWaylineId, body),
    '航线任务已下发准备')
}

async function onExecutePlannedWaylineTask (record: PlannedWaylineRecord) {
  await runPlannedWaylineAction(
    record,
    () => executePlannedWaylineTask(workspaceId, record.plannedWaylineId),
    '航线任务已开始执行')
}

async function onCancelPlannedWaylineTask (record: PlannedWaylineRecord) {
  await runPlannedWaylineAction(
    record,
    () => cancelPlannedWaylineTask(workspaceId, record.plannedWaylineId),
    '航线任务已取消')
}

async function onDeletePlannedWayline (record: PlannedWaylineRecord) {
  Modal.confirm({
    title: '删除规划航线',
    content: `确认删除“${record.name}”？状态：${formatPlannedWaylineStatus(record.status)}，航点：${record.waypoints?.length || 0} 个。`,
    okText: '删除',
    cancelText: '取消',
    okButtonProps: { danger: true },
    async onOk () {
      loading.value = true
      try {
        const res = await deletePlannedWayline(workspaceId, record.plannedWaylineId)
        if (res.code !== 0) return
        message.success('规划航线已删除')
        if ((planningState as any).editingPlannedWaylineId === record.plannedWaylineId) {
          resetPlanningDraft()
          selectedAircraftSn.value = ''
        }
        if (selectedPlannedWayline.value?.plannedWaylineId === record.plannedWaylineId) {
          plannedWaylineDetailVisible.value = false
          selectedPlannedWayline.value = null
        }
        await refreshPlannedWaylines(true)
      } finally {
        loading.value = false
      }
    }
  })
}

async function showPlannedWaylineDetail (record: PlannedWaylineRecord) {
  loading.value = true
  try {
    const res = await getPlannedWayline(workspaceId, record.plannedWaylineId)
    if (res.code !== 0 || !res.data) return
    selectedPlannedWayline.value = res.data
    plannedWaylineDetailVisible.value = true
  } finally {
    loading.value = false
  }
}

function formatNumber (value: unknown): string {
  const n = Number(value)
  return Number.isFinite(n) ? String(n) : '-'
}

function formatTimestamp (value: unknown): string {
  const n = Number(value)
  return Number.isFinite(n) && n > 0 ? new Date(n).toLocaleString() : '-'
}

function normalizePlannedWaylineStatus (recordOrStatus: PlannedWaylineRecord | string): string {
  const raw = typeof recordOrStatus === 'string' ? recordOrStatus : (recordOrStatus.taskStatus || recordOrStatus.status)
  return (raw || PlannedWaylineStatus.DRAFT).toLowerCase()
}

function formatPlannedWaylineStatus (status: string): string {
  const labels: Record<string, string> = {
    [PlannedWaylineStatus.DRAFT]: '草稿',
    [PlannedWaylineStatus.FILE_GENERATED]: '航线文件已生成',
    [PlannedWaylineStatus.PUBLISHING]: '下发中',
    [PlannedWaylineStatus.PREPARED]: '已准备',
    [PlannedWaylineStatus.EXECUTING]: '执行中',
    [PlannedWaylineStatus.COMPLETED]: '已完成',
    [PlannedWaylineStatus.FAILED]: '失败',
    [PlannedWaylineStatus.CANCELED]: '已取消',
    published: '已发布',
  }
  return labels[normalizePlannedWaylineStatus(status)] || status || '草稿'
}

function canOverwritePlannedWayline (record: PlannedWaylineRecord): boolean {
  return normalizePlannedWaylineStatus(record) === PlannedWaylineStatus.DRAFT && !record.publishedWaylineId
}

function getPlannedWaylineActions (record: PlannedWaylineRecord) {
  const status = normalizePlannedWaylineStatus(record)
  if (status === PlannedWaylineStatus.DRAFT) {
    return [{ key: 'generate', label: '生成航线文件', primary: true, danger: false, handler: onGeneratePlannedWaylineFile }]
  }
  if (status === PlannedWaylineStatus.FILE_GENERATED) {
    return [{ key: 'prepare', label: '下发准备', primary: true, danger: false, handler: onPreparePlannedWaylineTask }]
  }
  if (status === PlannedWaylineStatus.PREPARED) {
    return [
      { key: 'execute', label: '开始执行', primary: true, danger: false, handler: onExecutePlannedWaylineTask },
      { key: 'cancel', label: '取消任务', primary: false, danger: true, handler: onCancelPlannedWaylineTask },
    ]
  }
  if (status === PlannedWaylineStatus.PUBLISHING || status === PlannedWaylineStatus.EXECUTING) {
    return [{ key: 'cancel', label: '取消任务', primary: false, danger: true, handler: onCancelPlannedWaylineTask }]
  }
  if (status === PlannedWaylineStatus.FAILED || status === PlannedWaylineStatus.CANCELED) {
    return [{
      key: record.publishedWaylineId ? 'prepare' : 'generate',
      label: record.publishedWaylineId ? '下发准备' : '生成航线文件',
      primary: true,
      danger: false,
      handler: record.publishedWaylineId ? onPreparePlannedWaylineTask : onGeneratePlannedWaylineFile,
    }]
  }
  return []
}
const pagination :IPage = {
  page: 1,
  total: -1,
  page_size: 10
}

const waylinesData = reactive({
  data: [] as WaylineFile[]
})

const root = getRoot()
const workspaceId = localStorage.getItem(ELocalStorageKey.WorkspaceId)!
const deleteTip = ref(false)
const deleteWaylineId = ref<string>('')
const canRefresh = ref(true)
const importVisible = computed(() => route.name === ERouterName.WAYLINE)
const height = ref()

onMounted(() => {
  const parent = document.getElementsByClassName('scrollbar').item(0)?.parentNode as HTMLDivElement
  height.value = document.body.clientHeight - parent.firstElementChild!.clientHeight
  getWaylines()
  if (showPlanningTools.value) {
    refreshPlannedWaylines(true)
  }

  const key = setInterval(() => {
    const data = document.getElementById('data')?.lastElementChild as HTMLDivElement
    if (pagination.total === 0 || Math.ceil(pagination.total / pagination.page_size) <= pagination.page || height.value <= data?.clientHeight + data?.offsetTop) {
      clearInterval(key)
      return
    }
    pagination.page++
    getWaylines()
  }, 1000)

  if (showPlanningTools.value) {
    // Populate online aircraft list for planning target selection.
    selectedAircraftSn.value = planningState.aircraftSn || ''
    refreshOnlineAircrafts()
    topoTimer = window.setInterval(refreshOnlineAircrafts, 5000)
  }
})

onUnmounted(() => {
  if (topoTimer !== null) {
    window.clearInterval(topoTimer)
    topoTimer = null
  }
  if (showPlanningTools.value) {
    if (planningState.executing) {
      if (planningState.active) planningStop()
      return
    }
    resetPlanningDraft()
    selectedAircraftSn.value = ''
  }
})

function getWaylines () {
  refreshWaylineFiles()
}

function refreshWaylineFiles (reset = false) {
  if (reset) {
    pagination.total = 0
    pagination.page = 1
    waylinesData.data = []
  }
  if (!canRefresh.value) {
    return
  }
  canRefresh.value = false
  getWaylineFiles(workspaceId, {
    page: pagination.page,
    page_size: pagination.page_size,
    order_by: 'update_time desc'
  }).then(res => {
    if (res.code !== 0) {
      return
    }
    waylinesData.data = [...waylinesData.data, ...res.data.list]
    pagination.total = res.data.pagination.total
    pagination.page = res.data.pagination.page
  }).finally(() => {
    canRefresh.value = true
  })
}

function showWaylineTip (waylineId: string) {
  deleteWaylineId.value = waylineId
  deleteTip.value = true
}

function deleteWayline () {
  deleteWaylineFile(workspaceId, deleteWaylineId.value).then(res => {
    if (res.code === 0) {
      message.success('航线文件已删除')
    }
    deleteWaylineId.value = ''
    deleteTip.value = false
    refreshWaylineFiles(true)
  })
}

function downloadWayline (waylineId: string, fileName: string) {
  loading.value = true
  downloadWaylineFile(workspaceId, waylineId).then(res => {
    if (!res) {
      return
    }
    const data = new Blob([res], { type: 'application/zip' })
    downloadFile(data, fileName + '.kmz')
  }).finally(() => {
    loading.value = false
  })
}

function selectRoute (wayline: WaylineFile) {
  store.commit('SET_SELECT_WAYLINE_INFO', wayline)
}

function onScroll (e: any) {
  const element = e.srcElement
  if (element.scrollTop + element.clientHeight >= element.scrollHeight - 5 && Math.ceil(pagination.total / pagination.page_size) > pagination.page && canRefresh.value) {
    pagination.page++
    getWaylines()
  }
}

interface FileItem {
  uid: string;
  name?: string;
  status?: string;
  response?: string;
  url?: string;
}

interface FileInfo {
  file: FileItem;
  fileList: FileItem[];
}
const fileList = ref<FileItem[]>([])

function beforeUpload (file: FileItem) {
  fileList.value = [file]
  loading.value = true
  return true
}
const uploadFile = async () => {
  fileList.value.forEach(async (file: FileItem) => {
    const fileData = new FormData()
    fileData.append('file', file, file.name)
    await importKmzFile(workspaceId, fileData).then((res) => {
      if (res.code === 0) {
        message.success(`${file.name} 上传成功`)
        canRefresh.value = true
        refreshWaylineFiles(true)
      }
    }).finally(() => {
      loading.value = false
      fileList.value = []
    })
  })
}

</script>

<style lang="scss" scoped>
.wayline-panel {
  background: #3c3c3c;
  margin-left: auto;
  margin-right: auto;
  margin-top: 10px;
  height: 90px;
  width: 95%;
  font-size: 13px;
  border-radius: 2px;
  cursor: pointer;
  .title {
    display: flex;
    flex-direction: row;
    align-items: center;
    height: 30px;
    font-weight: bold;
    margin: 0px 10px 0 10px;
  }
}
.uranus-scrollbar {
  overflow: auto;
  scrollbar-width: thin;
  scrollbar-color: #c5c8cc transparent;
}

.planning-panel {
  margin: 10px auto 0;
  width: 95%;
  padding: 10px;
  background: #2b2b2b;
  border-radius: 4px;
  color: #d9d9d9;
  font-size: 12px;
}
.planning-panel-title {
  font-size: 13px;
  font-weight: 700;
  color: #f5f5f5;
  margin-bottom: 8px;
  display: flex;
  align-items: center;
}
.planning-row {
  margin-bottom: 8px;
}
.planning-row:last-child {
  margin-bottom: 0;
}
.planning-two-col {
  display: flex;
  gap: 6px;
}
.planning-two-col > div {
  flex: 1;
}
.planning-label {
  display: block;
  color: #8c8c8c;
  margin-bottom: 4px;
}
.planning-actions {
  display: flex;
  gap: 6px;
}
.planning-actions .ant-btn {
  flex: 1;
}
.planning-empty {
  padding: 8px;
  border-radius: 3px;
  background: #353535;
  color: #8c8c8c;
  text-align: center;
}
.planning-waypoints {
  max-height: 200px;
  overflow-y: auto;
}
.planning-wp {
  padding: 6px;
  margin-bottom: 4px;
  background: #353535;
  border-radius: 3px;
  border: 1px solid transparent;
}
.planning-wp.active {
  border-color: #faad14;
  background: #3a2f1a;
}
.planning-wp-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 4px;
}
.planning-wp-index {
  color: #faad14;
  font-weight: 700;
}
.planning-wp-coord {
  font-size: 11px;
  color: #bfbfbf;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.planning-wp-tail {
  display: flex;
  align-items: center;
  gap: 4px;
}
.planning-wp-unit {
  color: #8c8c8c;
  font-size: 11px;
}
.scrollbar {
  overflow-y: auto;
  overflow-x: hidden;
}
.planning-advanced {
  margin-top: 4px;
  padding: 8px;
  background: #1f1f1f;
  border-radius: 3px;
  border: 1px solid #444;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.planning-wp-advanced {
  margin-top: 6px;
  padding: 6px;
  background: #1f1f1f;
  border-radius: 3px;
  border: 1px dashed #555;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.planning-wp-row {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.planning-wp-row.planning-two-col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px;
}
.planning-wp-row-label {
  color: #8c8c8c;
  font-size: 11px;
}
.planning-status {
  margin-top: 4px;
  padding: 6px;
  border-radius: 3px;
  background: #1f1f1f;
  color: #faad14;
  font-size: 11px;
}
.planning-section-gap {
  height: 6px;
  border-bottom: 1px solid #4f4f4f;
  margin: 10px 0;
}
.planned-wayline-panel {
  margin: 10px auto 0;
  width: 95%;
  padding: 10px;
  background: #2b2b2b;
  border-radius: 4px;
  color: #d9d9d9;
  font-size: 12px;
}
.planned-wayline-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  font-weight: 700;
  color: #f5f5f5;
  margin-bottom: 8px;
}
.planned-wayline-card {
  padding: 8px;
  margin-bottom: 8px;
  background: #353535;
  border-radius: 3px;
}
.planned-wayline-list {
  max-height: 360px;
  overflow-y: auto;
  padding-right: 2px;
}
.planned-wayline-list-footer {
  padding: 8px 0 2px;
  color: #8c8c8c;
  text-align: center;
}
.planned-wayline-card:last-child {
  margin-bottom: 0;
}
.planned-wayline-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}
.planned-wayline-name {
  min-width: 0;
  color: #f5f5f5;
  font-weight: 700;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.planned-wayline-status {
  flex: 0 0 auto;
  padding: 1px 6px;
  border-radius: 2px;
  background: #1f1f1f;
  color: #faad14;
  font-size: 11px;
}
.planned-wayline-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
  color: hsla(0, 0%, 100%, 0.65);
  margin-bottom: 5px;
}
.planned-wayline-meta.muted {
  color: hsla(0, 0%, 100%, 0.35);
}
.planned-wayline-actions {
  display: flex;
  gap: 6px;
  margin-top: 6px;
}
.planned-wayline-actions .ant-btn {
  flex: 1;
}
.planned-wayline-form {
  color: #262626;
}
.planned-wayline-detail {
  color: #262626;
}
.planned-wayline-detail-grid {
  display: grid;
  grid-template-columns: 86px minmax(0, 1fr) 86px minmax(0, 1fr);
  gap: 8px 12px;
  margin-bottom: 14px;
  font-size: 12px;
}
.planned-wayline-detail-grid span {
  color: #8c8c8c;
}
.planned-wayline-detail-grid strong {
  min-width: 0;
  font-weight: 500;
  overflow-wrap: anywhere;
}
.planned-wayline-detail-actions {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.planned-wayline-waypoint-table {
  max-height: 280px;
  overflow-y: auto;
  border: 1px solid #f0f0f0;
  border-radius: 4px;
}
.planned-wayline-waypoint-row {
  display: grid;
  grid-template-columns: 44px 1fr 1fr 72px;
  gap: 8px;
  padding: 7px 10px;
  border-bottom: 1px solid #f0f0f0;
  font-size: 12px;
}
.planned-wayline-waypoint-row:last-child {
  border-bottom: 0;
}
.planned-wayline-waypoint-row.head {
  position: sticky;
  top: 0;
  z-index: 1;
  background: #fafafa;
  color: #8c8c8c;
  font-weight: 700;
}
</style>
