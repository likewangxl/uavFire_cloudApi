<template>
  <div class="project-wayline-wrapper height-100">
    <a-spin :spinning="loading" :delay="300" tip="下载中" size="large">
    <div style="height: 50px; line-height: 50px; border-bottom: 1px solid #4f4f4f; font-weight: 450;">
      <a-row>
        <a-col :span="1"></a-col>
        <a-col :span="15">{{ isTaskRouteSelector ? '选择KMZ航线文件' : '航线任务' }}</a-col>
        <a-col :span="8" v-if="importVisible" class="wayline-header-actions">
          <a-tooltip title="导入航线">
            <a-upload
              name="file"
              :accept="plannerTab === 'delivery' ? '.kmz,.kml' : '.kmz'"
              :multiple="false"
              :before-upload="beforeUpload"
              :show-upload-list="false"
              :custom-request="uploadFile"
            >
              <a-button class="wayline-header-icon-button" type="text" :loading="loading">
                <ImportOutlined />
              </a-button>
            </a-upload>
          </a-tooltip>
          <a-tooltip title="新建航线">
            <a-button class="wayline-header-icon-button" type="text" @click="openCreateRouteModal">
              <PlusOutlined />
            </a-button>
          </a-tooltip>
        </a-col>
      </a-row>
    </div>
    <div :style="{ height : height + 'px'}" class="scrollbar">
      <a-tabs
        v-if="showPlanningTools"
        v-model:activeKey="plannerTab"
        class="planner-mode-tabs">
        <a-tab-pane key="monitor" tab="监测规划" />
        <a-tab-pane key="delivery" tab="投放任务" />
      </a-tabs>
      <div v-if="showPlanningTools" v-show="plannerTab === 'monitor'">
      <div class="planned-wayline-panel">
        <div class="planned-wayline-title">
          <span>监测航线库</span>
          <a-button size="small" type="link" :loading="plannedWaylinesLoading" @click="refreshPlannedWaylines">
            刷新
          </a-button>
        </div>
        <div class="planning-empty" v-if="!plannedWaylinesLoading && plannedWaylinesData.data.length === 0">
          暂无已保存监测航线。
        </div>
        <div v-else class="planned-wayline-list" @scroll="onPlannedWaylinesScroll">
          <div class="planned-wayline-card" v-for="record in plannedWaylinesData.data" :key="record.plannedWaylineId" @click="onPreviewPlannedWayline(record)">
            <div class="planned-wayline-card-head">
              <a-tooltip :title="record.name">
                <span class="planned-wayline-name">{{ record.name }}</span>
              </a-tooltip>
              <span class="planned-wayline-status" :class="{ failed: normalizePlannedWaylineStatus(record) === PlannedWaylineStatus.FAILED }">
                {{ formatPlannedWaylineStatus(record) }}
              </span>
            </div>
            <div class="planned-wayline-meta">
              <span>航点 {{ record.waypoints?.length || 0 }}</span>
              <span>高度 {{ formatNumber(record.defaultHeight) }} m</span>
              <span>速度 {{ formatNumber(record.maxSpeed) }} m/s</span>
            </div>
            <div class="planned-wayline-reason" v-if="getPlannedWaylineTaskReason(record)">
              {{ getPlannedWaylineTaskReason(record) }}
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
                @change="(updated: any) => onMissionMonitorChange(updated)"
                @execute="openExecuteTargetModal" />
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
                :class="{ 'wayline-button-wrap': action.wrap }"
                @click.stop="action.handler(record, $event)">
                {{ action.label }}
              </a-button>
              <a-button size="small" danger @click.stop="onDeletePlannedWayline(record)">删除</a-button>
            </div>
          </div>
          <div class="planned-wayline-list-footer" v-if="plannedWaylinesLoading">加载中...</div>
          <div class="planned-wayline-list-footer" v-else-if="plannedWaylinesData.data.length > 0 && !plannedWaylinesCanRefresh">已加载全部</div>
        </div>
      </div>
      </div>
      <Fc100DeliveryView
        v-if="showPlanningTools"
        v-show="plannerTab === 'delivery'"
        :planned-waylines-data="plannedWaylinesData"
        :planned-waylines-loading="plannedWaylinesLoading"
        :planned-waylines-can-refresh="plannedWaylinesCanRefresh"
        :refresh-planned-waylines="refreshPlannedWaylines"
        :on-planned-waylines-scroll="onPlannedWaylinesScroll"
        :show-planned-wayline-detail="showPlannedWaylineDetail"
        :on-open-delivery-task="openDeliveryTaskModal"
        :on-delete-planned-wayline="onDeletePlannedWayline" />
      <a-collapse
        v-if="showPlanningTools"
        v-show="plannerTab === 'monitor'"
        class="wayline-mode-collapse generated-wayline-collapse"
        :bordered="false"
        expandIconPosition="right"
        accordion
        style="background: #232323;">
        <a-collapse-panel key="generated-wayline" header="已生成航线" style="border-bottom: 1px solid #4f4f4f;">
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
            <a-empty :image-style="{ height: '60px', marginTop: '60px' }" />
          </div>
        </a-collapse-panel>
      </a-collapse>
      <div id="data" class="height-100 uranus-scrollbar" v-else-if="waylinesData.data.length !== 0" @scroll="onScroll">
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
          <div v-if="savePlannedWaylineModal.aircraftModelKey === 'M300'" class="planning-row planning-two-col">
            <div>
              <span class="planning-label">云台负载</span>
              <a-select size="small" style="width: 100%;" v-model:value="savePlannedWaylineModal.payloadModelKey">
                <a-select-option v-for="payload in M300_PAYLOAD_OPTIONS" :key="payload" :value="payload">{{ payload }}</a-select-option>
              </a-select>
            </div>
            <div>
              <span class="planning-label">安装位</span>
              <a-select size="small" style="width: 100%;" v-model:value="savePlannedWaylineModal.payloadPositionIndex">
                <a-select-option :value="0">左/主云台</a-select-option>
                <a-select-option :value="1">右云台</a-select-option>
                <a-select-option :value="2">上云台</a-select-option>
              </a-select>
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
            <span>状态</span><strong>{{ formatPlannedWaylineStatus(selectedPlannedWayline) }}</strong>
            <span>机型</span><strong>{{ selectedPlannedWayline.aircraftModelKey || '-' }}</strong>
            <span>云台负载</span><strong>{{ selectedPlannedWayline.payloadModelKey || '-' }}</strong>
            <span>安装位</span><strong>{{ formatPayloadPosition(selectedPlannedWayline.payloadPositionIndex) }}</strong>
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
            <span>失败原因</span><strong>{{ getPlannedWaylineTaskReason(selectedPlannedWayline) || '-' }}</strong>
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
              :class="{ 'wayline-button-wrap': action.wrap }"
              @click="action.handler(selectedPlannedWayline, $event)">
              {{ action.label }}
            </a-button>
            <a-button
              v-for="action in getFc100GeneratedWaylineActions(selectedPlannedWayline)"
              :key="action.key"
              size="small"
              :type="action.primary ? 'primary' : 'default'"
              :class="{ 'wayline-button-wrap': action.wrap }"
              :disabled="action.disabled"
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
      <a-modal
        v-model:visible="executeTargetModal.visible"
        width="520px"
        title="选择执行飞行器"
        :confirmLoading="executeTargetModal.loading"
        ok-text="确认执行"
        cancel-text="取消"
        @ok="confirmExecuteTarget">
        <div class="execute-target-modal">
          <div class="execute-target-summary">
            <span>航线</span>
            <strong>{{ executeTargetModal.record?.name || '-' }}</strong>
          </div>
          <div class="execute-target-field">
            <span class="planning-label">在线飞行器</span>
            <a-select
              style="width: 100%;"
              :value="executeTargetModal.targetSn"
              placeholder="请选择在线飞行器"
              @change="(sn: string) => executeTargetModal.targetSn = sn">
              <a-select-option
                v-for="aircraft in executeTargetOptions"
                :key="aircraft.sn"
                :value="aircraft.sn">
                {{ aircraft.callsign || aircraft.sn }}<span v-if="aircraft.aircraftModelKey"> · {{ aircraft.aircraftModelKey }}</span>
              </a-select-option>
            </a-select>
          </div>
          <div class="planning-empty" v-if="executeTargetOptions.length === 0">
            当前没有检测到在线飞行器，请确认 MSDK 程序在线后点击左侧刷新。
          </div>
        </div>
      </a-modal>
    </div>
    </a-spin>
    <Teleport v-if="showPlanningTools && planningOverlayReady" to="#wayline-planning-overlay-host">
      <PlannerWorkspace
        v-show="plannerTab === 'monitor' || planningActive"
        :can-execute="!!selectedAircraftSn"
        :on-start-placing="onStartPlacing"
        :on-stop-placing="onStopPlacing"
        :on-start-execution="onStartExecution"
        :on-stop-execution="onStopExecution"
        :on-save="onSavePlannedWayline" />
    </Teleport>
    <Teleport v-if="showPlanningTools && planningOverlayReady" to="#wayline-planning-overlay-host">
      <div v-if="createRouteModal.visible" class="create-route-popover">
        <div class="create-route-popover-head">
          <span>创建新航线</span>
          <CloseOutlined class="create-route-popover-close" @click="createRouteModal.visible = false" />
        </div>
        <div class="create-route-popover-body">
          <div class="create-route-section">
            <div class="create-route-section-title">任务类型</div>
            <div class="create-route-cards">
              <div
                class="create-route-card"
                :class="{ active: createRouteModal.missionType === 'monitor' }"
                @click="selectMissionType('monitor')">
                <RadarChartOutlined class="create-route-card-icon" />
                <span>监测任务</span>
              </div>
              <div
                class="create-route-card"
                :class="{ active: createRouteModal.missionType === 'delivery' }"
                @click="selectMissionType('delivery')">
                <RocketOutlined class="create-route-card-icon" />
                <span>投放任务</span>
              </div>
            </div>
          </div>
          <div class="create-route-section">
            <div class="create-route-section-title">航线类型</div>
            <div class="create-route-cards">
              <div
                class="create-route-card"
                :class="{ active: createRouteModal.routeType === 'waypoint' }"
                @click="createRouteModal.routeType = 'waypoint'">
                <EnvironmentOutlined class="create-route-card-icon" />
                <span>航点航线</span>
              </div>
              <template v-if="createRouteModal.missionType === 'monitor'">
                <div
                  class="create-route-card"
                  :class="{ active: createRouteModal.routeType === 'patrol' }"
                  @click="createRouteModal.routeType = 'patrol'">
                  <RetweetOutlined class="create-route-card-icon" />
                  <span>巡逻航线</span>
                </div>
                <div
                  class="create-route-card"
                  :class="{ active: createRouteModal.routeType === 'area' }"
                  @click="createRouteModal.routeType = 'area'">
                  <BorderOutlined class="create-route-card-icon" />
                  <span>面状航线</span>
                </div>
              </template>
            </div>
            <div class="create-route-hint" v-if="createRouteModal.missionType === 'delivery'">
              投放任务仅支持航点航线。
            </div>
            <div class="create-route-hint" v-else-if="createRouteModal.routeType === 'patrol'">
              巡逻航线：沿布点顺序飞行并自动闭合回到起点。
            </div>
            <div class="create-route-hint" v-else-if="createRouteModal.routeType === 'area'">
              面状航线：先点出测区多边形，再按相机重叠率生成弓字形覆盖航点。
            </div>
          </div>
        </div>
        <div class="create-route-popover-foot">
          <a-button size="small" @click="createRouteModal.visible = false">取消</a-button>
          <a-button size="small" type="primary" @click="confirmCreateRoute">确定</a-button>
        </div>
      </div>
      <div
        v-if="prepareTargetModal.visible"
        class="create-route-popover prepare-target-popover"
        :style="{ top: prepareTargetModal.anchorTop + 'px' }">
        <div class="create-route-popover-head">
          <span><RocketOutlined class="prepare-target-head-icon" />选择下发飞行器</span>
          <CloseOutlined class="create-route-popover-close" @click="prepareTargetModal.visible = false" />
        </div>
        <div class="create-route-popover-body">
          <div class="prepare-target-summary">
            <span class="prepare-target-summary-label">航线</span>
            <strong>{{ prepareTargetModal.record?.name || '-' }}</strong>
          </div>
          <span class="planning-label">
            目标飞行器<em v-if="executeTargetOptions.length" class="prepare-target-count">（{{ executeTargetOptions.length }} 台在线）</em>
          </span>
          <a-select
            class="prepare-target-select"
            style="width: 100%;"
            size="large"
            :value="prepareTargetModal.targetSn"
            placeholder="请选择在线飞行器"
            dropdown-class-name="prepare-target-dropdown"
            :get-popup-container="(node: any) => node.parentElement"
            @change="(sn: string) => prepareTargetModal.targetSn = sn">
            <a-select-option
              v-for="aircraft in executeTargetOptions"
              :key="aircraft.sn"
              :value="aircraft.sn">
              <span class="prepare-target-option">
                <RocketOutlined class="prepare-target-option-icon" />
                <span class="prepare-target-option-name">{{ aircraft.callsign || aircraft.sn }}</span>
                <span v-if="aircraft.aircraftModelKey" class="prepare-target-option-model">{{ aircraft.aircraftModelKey }}</span>
              </span>
            </a-select-option>
          </a-select>
          <div class="planning-empty" v-if="executeTargetOptions.length === 0">
            当前没有检测到在线飞行器，请确认 MSDK 程序在线后点击左侧刷新。
          </div>
        </div>
        <div class="create-route-popover-foot">
          <a-button size="small" @click="prepareTargetModal.visible = false">取消</a-button>
          <a-button
            size="small"
            type="primary"
            :loading="prepareTargetModal.loading"
            :disabled="!prepareTargetModal.targetSn"
            @click="confirmPrepareTarget">确认下发准备</a-button>
        </div>
      </div>
      <div
        v-if="deliveryTaskModal.visible"
        class="create-route-popover prepare-target-popover"
        :style="{ top: deliveryTaskModal.anchorTop + 'px' }">
        <div class="create-route-popover-head">
          <span><RocketOutlined class="prepare-target-head-icon" />投放任务</span>
          <CloseOutlined class="create-route-popover-close" @click="deliveryTaskModal.visible = false" />
        </div>
        <div class="create-route-popover-body">
          <div class="prepare-target-summary">
            <span class="prepare-target-summary-label">航线</span>
            <strong>{{ deliveryTaskModal.record?.name || '-' }}</strong>
          </div>
          <div class="delivery-task-badge" v-if="deliveryExistingTask">
            <span>已创建任务 <b>{{ deliveryExistingTask.taskId }}</b>
              <template v-if="fc100PlanningState.taskStatus?.status"> · {{ fc100PlanningState.taskStatus.status }}</template>
              <template v-if="fc100PlanningState.taskStatus?.progressPercent != null"> · {{ fc100PlanningState.taskStatus.progressPercent }}%</template>
            </span>
            <a
              class="delivery-task-refresh"
              :class="{ loading: fc100PlanningState.loadingAction === 'status' }"
              @click="handleFc100GeneratedWaylineTaskStatus">刷新</a>
          </div>
          <span class="planning-label">
            投放飞行器<em v-if="fc100AircraftDevices.length" class="prepare-target-count">（{{ fc100AircraftDevices.length }} 台）</em>
          </span>
          <a-select
            class="prepare-target-select delivery-device-select"
            style="width: 100%;"
            size="large"
            option-label-prop="label"
            :value="deliveryTaskModal.deviceSn"
            placeholder="请选择 FC100 投放飞行器"
            dropdown-class-name="prepare-target-dropdown"
            :get-popup-container="(node: any) => node.parentElement"
            @change="(sn: string) => deliveryTaskModal.deviceSn = sn">
            <a-select-option
              v-for="device in fc100AircraftDevices"
              :key="device.deviceSn"
              :value="device.deviceSn"
              :label="formatFc100DeliveryAircraftModel() + ' · ' + device.deviceSn">
              <div class="delivery-device-option">
                <div class="delivery-device-option-top">
                  <RocketOutlined class="prepare-target-option-icon" />
                  <span class="prepare-target-option-name">{{ formatFc100DeliveryAircraftModel() }}</span>
                  <span class="prepare-target-option-model" :class="{ offline: !isFc100DeviceOnline(device) }">
                    {{ isFc100DeviceOnline(device) ? '在线' : '离线' }}
                  </span>
                </div>
                <div class="delivery-device-option-sn">{{ device.deviceSn }}</div>
              </div>
            </a-select-option>
          </a-select>
          <div class="planning-empty" v-if="fc100AircraftDevices.length === 0">
            未检测到 FC100 设备，请确认设备在线后重试（设备需在云端在线）。
          </div>
          <!-- 任务执行后到点投放控制（原投放执行面板已并入此处） -->
          <div class="delivery-terminal" v-if="deliveryExistingTask">
            <div class="delivery-terminal-head">
              <span>到点后投放控制</span>
              <small>{{ fc100TerminalControlHint }}</small>
            </div>
            <div class="delivery-terminal-actions">
              <a-button size="small" :loading="fc100PlanningState.loadingAction === 'ropeDown'" :disabled="!canUseFc100TerminalControls() || isFc100TerminalCommandLoading" @click="handleFc100RopeDown">放绳</a-button>
              <a-button size="small" :loading="fc100PlanningState.loadingAction === 'ropeStop'" :disabled="!canUseFc100TerminalControls() || isFc100TerminalCommandLoading" @click="handleFc100RopeStop">停止</a-button>
              <a-button size="small" :loading="fc100PlanningState.loadingAction === 'ropeUp'" :disabled="!canUseFc100TerminalControls() || isFc100TerminalCommandLoading" @click="handleFc100RopeUp">收绳</a-button>
              <a-button size="small" danger :loading="fc100PlanningState.loadingAction === 'releaseHook'" :disabled="!canUseFc100TerminalControls() || isFc100TerminalCommandLoading" @click="handleFc100ReleaseHook">脱钩</a-button>
              <a-button size="small" :loading="fc100PlanningState.loadingAction === 'returnHome'" :disabled="!getSelectedFc100DeviceSn() || isFc100TerminalCommandLoading" @click="handleFc100ReturnHome">返航</a-button>
            </div>
          </div>
        </div>
        <div class="create-route-popover-foot">
          <a-button size="small" @click="deliveryTaskModal.visible = false">取消</a-button>
          <a-button
            size="small"
            :loading="deliveryTaskModal.loading && fc100PlanningState.loadingAction === 'import'"
            :disabled="!deliveryTaskModal.deviceSn || !deliveryTaskModal.record?.kmzUrl"
            @click="confirmCreateDeliveryTask">
            {{ deliveryExistingTask ? '重新创建' : '创建飞行任务' }}
          </a-button>
          <a-button
            size="small"
            type="primary"
            :loading="deliveryTaskModal.loading && fc100PlanningState.loadingAction === 'start'"
            @click="confirmStartDeliveryTask">开始执行</a-button>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script lang="ts" setup>
import { reactive } from '@vue/reactivity'
import { message, Modal } from 'ant-design-vue'
import { computed, nextTick, onMounted, onUnmounted, onUpdated, ref, watch } from 'vue'
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
  importPlannedWaylineKmzFile,
  preparePlannedWaylineTask,
  updatePlannedWayline,
} from '/@/api/wayline'
import { ELocalStorageKey, ERouterName, EDeviceTypeName } from '/@/types'
import { EllipsisOutlined, CameraFilled, UserOutlined, ImportOutlined, PlusOutlined, RadarChartOutlined, RocketOutlined, EnvironmentOutlined, RetweetOutlined, BorderOutlined, CloseOutlined } from '@ant-design/icons-vue'
import { DEVICE_MODEL_KEY, DEVICE_NAME, EModeCode } from '/@/types/device'
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
  startExecution as planningExecute,
  stopExecution as planningStopExec,
  setTargetAircraft as planningSetTarget,
  buildPlannedWaylineBody,
  loadPlannedWayline,
  previewPlannedWayline,
  resetPlanningDraft,
  setRouteKind,
  setFlightPositionFromRecord,
  setFlightPositionFromWgs,
  requestAircraftRecenter,
  setTrackedAircraft,
} from '/@/hooks/use-wayline-planning'
import { getDeviceTopo } from '/@/api/manage'
import { listMsdkDevices } from '/@/api/msdk-device'
import type { MsdkDeviceState } from '/@/api/msdk-device'
import WaylineMissionMonitor from '/@/components/WaylineMissionMonitor.vue'
import Fc100DeliveryView from '/@/components/wayline-planner/Fc100DeliveryView.vue'
import PlannerWorkspace from '/@/components/wayline-planner/PlannerWorkspace.vue'
import { setParamDrawerOpen, setPlannerTab, usePlannerUi } from '/@/hooks/use-planner-ui'
import { loadFlightAreas, confirmComplianceBeforeAction } from '/@/hooks/use-flight-area-compliance'
import { getFc100GeneratedWaylineActions, beforeFc100WaylineUpload, uploadFc100WaylineFile, fc100PlanningState, fc100AircraftDevices, isFc100DeviceOnline, formatFc100DeliveryAircraftModel, getSelectedFc100DeviceSn, onFc100PreviewGeneratedWayline, handleFc100ImportGeneratedWaylineTask, handleFc100StartGeneratedWaylineTask, getFc100RouteTask, setFc100RouteTask, handleFc100GeneratedWaylineTaskStatus, handleFc100RopeDown, handleFc100RopeStop, handleFc100RopeUp, handleFc100ReleaseHook, handleFc100ReturnHome, canUseFc100TerminalControls, isFc100TerminalCommandLoading, fc100TerminalControlHint } from '/@/hooks/use-fc100-delivery'
import type { FileItem } from '/@/components/wayline-planner/wayline-format'
import { canOverwritePlannedWayline, formatNumber, formatPlannedWaylineStatus, formatSafePlannedWaylineTimestamp, formatTimestamp, getPlannedWaylineTaskReason, normalizePlannedWaylineStatus, sanitizeDjiWaylineName } from '/@/components/wayline-planner/wayline-format'

const loading = ref(false)
const store = useMyStore()
const route = useRoute()
const isTaskRouteSelector = computed(() => route.name === ERouterName.SELECT_PLAN)
const showPlanningTools = computed(() => !isTaskRouteSelector.value)

// ---------- Planned wayline (click-to-fly) ----------
const planningState = getPlanningStateRaw()
// 正在规划（布点中或已有航点编辑）：用于让规划浮层在投放页签下也能出现
const planningActive = computed(() => planningState.active || planningState.waypoints.length > 0)
const plannerUi = usePlannerUi()
const plannerTab = computed({
  get: () => plannerUi.activeTab,
  set: (v: 'monitor' | 'delivery') => setPlannerTab(v),
})
const selectedAircraftSn = ref('')
const planningOverlayReady = ref(false)

// 新建航线弹窗（任务类型 + 航线类型；目前仅航点航线可用，巡逻/面状即将推出）
const createRouteModal = reactive({
  visible: false,
  missionType: 'monitor' as 'monitor' | 'delivery',
  routeType: 'waypoint' as 'waypoint' | 'patrol' | 'area',
})
function openCreateRouteModal () {
  createRouteModal.missionType = 'monitor'
  createRouteModal.routeType = 'waypoint'
  createRouteModal.visible = true
}
function selectMissionType (type: 'monitor' | 'delivery') {
  createRouteModal.missionType = type
  createRouteModal.routeType = 'waypoint'
}
function confirmCreateRoute () {
  createRouteModal.visible = false
  // 投放任务只支持航点航线；监测任务可选 航点/巡逻/面状
  const kind = createRouteModal.missionType === 'delivery' ? 'waypoint' : createRouteModal.routeType
  setPlannerTab(createRouteModal.missionType === 'delivery' ? 'delivery' : 'monitor')
  resetPlanningDraft()
  setRouteKind(kind)
  // 面状航线先画测区多边形再生成航点；航点/巡逻直接进入布点
  nextTick(() => onStartPlacing())
}
const monitorWorkspaceId = computed(() => localStorage.getItem(ELocalStorageKey.WorkspaceId) || '')
function onMissionMonitorChange (updated: PlannedWaylineRecord) {
  const idx = plannedWaylinesData.data.findIndex(r => r.plannedWaylineId === updated.plannedWaylineId)
  if (idx >= 0) plannedWaylinesData.data.splice(idx, 1, updated)
  applyPlannedWaylineFlightPosition(updated)
}

interface AircraftSummary {
  sn: string
  callsign: string
  gatewaySn: string
  aircraftModelKey: string
  source: 'cloud-topology' | 'msdk-agent'
  lastSeen?: number
}

const onlineAircraftMap = reactive({} as Record<string, AircraftSummary>)
const msdkAircraftMap = reactive({} as Record<string, MsdkDeviceState>)
const onlineAircrafts = computed<AircraftSummary[]>(() => Object.values(onlineAircraftMap))
const executeTargetModal = reactive({
  visible: false,
  loading: false,
  record: null as PlannedWaylineRecord | null,
  targetSn: '',
})
// 下发准备的飞行器选择（多台/未选时紧贴面板弹出，司空2 式）
const prepareTargetModal = reactive({
  visible: false,
  loading: false,
  record: null as PlannedWaylineRecord | null,
  targetSn: '',
  anchorTop: 12, // 弹窗顶部偏移(px),对齐触发它的那张航线卡片
})
// 投放任务弹窗：选 FC100 设备 + 创建/执行任务，紧贴航线卡片，样式与监测页一致
const deliveryTaskModal = reactive({
  visible: false,
  loading: false,
  record: null as PlannedWaylineRecord | null,
  deviceSn: '',
  anchorTop: 12,
})
// 反显：当前航线已创建的投放任务（fc100RouteTasks 是 reactive，map 变化会自动更新）
const deliveryExistingTask = computed(() =>
  deliveryTaskModal.record ? getFc100RouteTask(deliveryTaskModal.record.plannedWaylineId) : null)
// 任务类弹窗互斥/随页签关闭：同一时刻只留一个；切监测/投放页签时全部收起
function closeWaylineTaskPopovers () {
  prepareTargetModal.visible = false
  executeTargetModal.visible = false
  deliveryTaskModal.visible = false
}
watch(plannerTab, () => closeWaylineTaskPopovers())
const executeTargetOptions = computed<AircraftSummary[]>(() => {
  const targetMap = new Map<string, AircraftSummary>()
  onlineAircrafts.value.forEach(aircraft => {
    if (aircraft.sn) targetMap.set(aircraft.sn, aircraft)
  })
  Object.values(store.state.msdkDeviceState.devices || {}).forEach((device: any) => {
    if (!device?.aircraftSn || !device.online) return
    if (!targetMap.has(device.aircraftSn)) {
      targetMap.set(device.aircraftSn, {
        sn: device.aircraftSn,
        callsign: device.model || device.aircraftSn,
        gatewaySn: device.gatewaySn || device.aircraftSn,
        aircraftModelKey: normalizeAircraftModelKey(device.model) || DEFAULT_PLANNED_WAYLINE_MODEL,
        source: 'msdk-agent',
        lastSeen: device.updatedAt || Date.now(),
      })
    }
  })
  return Array.from(targetMap.values())
})
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
  payloadModelKey: 'H20T',
  payloadPositionIndex: 0,
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
const PLANNED_WAYLINE_MODEL_OPTIONS = ['M4T', 'M4E', 'M30T', 'M30', 'M3T', 'M3E', 'M3TD', 'M3D', 'M350', 'M300']
const M300_PAYLOAD_OPTIONS = ['H20', 'H20T', 'H30', 'H30T']
const formatPayloadPosition = (value?: number) => ({ 0: '左/主云台', 1: '右云台', 2: '上云台' } as Record<number, string>)[Number(value)] || '-'
const DEFAULT_PLANNED_WAYLINE_MODEL = 'M4T'

watch(
  () => selectedAircraftSn.value,
  sn => {
    // 用户选择 MSDK 飞机即设为跟踪目标（执行中的航线会在轮询里持续覆盖）。
    if (sn) setTrackedAircraft(sn)
    syncSelectedAircraftFlightPosition(sn)
  },
)

watch(
  () => {
    const sn = selectedAircraftSn.value
    const osd = sn ? store.state.deviceState.deviceInfo[sn] : null
    return osd ? `${sn}:${(osd as any).longitude}:${(osd as any).latitude}:${(osd as any).height}` : ''
  },
  () => syncSelectedAircraftFlightPosition(),
)

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

function pickDeviceField (source: any, camelKey: string, snakeKey: string) {
  if (!source || typeof source !== 'object') return undefined
  return source[camelKey] !== undefined ? source[camelKey] : source[snakeKey]
}

function normalizeDeviceDomain (domain: any): string {
  if (domain === null || domain === undefined) return ''
  if (typeof domain === 'object') {
    return normalizeDeviceDomain(domain.domain ?? domain.value ?? domain.name)
  }
  return String(domain).toLowerCase()
}

function isGatewayDomain (domain: any): boolean {
  const value = normalizeDeviceDomain(domain)
  return value === String(EDeviceTypeName.Gateway) ||
    value === 'gateway' ||
    value === 'remoter_control' ||
    value === 'remote_control'
}

function isDeviceOnline (device: any): boolean {
  return Boolean(pickDeviceField(device, 'status', 'status') ?? pickDeviceField(device, 'online', 'online') ?? true)
}

function upsertOnlineAircraft (seen: Set<string>, summary: AircraftSummary) {
  if (!summary.sn) return
  seen.add(summary.sn)
  const existing = onlineAircraftMap[summary.sn]
  if (existing?.source === 'msdk-agent' && summary.source !== 'msdk-agent') return
  onlineAircraftMap[summary.sn] = summary
}

function syncManagedTopoAircrafts (devices: any[], seen: Set<string>) {
  devices.forEach((gateway: any) => {
    const children = pickDeviceField(gateway, 'children', 'children')
    const childList = Array.isArray(children) ? children : (children ? [children] : [])
    if (!isGatewayDomain(pickDeviceField(gateway, 'domain', 'domain')) && childList.length === 0) return
    const gatewayOnline = isDeviceOnline(gateway)
    childList.forEach((child: any) => {
      const childSn = pickDeviceField(child, 'deviceSn', 'device_sn')
      if (!childSn) return
      if (!gatewayOnline && !isDeviceOnline(child)) return
      upsertOnlineAircraft(seen, {
        sn: childSn,
        callsign: pickDeviceField(child, 'nickname', 'nickname') ||
          pickDeviceField(child, 'deviceName', 'device_name') ||
          childSn,
        gatewaySn: pickDeviceField(gateway, 'deviceSn', 'device_sn') || pickDeviceField(child, 'parentSn', 'parent_sn') || '',
        aircraftModelKey: inferAircraftModelKey(child) || DEFAULT_PLANNED_WAYLINE_MODEL,
        source: 'cloud-topology',
        lastSeen: Date.now(),
      })
    })
  })
}

function syncMsdkOnlineAircrafts (devices: MsdkDeviceState[], seen: Set<string>) {
  store.commit('SET_MSDK_DEVICE_STATE', devices)
  Object.keys(msdkAircraftMap).forEach(sn => {
    delete msdkAircraftMap[sn]
  })
  devices.forEach((device) => {
    if (!device.aircraftSn || !device.online) return
    msdkAircraftMap[device.aircraftSn] = device
    upsertOnlineAircraft(seen, {
      sn: device.aircraftSn,
      callsign: device.model || device.aircraftSn,
      gatewaySn: device.gatewaySn || device.aircraftSn,
      aircraftModelKey: normalizeAircraftModelKey(device.model) || DEFAULT_PLANNED_WAYLINE_MODEL,
      source: 'msdk-agent',
      lastSeen: device.updatedAt || Date.now(),
    })
    if ((selectedAircraftSn.value === device.aircraftSn || planningState.aircraftSn === device.aircraftSn) &&
      device.longitude && device.latitude) {
      setFlightPositionFromWgs(device.aircraftSn, device.longitude, device.latitude, {
        height: device.height,
        updatedAt: device.updatedAt || Date.now(),
      })
    }
  })
}

function syncSelectedAircraftFlightPosition (sn = selectedAircraftSn.value) {
  if (!sn) return
  const msdkDevice = msdkAircraftMap[sn]
  if (msdkDevice?.longitude && msdkDevice?.latitude) {
    setFlightPositionFromWgs(sn, msdkDevice.longitude, msdkDevice.latitude, {
      height: msdkDevice.height,
      updatedAt: msdkDevice.updatedAt || Date.now(),
    })
    return
  }
  const osd = store.state.deviceState.deviceInfo[sn]
  if (!osd) return
  setFlightPositionFromWgs(sn, (osd as any).longitude, (osd as any).latitude, {
    height: (osd as any).height,
    updatedAt: Date.now(),
  })
}

function getRecordAircraftSn (record: PlannedWaylineRecord | null | undefined) {
  return record?.droneSn || record?.aircraftSn || ''
}

function getSingleOnlineMsdkAircraft () {
  const msdkSnList = Object.keys(msdkAircraftMap)
  return msdkSnList.length === 1 ? msdkSnList[0] : ''
}

function applyPrepareTargetSelection (sn: string) {
  if (!sn) return
  const summary = onlineAircraftMap[sn]
  if (!summary) return
  selectedAircraftSn.value = summary.sn
  planningSetTarget(summary.gatewaySn, summary.sn)
  syncSelectedAircraftFlightPosition(summary.sn)
}

function getMsdkAircraftSummary (sn: string): AircraftSummary | null {
  const device = msdkAircraftMap[sn]
  if (!device?.aircraftSn || !device.online) return null
  return {
    sn: device.aircraftSn,
    callsign: device.model || device.aircraftSn,
    gatewaySn: device.gatewaySn || device.aircraftSn,
    aircraftModelKey: normalizeAircraftModelKey(device.model) || DEFAULT_PLANNED_WAYLINE_MODEL,
    source: 'msdk-agent',
    lastSeen: device.updatedAt || Date.now(),
  }
}

function resolvePlanningExecutionTarget (): AircraftSummary | null {
  const candidateSn = selectedAircraftSn.value || planningState.aircraftSn || getSingleOnlineMsdkAircraft()
  if (!candidateSn) return null
  return onlineAircraftMap[candidateSn] || getMsdkAircraftSummary(candidateSn)
}

function isPlannedWaylineLive (record: PlannedWaylineRecord | null | undefined): boolean {
  if (!record) return false
  const status = normalizePlannedWaylineStatus(record)
  return status === PlannedWaylineStatus.EXECUTING || status === PlannedWaylineStatus.PUBLISHING
}

function applyPlannedWaylineFlightPosition (record: PlannedWaylineRecord | null | undefined) {
  const recordAircraftSn = getRecordAircraftSn(record)
  if (!recordAircraftSn) return
  // 执行中的航线 → 该飞机认领地图跟踪，挡掉其它(停地)飞机的位置写入。
  if (isPlannedWaylineLive(record)) {
    setTrackedAircraft(recordAircraftSn)
  }
  const onlineMsdkSnList = Object.keys(msdkAircraftMap)
  const msdkDevice = msdkAircraftMap[recordAircraftSn]
  if (msdkDevice?.longitude && msdkDevice?.latitude) {
    setFlightPositionFromWgs(recordAircraftSn, msdkDevice.longitude, msdkDevice.latitude, {
      height: msdkDevice.height,
      updatedAt: msdkDevice.updatedAt || Date.now(),
      currentWaypointIndex: record?.currentWaypointIndex,
      totalWaypoints: record?.totalWaypoints,
    })
    return
  }
  if (onlineMsdkSnList.length > 0) {
    return
  }
  setFlightPositionFromRecord(record)
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
  const normalizedAircraftModelKey = normalizePlannedWaylineModel(aircraftModelKey)
  return {
    name,
    aircraftModelKey: normalizedAircraftModelKey,
    payloadModelKey: normalizedAircraftModelKey === 'M300' ? savePlannedWaylineModal.payloadModelKey : undefined,
    payloadPositionIndex: normalizedAircraftModelKey === 'M300' ? savePlannedWaylineModal.payloadPositionIndex : undefined,
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
  const seen = new Set<string>()
  try {
    const res = await getDeviceTopo(workspaceIdForPlanning)
    if (res.code === 0 && Array.isArray(res.data)) {
      syncManagedTopoAircrafts(res.data, seen)
    }
  } catch (e) {
    // silent — MSDK state below and the next timer tick can still populate candidates.
  }
  try {
    const msdkRes = await listMsdkDevices()
    if (msdkRes.code === 0 && Array.isArray(msdkRes.data)) {
      syncMsdkOnlineAircrafts(msdkRes.data, seen)
    }
  } catch (e) {
    // silent — topo will retry.
  }
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
  const aircrafts = onlineAircrafts.value
  if (!selectedAircraftSn.value && !planningState.executing && !planningState.active && aircrafts.length === 1) {
    onSelectAircraft(aircrafts[0].sn)
  }
}

function onSelectAircraft (sn: string) {
  selectedAircraftSn.value = sn
  const summary = onlineAircraftMap[sn]
  if (summary) {
    planningSetTarget(summary.gatewaySn, summary.sn)
  }
  syncSelectedAircraftFlightPosition(sn)
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

async function onStartExecution () {
  await refreshOnlineAircrafts()
  const summary = resolvePlanningExecutionTarget()
  if (!summary) {
    message.warning('执行航线前需要选择在线飞行器。')
    return
  }
  if (!(await confirmComplianceBeforeAction('下发执行'))) return
  selectedAircraftSn.value = summary.sn
  planningSetTarget(summary.gatewaySn, summary.sn)
  syncSelectedAircraftFlightPosition(summary.sn)
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
  // 新建航线默认名按航线类型区分：巡逻-航点航线/巡逻-巡逻航线/巡逻-面状航线 + 时间戳（监测规划页均属“巡逻”类）
  const routeKindLabels: Record<string, string> = { waypoint: '航点航线', patrol: '巡逻航线', area: '面状航线' }
  const newRouteName = `巡逻-${routeKindLabels[planningState.routeKind] || '航点航线'}-${formatSafePlannedWaylineTimestamp(new Date())}`
  const defaultName = saveAs
    ? `${sanitizeDjiWaylineName(editingName || '规划航线')} 副本`
    : sanitizeDjiWaylineName(editingName || newRouteName)
  savePlannedWaylineModal.visible = true
  savePlannedWaylineModal.saveAs = saveAs
  savePlannedWaylineModal.name = defaultName
  savePlannedWaylineModal.aircraftModelKey = normalizePlannedWaylineModel(summary.aircraftModelKey)
  savePlannedWaylineModal.payloadModelKey = editingRecord?.payloadModelKey || 'H20T'
  savePlannedWaylineModal.payloadPositionIndex = editingRecord?.payloadPositionIndex ?? 0
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
  if (!(await confirmComplianceBeforeAction('保存'))) return

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
    const activeRecord = plannedWaylinesData.data.find(record => {
      const status = String(record.taskStatus || record.status || '').toLowerCase()
      return ['executing', 'paused', 'broken'].includes(status) &&
        (record.aircraftGcjLng != null || record.aircraftLng != null)
    })
    if (activeRecord) applyPlannedWaylineFlightPosition(activeRecord)
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
  // 点击预览别的航线时收起任务类弹窗（下发/执行/投放），避免弹窗残留指向旧航线
  const openTaskRecord = [prepareTargetModal, executeTargetModal, deliveryTaskModal]
    .find(m => m.visible)?.record
  if (openTaskRecord && openTaskRecord.plannedWaylineId !== record.plannedWaylineId) {
    closeWaylineTaskPopovers()
  }
  resetPlanningDraft()
  const recordAircraftSn = getRecordAircraftSn(record)
  if (recordAircraftSn && onlineAircraftMap[recordAircraftSn]) {
    applyPrepareTargetSelection(recordAircraftSn)
  } else {
    applyPrepareTargetSelection(getSingleOnlineMsdkAircraft())
  }
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

async function dispatchPreparePlannedWayline (record: PlannedWaylineRecord, targetDroneSn: string) {
  // Agent 路径 (M4T + RC,无机场) 不需要 dockSn,后端按 dockSn 是否非空自动路由。
  // 如果用户绑定了机场就走 dock 路径;否则走 agent 把 KMZ 推到 RC + MSDK。
  const body: any = {
    droneSn: targetDroneSn,
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

function openPrepareTargetModal (record: PlannedWaylineRecord) {
  closeWaylineTaskPopovers()
  // 默认选中：航线记录里的飞机(若在线) → 唯一在线飞机 → 空(让用户选)
  const recordAircraftSn = getRecordAircraftSn(record)
  const preset = (recordAircraftSn && onlineAircraftMap[recordAircraftSn])
    ? recordAircraftSn
    : (executeTargetOptions.value.length === 1 ? executeTargetOptions.value[0].sn : '')
  prepareTargetModal.record = record
  prepareTargetModal.targetSn = preset
  prepareTargetModal.visible = true
}

async function confirmPrepareTarget () {
  const record = prepareTargetModal.record
  if (!record) return
  const targetDroneSn = prepareTargetModal.targetSn
  if (!targetDroneSn) {
    message.warning('请选择在线飞行器后再下发准备。')
    return
  }
  applyPrepareTargetSelection(targetDroneSn)
  prepareTargetModal.loading = true
  try {
    await dispatchPreparePlannedWayline(record, targetDroneSn)
    prepareTargetModal.visible = false
    prepareTargetModal.record = null
    prepareTargetModal.targetSn = ''
  } finally {
    prepareTargetModal.loading = false
  }
}

// 把弹窗顶部对齐到触发它的那张航线卡片(覆盖层 host 的左边缘=面板右边缘,故弹窗就贴在该航线右侧)
function computePrepareAnchorTop (ev?: Event): number {
  try {
    const host = document.getElementById('wayline-planning-overlay-host')
    const trigger = (ev?.currentTarget as HTMLElement)?.closest('.planned-wayline-card') as HTMLElement ||
      (ev?.currentTarget as HTMLElement)
    if (!host || !trigger) return 12
    const hostRect = host.getBoundingClientRect()
    const triggerRect = trigger.getBoundingClientRect()
    const POPOVER_H = 248
    const raw = triggerRect.top - hostRect.top
    const max = Math.max(12, hostRect.height - POPOVER_H - 12)
    return Math.min(Math.max(12, raw), max)
  } catch (e) {
    return 12
  }
}

async function onPreparePlannedWaylineTask (record: PlannedWaylineRecord, ev?: Event) {
  clearPlannedWaylineTaskReason(record)
  // 同步先算好位置(此刻卡片还在原位),再 await 刷新在线飞机
  prepareTargetModal.anchorTop = computePrepareAnchorTop(ev)
  await refreshOnlineAircrafts()
  // 每次下发都强制选机(司空2 式):无论单台还是多台,一律弹窗确认
  openPrepareTargetModal(record)
}

// ---- 投放任务弹窗（FC100）：点航线卡片"下发"→弹窗选设备+创建/执行 ----
function openDeliveryTaskModal (record: PlannedWaylineRecord, ev?: Event) {
  closeWaylineTaskPopovers()
  deliveryTaskModal.anchorTop = computePrepareAnchorTop(ev)
  onFc100PreviewGeneratedWayline(record) // 设 selectedRecord + 地图预览
  const existing = getFc100RouteTask(record.plannedWaylineId)
  const presetDevice = existing?.deviceSn || getSelectedFc100DeviceSn() || ''
  deliveryTaskModal.record = record
  deliveryTaskModal.deviceSn = presetDevice
  // 把该航线已建任务载入全局态，供"开始执行"与面板终端控制复用
  fc100PlanningState.selectedRecord = record
  fc100PlanningState.selectedDeviceSn = presetDevice
  fc100PlanningState.taskId = existing?.taskId || ''
  fc100PlanningState.taskStatus = null
  deliveryTaskModal.visible = true
}

async function confirmCreateDeliveryTask () {
  const record = deliveryTaskModal.record
  if (!record) return
  if (!deliveryTaskModal.deviceSn) {
    message.warning('请选择投放飞行器后再创建任务。')
    return
  }
  fc100PlanningState.selectedRecord = record
  fc100PlanningState.selectedDeviceSn = deliveryTaskModal.deviceSn
  deliveryTaskModal.loading = true
  try {
    await handleFc100ImportGeneratedWaylineTask(record)
    // 成功后任务 ID 落到 fc100PlanningState.taskId → 按航线持久化以便反显
    if (fc100PlanningState.taskId) {
      setFc100RouteTask(record.plannedWaylineId, {
        taskId: fc100PlanningState.taskId,
        deviceSn: deliveryTaskModal.deviceSn,
        taskName: record.name,
        updatedAt: Date.now(),
      })
    }
  } finally {
    deliveryTaskModal.loading = false
  }
}

async function confirmStartDeliveryTask () {
  const record = deliveryTaskModal.record
  if (!record) return
  const existing = getFc100RouteTask(record.plannedWaylineId)
  if (!existing?.taskId) {
    message.warning('该航线尚未创建飞行任务，请先点击"创建飞行任务"。')
    return
  }
  // 载入已建任务，复用现有执行处理（含起飞前校验）
  fc100PlanningState.selectedRecord = record
  fc100PlanningState.selectedDeviceSn = existing.deviceSn || deliveryTaskModal.deviceSn
  fc100PlanningState.taskId = existing.taskId
  deliveryTaskModal.loading = true
  try {
    await handleFc100StartGeneratedWaylineTask()
  } finally {
    deliveryTaskModal.loading = false
  }
}

async function openExecuteTargetModal (record: PlannedWaylineRecord) {
  closeWaylineTaskPopovers()
  clearPlannedWaylineTaskReason(record)
  await refreshOnlineAircrafts()
  const targetDroneSn = selectedAircraftSn.value ||
    (record.droneSn && executeTargetOptions.value.some(item => item.sn === record.droneSn) ? record.droneSn : '') ||
    (record.aircraftSn && executeTargetOptions.value.some(item => item.sn === record.aircraftSn) ? record.aircraftSn : '') ||
    (executeTargetOptions.value.length === 1 ? executeTargetOptions.value[0].sn : '')
  executeTargetModal.record = record
  executeTargetModal.targetSn = targetDroneSn
  executeTargetModal.visible = true
}

function onExecutePlannedWaylineTask (record: PlannedWaylineRecord) {
  openExecuteTargetModal(record)
}

async function confirmExecuteTarget () {
  const record = executeTargetModal.record
  if (!record) return
  const targetDroneSn = executeTargetModal.targetSn
  if (!targetDroneSn) {
    message.warning('请选择在线飞行器后再执行。')
    return
  }
  const summary = executeTargetOptions.value.find(item => item.sn === targetDroneSn) || onlineAircraftMap[targetDroneSn]
  planningSetTarget(summary?.gatewaySn || record.gatewaySn || record.dockSn || targetDroneSn, targetDroneSn)
  selectedAircraftSn.value = targetDroneSn

  executeTargetModal.loading = true
  try {
    const prepareBody: any = {
      droneSn: targetDroneSn,
      executeTime: 0,
      taskType: 'IMMEDIATE',
    }
    if (record.dockSn) {
      prepareBody.dockSn = record.dockSn
    }
    const prepareRes = await preparePlannedWaylineTask(workspaceId, record.plannedWaylineId, prepareBody)
    if (prepareRes.code !== 0) return
    const executeRes = await executePlannedWaylineTask(workspaceId, record.plannedWaylineId, prepareBody)
    if (executeRes.code !== 0) return
    executeTargetModal.visible = false
    executeTargetModal.record = null
    executeTargetModal.targetSn = ''
    message.success('执行指令已发出，等待飞行器回传状态')
    await refreshPlannedWaylines(true)
    if (selectedPlannedWayline.value?.plannedWaylineId === record.plannedWaylineId) {
      await showPlannedWaylineDetail(executeRes.data || record)
    }
  } finally {
    executeTargetModal.loading = false
  }
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

function clearPlannedWaylineTaskReason (record: PlannedWaylineRecord) {
  record.taskStatusReason = ''
  const cached = plannedWaylinesData.data.find(item => item.plannedWaylineId === record.plannedWaylineId)
  if (cached) cached.taskStatusReason = ''
  if (selectedPlannedWayline.value?.plannedWaylineId === record.plannedWaylineId) {
    selectedPlannedWayline.value.taskStatusReason = ''
  }
}

function getPlannedWaylineActions (record: PlannedWaylineRecord) {
  const status = normalizePlannedWaylineStatus(record)
  if (status === PlannedWaylineStatus.DRAFT) {
    return [{ key: 'generate', label: '生成航线文件', primary: true, danger: false, wrap: true, handler: onGeneratePlannedWaylineFile }]
  }
  if (status === PlannedWaylineStatus.FILE_GENERATED) {
    return [{ key: 'prepare', label: '下发准备', primary: true, danger: false, wrap: true, handler: onPreparePlannedWaylineTask }]
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
      wrap: true,
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
  nextTick(() => {
    planningOverlayReady.value = true
  })
  const parent = document.getElementsByClassName('scrollbar').item(0)?.parentNode as HTMLDivElement
  height.value = document.body.clientHeight - parent.firstElementChild!.clientHeight
  getWaylines()
  if (showPlanningTools.value) {
    refreshPlannedWaylines(true)
    loadFlightAreas()
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
  // 每次进入航线页面：请求地图以飞机当前位置为中心（飞机位置就绪后由 GMap 居中一次）。
  requestAircraftRecenter()
})

onUnmounted(() => {
  setParamDrawerOpen(false)
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

function beforeUpload (file: FileItem) {
  // 投放任务页签下，顶部导入按钮直接当作 FC100 航线导入
  if (plannerTab.value === 'delivery') {
    return beforeFc100WaylineUpload(file)
  }
  if (!file.name || !file.name.toLowerCase().endsWith('.kmz')) {
    message.error('文件格式错误，请选择 KMZ 文件。')
    return false
  }
  return true
}

const uploadFile = async (options?: { file?: FileItem; onSuccess?: (res: any) => void; onError?: (err: any) => void }) => {
  // 投放任务页签下，顶部导入即 FC100 航线导入并创建任务
  if (plannerTab.value === 'delivery') {
    return uploadFc100WaylineFile(options)
  }
  const file = options?.file
  if (!file) {
    message.error('请选择 KMZ 文件。')
    return
  }
  loading.value = true
  const fileData = new FormData()
  fileData.append('file', file, file.name)
  try {
    const res = await importPlannedWaylineKmzFile(workspaceId, fileData)
    if (res.code === 0) {
      message.success(`${file.name} 已导入为可执行航线`)
      canRefresh.value = true
      refreshPlannedWaylines(true)
      refreshWaylineFiles(true)
      options?.onSuccess?.(res)
    } else {
      options?.onError?.(new Error(res.message || 'KMZ 导入失败'))
    }
  } catch (error) {
    options?.onError?.(error)
  } finally {
    loading.value = false
  }
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
:deep(.wayline-mode-collapse.ant-collapse > .ant-collapse-item > .ant-collapse-header) {
  color: #fff !important;
}
:deep(.wayline-mode-collapse.ant-collapse > .ant-collapse-item > .ant-collapse-header .ant-collapse-arrow) {
  color: #fff !important;
}
.project-wayline-wrapper :deep(.ant-btn) {
  font-size: 14px;
  font-family: inherit;
  font-weight: 400;
  line-height: 1.5715;
}
.project-wayline-wrapper :deep(.ant-btn-sm) {
  font-size: 14px;
}
.wayline-header-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  padding-right: 18px;
}
.wayline-header-icon-button {
  width: 32px;
  height: 32px;
  padding: 0;
  color: #fff;
  font-size: 22px;
}
.wayline-header-icon-button:hover,
.wayline-header-icon-button:focus {
  color: #fff;
  background: rgba(255, 255, 255, 0.08);
}

.create-route-popover {
  position: absolute;
  top: 12px;
  left: 12px;
  width: 348px;
  background: #1f2329;
  border: 1px solid #2c3a4f;
  border-radius: 8px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.45);
  pointer-events: auto;
  z-index: 40;
}
.create-route-popover-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid #2c3a4f;
  color: #fff;
  font-size: 15px;
  font-weight: 500;
}
.create-route-popover-close {
  color: #7d8ca0;
  cursor: pointer;
  font-size: 14px;
}
.create-route-popover-close:hover {
  color: #fff;
}
.create-route-popover-body {
  padding: 16px;
}
.create-route-popover-foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 10px 16px;
  border-top: 1px solid #2c3a4f;
}
.prepare-target-popover {
  width: 300px;
  background: rgba(13, 17, 23, 0.93); // 与高度剖面(ElevationProfile)底色一致
}
.prepare-target-head-icon {
  margin-right: 6px;
  color: #4f9bff;
}
.prepare-target-summary {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 14px;
  padding: 8px 10px;
  border-radius: 6px;
  background: #161a20;
  border: 1px solid #2c3a4f;
}
.prepare-target-summary-label {
  flex: 0 0 auto;
  font-size: 12px;
  color: #7d8ca0;
}
.prepare-target-summary strong {
  color: #fff;
  font-weight: 500;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.prepare-target-count {
  font-style: normal;
  color: #4f9bff;
  font-size: 12px;
}
.prepare-target-option {
  display: flex;
  align-items: center;
  gap: 8px;
}
.prepare-target-option-icon {
  color: #4f9bff;
  font-size: 13px;
}
.prepare-target-option-name {
  font-weight: 500;
}
.prepare-target-option-model {
  margin-left: auto;
  padding: 0 6px;
  font-size: 11px;
  color: #9fb0c3;
  background: rgba(79, 155, 255, 0.14);
  border-radius: 4px;
}
.prepare-target-option-model.offline {
  color: #9aa4b0;
  background: rgba(140, 140, 140, 0.18);
}
.delivery-task-badge {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 12px;
  padding: 7px 10px;
  border-radius: 6px;
  font-size: 12px;
  color: #9fe0b6;
  background: rgba(67, 214, 117, 0.12);
  border: 1px solid rgba(67, 214, 117, 0.32);
}
.delivery-task-badge b {
  color: #d6ffe6;
  font-weight: 600;
}
.delivery-task-refresh {
  flex: 0 0 auto;
  color: #6fd69a;
  cursor: pointer;
}
.delivery-task-refresh:hover {
  color: #b7ffd4;
}
.delivery-task-refresh.loading {
  opacity: 0.5;
  pointer-events: none;
}
/* 两行设备项：第一行 机型+在线状态，第二行 SN */
.delivery-device-option {
  padding: 2px 0;
  line-height: 1.4;
}
.delivery-device-option-top {
  display: flex;
  align-items: center;
  gap: 8px;
}
.delivery-device-option-sn {
  margin-top: 2px;
  font-size: 11px;
  color: #8b97a6;
  font-family: 'SFMono-Regular', Consolas, monospace;
}
/* 让两行下拉项有足够高度 */
.prepare-target-popover :deep(.delivery-device-select.ant-select .ant-select-item-option-content),
.prepare-target-popover :deep(.ant-select-item-option-content) {
  white-space: normal;
}
.delivery-terminal {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid #2c3a4f;
}
.delivery-terminal-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
  color: #c7d2e0;
  font-size: 12px;
}
.delivery-terminal-head small {
  color: #7d8ca0;
  font-size: 11px;
}
.delivery-terminal-actions {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 6px;
}
.delivery-terminal-actions .ant-btn {
  padding-left: 0;
  padding-right: 0;
}
/* 下拉框暗色化：dropdown 经 getPopupContainer 渲染在 popover 内，:deep 在本组件作用域内命中 */
.prepare-target-popover :deep(.ant-select-selector) {
  background: #161a20 !important;
  border: 1px solid #2c3a4f !important;
  border-radius: 6px !important;
  color: #fff !important;
  box-shadow: none !important;
}
.prepare-target-popover :deep(.ant-select-selection-placeholder) {
  color: #6b7889 !important;
}
.prepare-target-popover :deep(.ant-select-arrow) {
  color: #7d8ca0 !important;
}
.prepare-target-popover :deep(.ant-select-focused .ant-select-selector) {
  border-color: #4f9bff !important;
}
.prepare-target-popover :deep(.ant-select-dropdown) {
  background: #1f2329 !important;
  border: 1px solid #2c3a4f !important;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.5) !important;
}
.prepare-target-popover :deep(.ant-select-item-option) {
  color: #c7d2e0 !important;
  border-radius: 4px !important;
}
.prepare-target-popover :deep(.ant-select-item-option-active) {
  background: #232b36 !important;
}
.prepare-target-popover :deep(.ant-select-item-option-selected) {
  background: rgba(79, 155, 255, 0.18) !important;
  color: #fff !important;
}
.create-route-section {
  margin-bottom: 18px;
}
.create-route-section:last-child {
  margin-bottom: 0;
}
.create-route-section-title {
  margin-bottom: 10px;
  color: #cfd8e3;
  font-size: 13px;
}
.create-route-cards {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.create-route-card {
  position: relative;
  width: 98px;
  height: 80px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 7px;
  border: 1px solid #3a4658;
  border-radius: 6px;
  background: #262b33;
  color: #cfd8e3;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s;
}
.create-route-card:hover {
  border-color: #4a82ff;
}
.create-route-card.active {
  border-color: #1668dc;
  background: rgba(22, 104, 220, 0.22);
  color: #fff;
}
.create-route-card.disabled {
  cursor: not-allowed;
  opacity: 0.45;
}
.create-route-card.disabled:hover {
  border-color: #3a4658;
}
.create-route-card-icon {
  font-size: 24px;
}
.create-route-card-badge {
  position: absolute;
  top: 5px;
  right: 5px;
  padding: 0 5px;
  font-size: 10px;
  color: #faad14;
  border: 1px solid rgba(250, 173, 20, 0.5);
  border-radius: 8px;
}
.create-route-hint {
  margin-top: 10px;
  color: #7d8ca0;
  font-size: 12px;
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
.planning-empty {
  padding: 8px;
  border-radius: 3px;
  background: #353535;
  color: #8c8c8c;
  text-align: center;
}
.scrollbar {
  overflow-y: auto;
  overflow-x: hidden;
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
  border: 1px solid transparent;
}
.planned-wayline-card--selected {
  border-color: #19be6b;
  background: #26382f;
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
.planned-wayline-status.failed {
  background: #cf1322;
  color: #fff;
}
.planned-wayline-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
  color: hsla(0, 0%, 100%, 0.65);
  margin-bottom: 5px;
}
.planned-wayline-reason {
  margin-bottom: 5px;
  padding: 4px 6px;
  border-left: 2px solid #cf1322;
  background: rgba(207, 19, 34, 0.16);
  color: #ffccc7;
  font-size: 11px;
  line-height: 1.4;
  word-break: break-word;
}
.planned-wayline-meta.muted {
  color: hsla(0, 0%, 100%, 0.35);
}
.planned-wayline-actions {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  grid-auto-flow: row dense;
  gap: 6px;
  margin-top: 6px;
}
.planned-wayline-actions .ant-btn {
  min-width: 0;
  max-width: 100%;
  white-space: nowrap;
}
.planned-wayline-actions--minimal {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}
.planned-wayline-detail-actions {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  grid-auto-flow: row dense;
  gap: 8px;
  flex-wrap: wrap;
}
.planned-wayline-detail-actions .ant-btn {
  min-width: 0;
  max-width: 100%;
  white-space: nowrap;
}
.wayline-button-wrap {
  grid-column: 1 / -1;
  min-width: 0 !important;
  max-width: 100%;
  height: auto;
  min-height: 24px;
  white-space: normal !important;
  overflow-wrap: anywhere;
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

@media (max-width: 1180px) {
}
.planner-mode-tabs {
  padding: 0 10px;
  :deep(.ant-tabs-nav) {
    margin-bottom: 0;
  }
  :deep(.ant-tabs-tab) {
    color: #8c8c8c;
  }
  :deep(.ant-tabs-tab-active .ant-tabs-tab-btn) {
    color: #4da3ff;
  }
}
</style>
