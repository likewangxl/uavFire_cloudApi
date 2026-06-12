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
              accept=".kmz"
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
          <a-tooltip :title="planningOverlayOpen ? '收起航线规划' : '创建新航线'">
            <a-button class="wayline-header-icon-button" type="text" @click="togglePlanningOverlay">
              <MinusOutlined v-if="planningOverlayOpen" />
              <PlusOutlined v-else />
            </a-button>
          </a-tooltip>
        </a-col>
      </a-row>
    </div>
    <div :style="{ height : height + 'px'}" class="scrollbar">
      <a-collapse
        v-if="showPlanningTools"
        class="wayline-mode-collapse"
        :bordered="false"
        expandIconPosition="right"
        accordion
        default-active-key="monitor-wayline"
        style="background: #232323;">
        <a-collapse-panel key="monitor-wayline" header="监测火情航线" style="border-bottom: 1px solid #4f4f4f;">
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
        </a-collapse-panel>
        <a-collapse-panel key="delivery-wayline" header="投放执行航线" style="border-bottom: 1px solid #4f4f4f;">
      <div class="fc100-planning-panel">
        <div class="fc100-planning-title">
          <span>投放执行面板</span>
          <a-button size="small" type="link" :loading="fc100PlanningState.loadingAction === 'devices'" @click="handleFc100RefreshDevices">
            刷新设备
          </a-button>
        </div>
        <div class="planning-row">
          <span class="planning-label">FC100云端设备</span>
          <a-select
            size="small"
            style="width: 100%;"
            :value="fc100PlanningState.selectedDeviceSn"
            placeholder="请选择FC100飞机设备"
            option-label-prop="label"
            :loading="fc100PlanningState.loadingAction === 'devices'"
            @change="handleFc100SelectDevice">
            <a-select-option
              v-for="device in fc100AircraftDevices"
              :key="device.deviceSn"
              :value="device.deviceSn"
              :label="formatFc100DeviceSelectLabel(device)">
              <div class="fc100-device-option">
                <div class="fc100-device-option-main">
                  <span class="fc100-device-option-model">{{ formatFc100DeliveryAircraftModel() }}</span>
                  <span class="fc100-device-option-status" :class="{ online: isFc100DeviceOnline(device) }">
                    {{ isFc100DeviceOnline(device) ? '在线' : '离线' }}
                  </span>
                </div>
                <div class="fc100-device-option-sn">{{ device.deviceSn }}</div>
              </div>
            </a-select-option>
          </a-select>
        </div>
        <div class="fc100-device-props" v-if="fc100PlanningState.selectedDeviceProps">
          <span>电量 {{ fc100PlanningState.selectedDeviceProps.batteryPercent ?? '-' }}%</span>
          <span>RTK {{ fc100PlanningState.selectedDeviceProps.rtkStatus || '-' }}</span>
          <span>{{ fc100PlanningState.selectedDeviceProps.onlineStatus === false ? '离线' : '在线' }}</span>
        </div>
        <div class="planning-row">
          <a-upload
            class="fc100-direct-wayline-upload"
            name="file"
            accept=".kmz,.kml"
            :multiple="false"
            :before-upload="beforeFc100WaylineUpload"
            :show-upload-list="false"
            :custom-request="uploadFc100WaylineFile"
          >
          <a-button
              class="wayline-button-wrap"
              size="small"
              :loading="fc100PlanningState.loadingAction === 'directImport'">
              <SelectOutlined />
              导入FC100任务
            </a-button>
          </a-upload>
        </div>
        <div class="planning-row">
          <span class="planning-label">当前规划航线</span>
          <div class="fc100-selected-wayline">
            {{ fc100SelectedRecordName }}
          </div>
        </div>
        <div class="planning-row planning-actions fc100-task-actions">
          <a-button
            class="wayline-button-wrap"
            size="small"
            type="primary"
            :loading="fc100PlanningState.loadingAction === 'import'"
            :disabled="!fc100PlanningState.selectedRecord || !fc100PlanningState.selectedRecord.kmzUrl"
            @click="handleFc100ImportGeneratedWaylineTask()">
            创建FC100任务
          </a-button>
          <a-button
            size="small"
            :loading="fc100PlanningState.loadingAction === 'start'"
            :disabled="!fc100PlanningState.taskId"
            @click="handleFc100StartGeneratedWaylineTask">
            开始执行
          </a-button>
          <a-button
            size="small"
            :loading="fc100PlanningState.loadingAction === 'status'"
            :disabled="!fc100PlanningState.taskId"
            @click="handleFc100GeneratedWaylineTaskStatus">
            刷新任务
          </a-button>
        </div>
        <div class="fc100-task-summary" v-if="fc100PlanningState.taskId || fc100PlanningState.taskStatus">
          <span>FC100任务ID {{ fc100PlanningState.taskId || '-' }}</span>
          <span>状态 {{ fc100PlanningState.taskStatus?.status || fc100PlanningState.taskStatus?.phase || '-' }}</span>
          <span v-if="fc100PlanningState.taskStatus?.progressPercent !== null && fc100PlanningState.taskStatus?.progressPercent !== undefined">
            进度 {{ fc100PlanningState.taskStatus.progressPercent }}%
          </span>
        </div>
        <div class="fc100-terminal-panel" v-if="fc100PlanningState.taskId">
          <div class="fc100-terminal-head">
            <span>到点后投放控制</span>
            <small>{{ fc100TerminalControlHint }}</small>
          </div>
          <div class="fc100-terminal-note">
            确认航线到达终点并悬停后再操作。
          </div>
          <div class="fc100-terminal-actions">
            <a-button
              size="small"
              class="fc100-terminal-actions__primary"
              :loading="fc100PlanningState.loadingAction === 'ropeDown'"
              :disabled="!canUseFc100TerminalControls() || isFc100TerminalCommandLoading"
              @click="handleFc100RopeDown">
              放绳
            </a-button>
            <a-button
              size="small"
              class="fc100-terminal-actions__neutral"
              :loading="fc100PlanningState.loadingAction === 'ropeStop'"
              :disabled="!canUseFc100TerminalControls() || isFc100TerminalCommandLoading"
              @click="handleFc100RopeStop">
              停止
            </a-button>
            <a-button
              size="small"
              class="fc100-terminal-actions__primary"
              :loading="fc100PlanningState.loadingAction === 'ropeUp'"
              :disabled="!canUseFc100TerminalControls() || isFc100TerminalCommandLoading"
              @click="handleFc100RopeUp">
              收绳
            </a-button>
            <a-button
              size="small"
              class="fc100-terminal-actions__danger"
              :loading="fc100PlanningState.loadingAction === 'releaseHook'"
              :disabled="!canUseFc100TerminalControls() || isFc100TerminalCommandLoading"
              @click="handleFc100ReleaseHook">
              脱钩
            </a-button>
            <a-button
              size="small"
              class="fc100-terminal-actions__return"
              :loading="fc100PlanningState.loadingAction === 'returnHome'"
              :disabled="!getSelectedFc100DeviceSn() || isFc100TerminalCommandLoading"
              @click="handleFc100ReturnHome">
              返航
            </a-button>
          </div>
        </div>
        <div class="fc100-result" v-if="fc100PlanningState.lastResult">
          {{ fc100PlanningState.lastResult }}
        </div>
      </div>
      <div class="planning-section-gap"></div>
      <div class="planned-wayline-panel planned-wayline-panel--delivery">
        <div class="planned-wayline-title">
          <span>FC100 投放航线库</span>
          <a-button size="small" type="link" :loading="plannedWaylinesLoading" @click="refreshPlannedWaylines">
            刷新
          </a-button>
        </div>
        <div class="planning-empty" v-if="!plannedWaylinesLoading && plannedWaylinesData.data.length === 0">
          暂无可用于投放的已保存规划航线。
        </div>
        <div v-else class="planned-wayline-list" @scroll="onPlannedWaylinesScroll">
          <div
            class="planned-wayline-card"
            :class="{ 'planned-wayline-card--selected': fc100PlanningState.selectedRecord?.plannedWaylineId === record.plannedWaylineId }"
            v-for="record in plannedWaylinesData.data"
            :key="`fc100-${record.plannedWaylineId}`"
            @click="onFc100PreviewGeneratedWayline(record)">
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
            <div class="planned-wayline-meta muted">
              <span>机型 {{ formatFc100DeliveryAircraftModel() }}</span>
              <span>更新于 {{ formatTimestamp(record.updateTime) }}</span>
            </div>
            <div class="planned-wayline-actions planned-wayline-actions--minimal">
              <a-button size="small" @click.stop="showPlannedWaylineDetail(record)">详情</a-button>
              <a-button
                size="small"
                type="primary"
                :disabled="!record.kmzUrl"
                @click.stop="selectFc100GeneratedWayline(record)">
                选择
              </a-button>
              <a-button size="small" danger @click.stop="onDeletePlannedWayline(record)">删除</a-button>
            </div>
          </div>
          <div class="planned-wayline-list-footer" v-if="plannedWaylinesLoading">加载中...</div>
          <div class="planned-wayline-list-footer" v-else-if="plannedWaylinesData.data.length > 0 && !plannedWaylinesCanRefresh">已加载全部</div>
        </div>
      </div>
        </a-collapse-panel>
      </a-collapse>
      <a-collapse
        v-if="showPlanningTools"
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
              @click="action.handler(selectedPlannedWayline)">
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
      <div class="wayline-map-planning-overlay">
        <div v-if="planningOverlayOpen" class="wayline-map-planning-bar">
          <div class="wayline-map-planning-title">
            <span>航线规划</span>
            <a-tooltip title="飞行器可选。可先布点并保存规划航线；生成文件不需要设备，下发准备前再选择或绑定目标机场/飞行器。">
              <QuestionCircleOutlined class="wayline-map-planning-help" />
            </a-tooltip>
          </div>
          <div class="wayline-map-planning-chip">
            <span>高度</span>
            <strong>{{ planningState.defaultHeight }} m</strong>
          </div>
          <div class="wayline-map-planning-chip">
            <span>速度</span>
            <strong>{{ planningState.maxSpeed }} m/s</strong>
          </div>
          <div class="wayline-map-planning-chip">
            <span>航点</span>
            <strong>{{ planningState.waypoints.length }}</strong>
          </div>
          <div class="wayline-map-planning-route-summary">
            <span>航程 {{ formatRouteDistance(planningRouteStats.totalDistanceM) }}</span>
            <span>预计 {{ formatRouteDuration(planningRouteStats.estimatedSeconds) }}</span>
          </div>
          <div class="wayline-map-planning-actions">
            <div class="wayline-map-planning-action-stack">
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
            <a-tooltip :title="planningDrawerOpen ? '收起' : '展开'">
              <a-button class="wayline-map-collapse-button" size="small" @click="planningDrawerOpen = !planningDrawerOpen">
                <DownOutlined />
              </a-button>
            </a-tooltip>
          </div>
        </div>

        <div v-if="planningOverlayOpen && planningDrawerOpen" class="wayline-map-planning-drawer">
          <div class="wayline-map-planning-drawer-head">
            <div>
              <span class="planning-drawer-kicker">当前规划</span>
              <strong>规划参数</strong>
            </div>
            <div class="wayline-map-planning-drawer-actions">
              <a-button size="small" type="link" @click="advancedConfigOpen = !advancedConfigOpen">
                {{ advancedConfigOpen ? '收起高级配置' : '高级配置' }}
              </a-button>
            </div>
          </div>
          <div class="wayline-map-planning-drawer-grid">
            <div class="planning-row planning-field-card">
              <span class="planning-label">高度（米）</span>
              <a-input-number
                size="small"
                style="width: 100%;"
                :min="15"
                :step="1"
                :disabled="planningState.executing"
                :value="planningState.defaultHeight"
                @change="onPlanningDefaultHeightChange" />
            </div>
            <div class="planning-row planning-field-card">
              <span class="planning-label">最大速度（米/秒）</span>
              <a-input-number
                size="small"
                style="width: 100%;"
                :min="2"
                :max="15"
                :step="1"
                :disabled="planningState.executing"
                :value="planningState.maxSpeed"
                @change="onPlanningMaxSpeedChange" />
            </div>
          </div>

          <div class="planning-advanced planning-map-advanced" v-if="advancedConfigOpen">
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

          <div class="wayline-map-planning-content">
            <div class="wayline-map-planning-waypoints">
              <div class="wayline-map-planning-section-title">
                <span>航点（{{ planningState.waypoints.length }}）</span>
                <span v-if="planningState.active">地图点击添加航点中</span>
              </div>
              <div class="planning-empty" v-if="planningState.waypoints.length === 0">
                暂无航点。请开始布点后在地图上点击添加。
              </div>
              <div class="planning-waypoints planning-waypoints--map" v-else>
                <div
                  v-for="(wp, idx) in planningState.waypoints"
                  :key="wp.id"
                  class="planning-wp"
                  :class="{ active: planningState.executing && planningState.currentIndex === idx, selected: planningState.selectedWaypointId === wp.id }"
                  @click="selectPlanningWaypoint(wp.id)">
                  <div class="planning-wp-head">
                    <span class="planning-wp-index">#{{ idx + 1 }}</span>
                    <span class="planning-wp-coord">
                      {{ wp.wgsLat.toFixed(5) }}, {{ wp.wgsLng.toFixed(5) }}
                    </span>
                    <span class="planning-wp-summary">高 {{ formatNumber(wp.height) }}m · 速 {{ formatNumber(wp.speed || planningState.maxSpeed) }}m/s</span>
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

            <div class="wayline-map-planning-command-panel">
              <div class="planning-status" v-if="planningState.statusText">
                <span>{{ planningState.statusText }}</span>
              </div>
              <div class="wayline-map-planning-command-grid">
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
            </div>
          </div>
        </div>

        <div v-else-if="planningOverlayOpen" class="wayline-map-planning-mini">
          <button class="wayline-map-planning-edge-tab" type="button" @click="planningDrawerOpen = true">
            <span>规划 {{ planningState.waypoints.length }}点</span>
            <small>{{ planningState.active ? '布点中' : planningState.executing ? '执行中' : '点击展开' }}</small>
          </button>
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
import { EllipsisOutlined, CameraFilled, UserOutlined, SelectOutlined, QuestionCircleOutlined, ImportOutlined, PlusOutlined, MinusOutlined, DownOutlined } from '@ant-design/icons-vue'
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
  planningRouteStats,
  selectWaypoint as planningSelectWaypoint,
  buildPlannedWaylineBody,
  loadPlannedWayline,
  previewPlannedWayline,
  resetPlanningDraft,
  setFlightPositionFromRecord,
  setFlightPositionFromWgs,
  requestAircraftRecenter,
  setTrackedAircraft,
} from '/@/hooks/use-wayline-planning'
import { getDeviceTopo } from '/@/api/manage'
import { listMsdkDevices, type MsdkDeviceState } from '/@/api/msdk-device'
import { deliveryApi } from '/@/api/fire/delivery'
import type { DeliveryCommandBody, DeliveryCommandRef, DeliveryDeviceDTO, DeliveryDeviceProperties, DeliveryTaskOperationResult, DeliveryTaskStatus } from '/@/api/fire/delivery'
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
const planningDrawerOpen = ref(false)
const planningOverlayOpen = ref(false)
const planningOverlayReady = ref(false)
const expandedWaypointId = ref<string | null>(null)
function togglePlanningOverlay () {
  if (planningOverlayOpen.value) {
    planningOverlayOpen.value = false
    planningDrawerOpen.value = false
    return
  }
  planningOverlayOpen.value = true
  planningDrawerOpen.value = true
}
function toggleWaypointExpand (id: string) {
  expandedWaypointId.value = expandedWaypointId.value === id ? null : id
}
function selectPlanningWaypoint (id: string) {
  planningSelectWaypoint(id)
}
function formatRouteDistance (meters: number) {
  if (!Number.isFinite(meters) || meters <= 0) return '0 m'
  return meters >= 1000 ? `${(meters / 1000).toFixed(2)} km` : `${Math.round(meters)} m`
}
function formatRouteDuration (seconds: number) {
  if (!Number.isFinite(seconds) || seconds <= 0) return '0 s'
  const roundedSeconds = Math.round(seconds)
  if (roundedSeconds < 60) return `${roundedSeconds} s`
  const minutes = Math.floor(roundedSeconds / 60)
  const restSeconds = roundedSeconds % 60
  return restSeconds > 0 ? `${minutes} min ${restSeconds} s` : `${minutes} min`
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

interface FileItem extends File {
  uid?: string;
  status?: string;
  response?: string;
  url?: string;
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
  defaultHeight: 80,
  maxSpeed: 10,
  waypointCount: 0,
})
const fc100PlanningState = reactive({
  devices: [] as DeliveryDeviceDTO[],
  selectedDeviceSn: '',
  selectedDeviceProps: null as DeliveryDeviceProperties | null,
  selectedRecord: null as PlannedWaylineRecord | null,
  taskId: '',
  taskStatus: null as DeliveryTaskStatus | null,
  lastResult: '',
  loadingAction: '',
})
function isFc100AircraftDevice (device: DeliveryDeviceDTO) {
  const bindStatus = String(device.bindStatus || '').toLowerCase()
  const deviceType = String(device.deviceType || '').toLowerCase()
  return bindStatus !== 'rc' && deviceType !== 'rc'
}
const fc100AircraftDevices = computed(() => fc100PlanningState.devices.filter(isFc100AircraftDevice))
const fc100SelectedRecordName = computed(() => {
  const record = fc100PlanningState.selectedRecord
  if (!record) return '请在下方已保存规划航线中选择已生成KMZ的记录。'
  return record.kmzUrl ? record.name : `${record.name}（请先生成航线文件）`
})
function formatFc100DeliveryAircraftModel () {
  return 'DJI FlyCart 100'
}
function formatFc100DeviceSelectLabel (device: DeliveryDeviceDTO) {
  return `${formatFc100DeliveryAircraftModel()} · ${device.deviceSn}`
}
function isFc100DeviceOnline (device: DeliveryDeviceDTO) {
  const online = String(device.online || '').toLowerCase()
  return online === 'true' || online === 'online' || online === '1'
}
const isFc100TerminalCommandLoading = computed(() => [
  'ropeDown',
  'ropeStop',
  'ropeUp',
  'releaseHook',
  'returnHome',
].includes(fc100PlanningState.loadingAction))
const fc100TerminalControlHint = computed(() => getFc100TerminalControlBlockedReason() || '已满足投放控制条件')

let topoTimer: number | null = null
let fc100RealtimeTimer: number | null = null
let fc100RealtimeRefreshing = false

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

function syncFc100DeviceFlightPosition (props: DeliveryDeviceProperties | null) {
  if (!props) return
  const deviceSn = props.deviceSn || fc100PlanningState.selectedDeviceSn
  if (!deviceSn) return
  // FC100 有进行中的投放任务 → 该机认领地图跟踪，挡掉其它(停地)飞机的位置写入。
  if (fc100PlanningState.taskId && !isFc100TaskTerminal(fc100PlanningState.taskStatus)) {
    setTrackedAircraft(deviceSn)
  }
  setFlightPositionFromWgs(deviceSn, props.longitude, props.latitude, {
    height: props.altitude,
    updatedAt: props.osdTimestamp || Date.now(),
  })
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

function onPlanningDefaultHeightChange (value: number | string | null) {
  const n = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(n) || n <= 0) return
  const previousDefaultHeight = Number(planningState.defaultHeight)
  planningState.defaultHeight = n
  planningState.waypoints.forEach(wp => {
    if (!Number.isFinite(previousDefaultHeight) || Number(wp.height) === previousDefaultHeight) {
      wp.height = n
    }
  })
}

function onPlanningMaxSpeedChange (value: number | string | null) {
  const n = typeof value === 'number' ? value : Number(value)
  if (Number.isFinite(n) && n > 0) {
    planningState.maxSpeed = n
  }
}

async function onStartExecution () {
  await refreshOnlineAircrafts()
  const summary = resolvePlanningExecutionTarget()
  if (!summary) {
    message.warning('执行航线前需要选择在线飞行器。')
    return
  }
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

function resolvePrepareTargetDroneSn (record: PlannedWaylineRecord) {
  const selectedSummary = selectedAircraftSn.value ? onlineAircraftMap[selectedAircraftSn.value] : null
  if (selectedSummary?.sn) return selectedSummary.sn
  const recordAircraftSn = getRecordAircraftSn(record)
  if (recordAircraftSn && onlineAircraftMap[recordAircraftSn]) {
    applyPrepareTargetSelection(recordAircraftSn)
    return recordAircraftSn
  }
  const onlyMsdkAircraftSn = getSingleOnlineMsdkAircraft()
  if (onlyMsdkAircraftSn) {
    applyPrepareTargetSelection(onlyMsdkAircraftSn)
    return onlyMsdkAircraftSn
  }
  if (onlineAircrafts.value.length === 1) {
    const onlyAircraft = onlineAircrafts.value[0]
    applyPrepareTargetSelection(onlyAircraft.sn)
    return onlyAircraft.sn
  }
  return ''
}

async function onPreparePlannedWaylineTask (record: PlannedWaylineRecord) {
  clearPlannedWaylineTaskReason(record)
  await refreshOnlineAircrafts()
  const targetDroneSn = resolvePrepareTargetDroneSn(record)
  if (!targetDroneSn) {
    message.warning('检测到多台或未检测到在线飞行器，请先在上方飞行器下拉框选择目标后再下发准备。')
    return
  }
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

async function openExecuteTargetModal (record: PlannedWaylineRecord) {
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

function getPlannedWaylineTaskReason (record: PlannedWaylineRecord | null): string {
  if (!record) return ''
  return normalizePlannedWaylineStatus(record) === PlannedWaylineStatus.FAILED
    ? (record.taskStatusReason || '')
    : ''
}

function clearPlannedWaylineTaskReason (record: PlannedWaylineRecord) {
  record.taskStatusReason = ''
  const cached = plannedWaylinesData.data.find(item => item.plannedWaylineId === record.plannedWaylineId)
  if (cached) cached.taskStatusReason = ''
  if (selectedPlannedWayline.value?.plannedWaylineId === record.plannedWaylineId) {
    selectedPlannedWayline.value.taskStatusReason = ''
  }
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

function getFc100GeneratedWaylineActions (record: PlannedWaylineRecord) {
  return [{
    key: 'fc100-import-generated',
    label: '导入生成KMZ并创建任务',
    primary: false,
    wrap: true,
    disabled: !record.kmzUrl,
    handler: (record: PlannedWaylineRecord) => onFc100UseGeneratedWayline(record),
  }]
}

function getFc100ApiBody (res: any) {
  return res?.data ?? res
}

function formatFc100OperationResult (result: DeliveryTaskOperationResult | null | undefined, fallback: string): string {
  if (!result) return fallback
  return result.displayMessage || result.apiMessage || result.reason || fallback
}

function formatFc100TaskStatus (status: DeliveryTaskStatus | null | undefined): string {
  if (!status) return '未返回任务状态'
  const parts = [
    status.displayMessage || status.message || status.reason || '',
    status.status ? `状态 ${status.status}` : '',
    status.phase ? `阶段 ${status.phase}` : '',
    status.progressPercent !== null && status.progressPercent !== undefined ? `进度 ${status.progressPercent}%` : '',
    status.taskCode !== null && status.taskCode !== undefined ? `任务码 ${status.taskCode}` : '',
  ].filter(Boolean)
  return parts.join('；') || '任务状态已刷新'
}

function getFc100ErrorText (error: any, fallback: string): string {
  return error?.response?.data?.message || error?.message || fallback
}

function getSelectedFc100DeviceSn (): string {
  const selected = fc100AircraftDevices.value.find(device => device.deviceSn === fc100PlanningState.selectedDeviceSn)
  const firstDrone = fc100AircraftDevices.value.find(device => device.bindStatus === 'drone')
  const firstAircraft = fc100AircraftDevices.value[0]
  return selected?.deviceSn || firstDrone?.deviceSn || firstAircraft?.deviceSn || ''
}

function getFc100OperatorId (): string {
  return localStorage.getItem(ELocalStorageKey.Username) || 'web'
}

function getFc100WaylineFileTaskName (filename: string): string {
  const baseName = String(filename || '')
    .replace(/\.(kmz|kml)$/i, '')
    .trim()
  return sanitizeDjiWaylineName(baseName || 'FC100航线', 'FC100航线')
}

function beforeFc100WaylineUpload (file: FileItem) {
  if (!file.name || !/\.(kmz|kml)$/i.test(file.name)) {
    message.error('文件格式错误，请选择 FC100 KMZ/KML 航线文件。')
    return false
  }
  return true
}

const uploadFc100WaylineFile = async (options?: { file?: FileItem; onSuccess?: (res: any) => void; onError?: (err: any) => void }) => {
  const file = options?.file
  if (!file) {
    message.error('请选择 FC100 KMZ/KML 航线文件。')
    return
  }
  const deviceSn = getSelectedFc100DeviceSn()
  if (!deviceSn) {
    message.warning('请先选择FC100飞机设备。')
    options?.onError?.(new Error('FC100 device is required'))
    return
  }
  fc100PlanningState.selectedDeviceSn = deviceSn
  fc100PlanningState.loadingAction = 'directImport'
  const fileData = new FormData()
  fileData.append('file', file, file.name)
  fileData.append('deviceSn', deviceSn)
  fileData.append('taskName', sanitizeDjiWaylineName(getFc100WaylineFileTaskName(file.name), 'FC100航线'))
  fileData.append('operatorId', getFc100OperatorId())
  fileData.append('remark', `created from uploaded fc100 wayline ${file.name}`)
  try {
    const res = await deliveryApi.importCreateWaylineTask(fileData)
    const body = getFc100ApiBody(res)
    if (body.code !== 0) {
      fc100PlanningState.lastResult = `FC100导入航线文件并创建任务失败：${body.message || '接口返回异常'}`
      options?.onError?.(new Error(body.message || 'FC100 direct wayline import failed'))
      return
    }
    fc100PlanningState.taskId = body.data?.taskId || ''
    fc100PlanningState.taskStatus = null
    fc100PlanningState.selectedRecord = null
    fc100PlanningState.lastResult = `FC100任务已创建：${fc100PlanningState.taskId || '未返回任务ID'}`
    message.success('FC100航线文件已导入并创建任务')
    options?.onSuccess?.(res)
  } catch (error) {
    fc100PlanningState.lastResult = `FC100导入航线文件并创建任务失败：${getFc100ErrorText(error, '接口调用失败')}`
    options?.onError?.(error)
  } finally {
    fc100PlanningState.loadingAction = ''
  }
}

function buildFc100CommandBody (data?: Record<string, unknown>): DeliveryCommandBody {
  return {
    operatorId: getFc100OperatorId(),
    data,
  }
}

function isFc100TaskTerminal (status: DeliveryTaskStatus | null): boolean {
  if (!status) return false
  const text = `${status.status || ''} ${status.phase || ''}`.toLowerCase()
  return status.progressPercent === 100 ||
    ['completed', 'complete', 'finished', 'finish', 'success', 'succeeded', 'done'].some(key => text.includes(key))
}

function isFc100HoveringEnough (props: DeliveryDeviceProperties | null): boolean {
  if (!props) return false
  if (props.onlineStatus === false) return false
  const horizontalSpeed = Number(props.horizontalSpeed ?? 0)
  const verticalSpeed = Number(props.verticalSpeed ?? 0)
  return Math.abs(horizontalSpeed) <= 0.5 && Math.abs(verticalSpeed) <= 0.3
}

function getFc100TerminalControlBlockedReason (): string {
  if (!fc100PlanningState.taskId) return '请先创建FC100航线任务'
  if (!isFc100TaskTerminal(fc100PlanningState.taskStatus)) return '等待航线完成'
  if (!fc100PlanningState.selectedDeviceProps) return '请先刷新FC100状态'
  if (fc100PlanningState.selectedDeviceProps.onlineStatus === false) return 'FC100设备离线'
  if (!isFc100HoveringEnough(fc100PlanningState.selectedDeviceProps)) return '等待飞机悬停稳定'
  return ''
}

function canUseFc100TerminalControls (): boolean {
  return !getFc100TerminalControlBlockedReason()
}

function confirmFc100TerminalAction (title: string, actionText: string, danger = false): Promise<boolean> {
  const deviceSn = getSelectedFc100DeviceSn() || '-'
  const status = fc100PlanningState.taskStatus?.status || fc100PlanningState.taskStatus?.phase || '-'
  return new Promise(resolve => {
    Modal.confirm({
      title,
      content: `设备 ${deviceSn}，当前任务状态 ${status}。请确认飞机已到达终点并处于安全悬停状态后执行${actionText}。`,
      okText: `确认${actionText}`,
      cancelText: '取消',
      okButtonProps: danger ? { danger: true } : undefined,
      onOk: () => resolve(true),
      onCancel: () => resolve(false),
    })
  })
}

function formatFc100CommandResult (result: DeliveryCommandRef | null | undefined, fallback: string): string {
  if (!result) return fallback
  const parts = [
    result.deviceCmdMethod ? `方法 ${result.deviceCmdMethod}` : '',
    result.status ? `状态 ${result.status}` : '',
    result.bid ? `指令 ${result.bid}` : '',
  ].filter(Boolean)
  return parts.length ? `${fallback}：${parts.join('；')}` : fallback
}

async function sendFc100TerminalCommand (
  loadingAction: string,
  actionText: string,
  danger: boolean,
  request: (deviceSn: string, body: DeliveryCommandBody) => Promise<any>,
) {
  const deviceSn = getSelectedFc100DeviceSn()
  if (!deviceSn) {
    message.warning('请先选择FC100飞机设备。')
    return
  }
  if (loadingAction !== 'returnHome' && !canUseFc100TerminalControls()) {
    const reason = getFc100TerminalControlBlockedReason()
    message.warning(reason || '当前状态不允许投放控制。')
    return
  }
  const confirmed = await confirmFc100TerminalAction(`确认执行${actionText}吗？`, actionText, danger)
  if (!confirmed) return
  fc100PlanningState.loadingAction = loadingAction
  try {
    const res = await request(deviceSn, buildFc100CommandBody())
    const body = getFc100ApiBody(res)
    if (body.code !== 0) {
      fc100PlanningState.lastResult = `${actionText}失败：${body.message || '接口返回异常'}`
      return
    }
    fc100PlanningState.lastResult = formatFc100CommandResult(body.data, `${actionText}指令已发送`)
    message.success(`${actionText}指令已发送`)
    await refreshFc100SelectedDeviceProps(deviceSn)
  } catch (error) {
    fc100PlanningState.lastResult = `${actionText}失败：${getFc100ErrorText(error, '接口调用失败')}`
  } finally {
    fc100PlanningState.loadingAction = ''
  }
}

function handleFc100RopeDown () {
  return sendFc100TerminalCommand('ropeDown', '放绳', false, deliveryApi.sendFc100RopeDownCommand)
}

function handleFc100RopeStop () {
  return sendFc100TerminalCommand('ropeStop', '停止放收绳', false, deliveryApi.sendFc100RopeStopCommand)
}

function handleFc100RopeUp () {
  return sendFc100TerminalCommand('ropeUp', '收绳', false, deliveryApi.sendFc100RopeUpCommand)
}

function handleFc100ReleaseHook () {
  return sendFc100TerminalCommand('releaseHook', '脱钩', true, deliveryApi.sendFc100ReleaseHookCommand)
}

function handleFc100ReturnHome () {
  return sendFc100TerminalCommand('returnHome', '返航', true, (deviceSn, body) =>
    deliveryApi.sendDeviceCommand(deviceSn, 'return_home', body))
}

async function handleFc100RefreshDevices () {
  fc100PlanningState.loadingAction = 'devices'
  try {
    const res = await deliveryApi.listDevices(workspaceId)
    const body = getFc100ApiBody(res)
    if (body.code !== 0) {
      fc100PlanningState.lastResult = `FC100设备列表获取失败：${body.message || '接口返回异常'}`
      return
    }
    fc100PlanningState.devices = body.data || []
    if (!fc100AircraftDevices.value.some(device => device.deviceSn === fc100PlanningState.selectedDeviceSn)) {
      fc100PlanningState.selectedDeviceSn = fc100AircraftDevices.value.find(device => device.bindStatus === 'drone')?.deviceSn ||
        fc100AircraftDevices.value[0]?.deviceSn ||
        ''
    }
    if (fc100PlanningState.selectedDeviceSn) {
      await handleFc100SelectDevice(fc100PlanningState.selectedDeviceSn)
    } else {
      fc100PlanningState.selectedDeviceProps = null
      fc100PlanningState.lastResult = 'FC100设备列表为空，请确认飞机已绑定到当前FC100 workspace/group。'
    }
  } catch (error) {
    fc100PlanningState.lastResult = `FC100设备列表获取失败：${getFc100ErrorText(error, '接口调用失败')}`
  } finally {
    fc100PlanningState.loadingAction = ''
  }
}

async function handleFc100SelectDevice (deviceSn: string) {
  fc100PlanningState.selectedDeviceSn = deviceSn
  if (!deviceSn) {
    fc100PlanningState.selectedDeviceProps = null
    return
  }
  // 用户选择 FC100 设备即设为跟踪目标（进行中的任务会在轮询里持续覆盖）。
  setTrackedAircraft(deviceSn)
  await refreshFc100SelectedDeviceProps(deviceSn)
}

async function refreshFc100SelectedDeviceProps (deviceSn = fc100PlanningState.selectedDeviceSn) {
  if (!deviceSn) return null
  const res = await deliveryApi.deviceProps(deviceSn)
  const body = getFc100ApiBody(res)
  if (body.code !== 0) {
    fc100PlanningState.lastResult = `FC100设备物模型获取失败：${body.message || '接口返回异常'}`
    return null
  }
  fc100PlanningState.selectedDeviceProps = body.data || null
  syncFc100DeviceFlightPosition(fc100PlanningState.selectedDeviceProps)
  return fc100PlanningState.selectedDeviceProps
}

async function refreshFc100TaskStatus (showLoading = true) {
  const taskId = fc100PlanningState.taskId
  if (!taskId) return null
  if (showLoading) {
    fc100PlanningState.loadingAction = 'status'
  }
  try {
    const res = await deliveryApi.waylineTaskStatus(taskId)
    const body = getFc100ApiBody(res)
    if (body.code !== 0) {
      fc100PlanningState.lastResult = `FC100任务状态获取失败：${body.message || '接口返回异常'}`
      return null
    }
    fc100PlanningState.taskStatus = body.data || null
    fc100PlanningState.lastResult = formatFc100TaskStatus(fc100PlanningState.taskStatus)
    return fc100PlanningState.taskStatus
  } catch (error) {
    fc100PlanningState.lastResult = `FC100任务状态获取失败：${getFc100ErrorText(error, '接口调用失败')}`
    return null
  } finally {
    if (showLoading) {
      fc100PlanningState.loadingAction = ''
    }
  }
}

async function refreshFc100RealtimeState () {
  if (fc100RealtimeRefreshing) return
  if (!showPlanningTools.value) return
  fc100RealtimeRefreshing = true
  try {
    if (fc100PlanningState.selectedDeviceSn) {
      await refreshFc100SelectedDeviceProps()
    }
    if (fc100PlanningState.taskId) {
      await refreshFc100TaskStatus(false)
    }
  } catch (error) {
    // Realtime refresh stays quiet; explicit refresh/actions still show errors.
  } finally {
    fc100RealtimeRefreshing = false
  }
}

function startFc100RealtimeRefresh () {
  if (fc100RealtimeTimer !== null) return
  fc100RealtimeTimer = window.setInterval(refreshFc100RealtimeState, 3000)
}

function stopFc100RealtimeRefresh () {
  if (fc100RealtimeTimer === null) return
  window.clearInterval(fc100RealtimeTimer)
  fc100RealtimeTimer = null
  fc100RealtimeRefreshing = false
}

function buildFc100StartPreflightWarnings (props: DeliveryDeviceProperties | null) {
  const warnings: string[] = []
  if (!props) {
    warnings.push('未获取到飞行器状态')
    return warnings
  }
  if (props.onlineStatus === false) warnings.push('飞行器离线')
  if (props.batteryPercent !== null && props.batteryPercent !== undefined && props.batteryPercent < 30) warnings.push('电量低于30%')
  if (!props.rtkStatus) warnings.push('RTK/GPS状态未知')
  if (props.latitude === null || props.latitude === undefined || props.longitude === null || props.longitude === undefined) warnings.push('未获取到经纬度')
  return warnings
}

async function onFc100UseGeneratedWayline (record: PlannedWaylineRecord) {
  fc100PlanningState.selectedRecord = record
  previewPlannedWayline(record)
  if (!fc100PlanningState.devices.length) {
    await handleFc100RefreshDevices()
  }
  await handleFc100ImportGeneratedWaylineTask(record)
}

function onFc100PreviewGeneratedWayline (record: PlannedWaylineRecord) {
  fc100PlanningState.selectedRecord = record
  previewPlannedWayline(record)
}

function selectFc100GeneratedWayline (record: PlannedWaylineRecord) {
  fc100PlanningState.selectedRecord = record
  previewPlannedWayline(record)
  if (!record.kmzUrl) {
    message.warning('请先生成航线文件后再创建FC100任务。')
  }
}

async function handleFc100ImportGeneratedWaylineTask (record = fc100PlanningState.selectedRecord) {
  if (!record) {
    message.warning('请先选择已保存规划航线。')
    return
  }
  if (!record.kmzUrl) {
    message.warning('请先生成航线文件后再导入FC100。')
    return
  }
  const deviceSn = getSelectedFc100DeviceSn()
  if (!deviceSn) {
    message.warning('请先选择FC100飞机设备。')
    return
  }
  fc100PlanningState.selectedRecord = record
  fc100PlanningState.selectedDeviceSn = deviceSn
  fc100PlanningState.loadingAction = 'import'
  try {
    const res = await deliveryApi.importGeneratedPlannedWaylineTask({
      workspaceId,
      plannedWaylineId: record.plannedWaylineId,
      deviceSn,
      taskName: sanitizeDjiWaylineName(record.name || 'FC100规划航线'),
      operatorId: localStorage.getItem(ELocalStorageKey.Username) || 'web',
      remark: `created from planned wayline ${record.plannedWaylineId}`,
    })
    const body = getFc100ApiBody(res)
    if (body.code !== 0) {
      fc100PlanningState.lastResult = `FC100导入生成KMZ并创建任务失败：${body.message || '接口返回异常'}`
      return
    }
    fc100PlanningState.taskId = body.data?.taskId || ''
    fc100PlanningState.taskStatus = null
    fc100PlanningState.lastResult = `FC100任务已创建：${fc100PlanningState.taskId || '未返回任务ID'}`
    message.success('FC100航线任务已创建')
  } catch (error) {
    fc100PlanningState.lastResult = `FC100导入生成KMZ并创建任务失败：${getFc100ErrorText(error, '接口调用失败')}`
  } finally {
    fc100PlanningState.loadingAction = ''
  }
}

async function handleFc100StartGeneratedWaylineTask () {
  const taskId = fc100PlanningState.taskId
  if (!taskId) {
    message.warning('请先创建FC100任务。')
    return
  }
  const deviceSn = getSelectedFc100DeviceSn()
  if (!deviceSn) {
    message.warning('请先选择FC100飞机设备。')
    return
  }
  fc100PlanningState.loadingAction = 'start'
  try {
    const props = await refreshFc100SelectedDeviceProps(deviceSn)
    const warnings = buildFc100StartPreflightWarnings(props)
    if (warnings.length) {
      const text = `FC100 开始执行航线前检查未通过：${warnings.join('；')}`
      fc100PlanningState.lastResult = text
      message.warning(text)
      return
    }
    const res = await deliveryApi.startWaylineTask(taskId, deviceSn)
    const body = getFc100ApiBody(res)
    if (body.code !== 0) {
      fc100PlanningState.lastResult = `FC100开始执行失败：${body.message || '接口返回异常'}`
      return
    }
    const operation = body.data as DeliveryTaskOperationResult | null
    fc100PlanningState.lastResult = formatFc100OperationResult(operation, 'FC100开始执行航线指令已发送。')
    if (operation?.accepted === false) {
      message.warning(fc100PlanningState.lastResult)
    } else {
      message.success('FC100开始执行航线指令已发送')
    }
    await handleFc100GeneratedWaylineTaskStatus()
  } catch (error) {
    fc100PlanningState.lastResult = `FC100开始执行失败：${getFc100ErrorText(error, '接口调用失败')}`
  } finally {
    fc100PlanningState.loadingAction = ''
  }
}

async function handleFc100GeneratedWaylineTaskStatus () {
  if (!fc100PlanningState.taskId) {
    message.warning('请先创建FC100任务。')
    return
  }
  await refreshFc100TaskStatus(true)
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
    handleFc100RefreshDevices().catch(() => {})
    startFc100RealtimeRefresh()
    topoTimer = window.setInterval(refreshOnlineAircrafts, 5000)
  }
  // 每次进入航线页面：请求地图以飞机当前位置为中心（飞机位置就绪后由 GMap 居中一次）。
  requestAircraftRecenter()
})

onUnmounted(() => {
  stopFc100RealtimeRefresh()
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
  if (!file.name || !file.name.toLowerCase().endsWith('.kmz')) {
    message.error('文件格式错误，请选择 KMZ 文件。')
    return false
  }
  return true
}

const uploadFile = async (options?: { file?: FileItem; onSuccess?: (res: any) => void; onError?: (err: any) => void }) => {
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

.wayline-map-planning-overlay {
  position: absolute;
  inset: 0;
  z-index: 1;
  pointer-events: none;
  color: #d9d9d9;
  font-size: 12px;
}
.wayline-map-planning-overlay :deep(.ant-btn) {
  font-size: 14px;
}
.wayline-map-planning-overlay :deep(.ant-btn-sm) {
  font-size: 14px;
}
.wayline-map-planning-bar {
  display: grid;
  position: absolute;
  top: 16px;
  left: 18px;
  right: auto;
  width: min(480px, calc(100% - 36px));
  min-height: 48px;
  grid-template-columns: max-content repeat(3, minmax(58px, 1fr)) auto;
  gap: 8px;
  align-items: center;
  padding: 8px 10px;
  background: rgba(31, 31, 31, 0.74);
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 6px;
  box-shadow: 0 10px 28px rgba(0, 0, 0, 0.24);
  pointer-events: auto;
}
.wayline-map-planning-title {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 6px;
  margin-right: 8px;
  color: #f5f5f5;
  font-size: 14px;
  font-weight: 700;
}
.wayline-map-planning-help {
  flex: 0 0 auto;
  color: #8c8c8c;
}
.wayline-map-planning-chip {
  min-width: 0;
  padding: 6px 8px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.07);
}
.wayline-map-planning-chip span,
.planning-drawer-kicker {
  display: block;
  color: rgba(255, 255, 255, 0.48);
  font-size: 11px;
  line-height: 1.2;
}
.wayline-map-planning-chip strong {
  display: block;
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.wayline-map-planning-actions {
  grid-column: 5;
  grid-row: 1 / span 2;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
}
.wayline-map-planning-route-summary {
  grid-column: 2 / 5;
  grid-row: 2;
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
  color: rgba(255, 255, 255, 0.7);
  font-size: 11px;
  line-height: 1.2;
}
.wayline-map-planning-route-summary span {
  min-width: 0;
  white-space: nowrap;
}
.wayline-map-planning-action-stack {
  display: grid;
  grid-template-columns: minmax(76px, 1fr);
  gap: 5px;
}
.wayline-map-planning-action-stack .ant-btn {
  min-width: 0;
  height: 24px;
  padding: 0 8px;
}
.wayline-map-collapse-button {
  width: 30px;
  height: 30px;
  padding: 0;
  color: #fff;
  background: rgba(255, 255, 255, 0.08);
  border-color: rgba(255, 255, 255, 0.22);
}
.wayline-map-collapse-button:hover,
.wayline-map-collapse-button:focus {
  color: #fff;
  background: rgba(255, 255, 255, 0.14);
  border-color: rgba(255, 255, 255, 0.35);
}
.wayline-map-planning-drawer,
.wayline-map-planning-mini {
  position: absolute;
  background: rgba(31, 31, 31, 0.74);
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 6px;
  box-shadow: 0 10px 28px rgba(0, 0, 0, 0.24);
  pointer-events: auto;
}
.wayline-map-planning-drawer {
  top: 112px;
  left: 18px;
  width: min(480px, calc(100% - 36px));
  max-height: calc(100% - 130px);
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 12px;
}
.wayline-map-planning-drawer-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}
.wayline-map-planning-drawer-head strong {
  display: block;
  color: #fff;
  font-size: 15px;
  line-height: 1.3;
}
.wayline-map-planning-drawer-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.wayline-map-planning-drawer-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 8px;
}
.planning-field-card {
  min-width: 0;
  margin-bottom: 0;
  padding: 8px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.09);
}
.planning-map-advanced {
  max-height: 132px;
  overflow-y: auto;
  margin: 0 0 8px;
}
.wayline-map-planning-content {
  min-height: 0;
  display: grid;
  grid-template-columns: 1fr;
  gap: 10px;
  flex: 1;
}
.wayline-map-planning-waypoints {
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.wayline-map-planning-section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 6px;
  color: #f5f5f5;
  font-weight: 700;
}
.wayline-map-planning-section-title span:last-child {
  color: #faad14;
  font-size: 11px;
  font-weight: 400;
}
.planning-waypoints--map {
  min-height: 0;
  max-height: 220px;
  overflow-y: auto;
  padding-right: 2px;
}
.planning-waypoints--map .planning-wp {
  margin-bottom: 6px;
  cursor: pointer;
}
.planning-waypoints--map .planning-wp.selected {
  border-color: rgba(24, 144, 255, 0.85);
  background: rgba(24, 144, 255, 0.14);
}
.planning-wp-summary {
  margin-left: auto;
  color: rgba(255, 255, 255, 0.48);
  font-size: 11px;
  white-space: nowrap;
}
.wayline-map-planning-command-panel {
  min-width: 0;
  display: flex;
  flex-direction: column;
  justify-content: flex-start;
  gap: 8px;
}
.wayline-map-planning-command-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}
.wayline-map-planning-command-grid .ant-btn {
  min-width: 0;
}
.wayline-map-planning-mini {
  right: 18px;
  bottom: 72px;
  padding: 0;
  background: transparent;
  border: 0;
  box-shadow: none;
}
.wayline-map-planning-edge-tab {
  min-width: 92px;
  min-height: 38px;
  padding: 6px 10px;
  border: 0;
  border-radius: 6px;
  background: rgba(31, 31, 31, 0.74);
  box-shadow: 0 8px 22px rgba(0, 0, 0, 0.24);
  color: #fff;
  cursor: pointer;
}
.wayline-map-planning-edge-tab span,
.wayline-map-planning-edge-tab small {
  display: block;
  line-height: 1.25;
  text-align: center;
}
.wayline-map-planning-edge-tab span {
  font-size: 13px;
  font-weight: 700;
}
.wayline-map-planning-edge-tab small {
  margin-top: 2px;
  color: rgba(255, 255, 255, 0.58);
  font-size: 10px;
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
.workflow-section-note {
  width: 95%;
  margin: 10px auto 0;
  padding: 9px 10px;
  display: flex;
  flex-direction: column;
  gap: 3px;
  border-left: 3px solid #1677ff;
  background: #262c33;
  color: #d9d9d9;
  font-size: 12px;
  line-height: 1.4;
}
.workflow-section-note strong {
  color: #fff;
  font-size: 13px;
}
.workflow-section-note span {
  color: hsla(0, 0%, 100%, 0.62);
}
.workflow-section-note--delivery {
  border-left-color: #19be6b;
  background: #22302b;
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
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 6px;
}
.fc100-task-actions {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}
.fc100-task-actions .ant-btn:not(.wayline-button-wrap) {
  padding-left: 8px;
  padding-right: 8px;
}
.planning-actions .ant-btn {
  min-width: 0;
  max-width: 100%;
  white-space: nowrap;
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
  flex-wrap: wrap;
  gap: 4px;
}
.planning-wp-tail .ant-btn {
  flex: 0 1 auto;
  min-width: 28px;
  height: auto;
  min-height: 24px;
  white-space: normal;
  overflow-wrap: anywhere;
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
.fc100-planning-panel {
  margin: 10px auto 0;
  width: 95%;
  padding: 10px;
  background: #242f2c;
  border: 1px solid rgba(25, 190, 107, 0.28);
  border-radius: 4px;
  color: #d9d9d9;
  font-size: 12px;
}
.fc100-planning-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 13px;
  font-weight: 700;
  color: #f5f5f5;
  margin-bottom: 8px;
}
.fc100-planning-title > span {
  min-width: 0;
}
.fc100-planning-title .ant-btn {
  flex: 0 0 auto;
}
.fc100-direct-wayline-upload {
  display: block;
}
.fc100-direct-wayline-upload .ant-btn {
  width: 100%;
  justify-content: center;
}
.fc100-device-option {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
  padding: 2px 0;
}
.fc100-device-option-main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
}
.fc100-device-option-model {
  min-width: 0;
  color: #262626;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.fc100-device-option-sn {
  color: #8c8c8c;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.fc100-device-option-status {
  flex: 0 0 auto;
  padding: 1px 6px;
  border-radius: 2px;
  background: #f0f0f0;
  color: #8c8c8c;
  font-size: 12px;
}
.fc100-device-option-status.online {
  background: rgba(25, 190, 107, 0.14);
  color: #19be6b;
}
.fc100-device-props,
.fc100-task-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
  margin-bottom: 8px;
  color: hsla(0, 0%, 100%, 0.65);
}
.fc100-selected-wayline {
  min-height: 30px;
  padding: 6px 8px;
  border-radius: 3px;
  background: #1f1f1f;
  color: #bfbfbf;
  word-break: break-word;
}
.fc100-result {
  margin-top: 8px;
  padding: 6px 8px;
  border-left: 2px solid #19be6b;
  border-radius: 3px;
  background: rgba(25, 190, 107, 0.12);
  color: #d9f7be;
  line-height: 1.4;
  word-break: break-word;
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
.fc100-terminal-panel {
  margin-top: 10px;
  padding: 10px;
  border: 1px solid rgba(82, 196, 26, 0.42);
  border-radius: 4px;
  background: rgba(82, 196, 26, 0.08);
}
.fc100-terminal-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  color: #f5f5f5;
  font-weight: 700;
}
.fc100-terminal-head small {
  min-width: 0;
  color: #8c8c8c;
  font-weight: 400;
  text-align: right;
}
.fc100-terminal-note {
  margin-top: 5px;
  color: #bfbfbf;
  font-size: 12px;
  line-height: 1.4;
}
.fc100-terminal-actions {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 6px;
  margin-top: 8px;
}
.fc100-terminal-actions .ant-btn {
  min-width: 0;
  max-width: 100%;
  font-weight: 600;
  white-space: nowrap;
}
.fc100-terminal-actions__primary {
  border-color: #1677ff;
  background: #1677ff;
  color: #fff;
}
.fc100-terminal-actions__neutral {
  border-color: #d9d9d9;
  background: #f5f5f5;
  color: #262626;
}
.fc100-terminal-actions__danger {
  border-color: #ff4d4f;
  background: #ff4d4f;
  color: #fff;
}
.fc100-terminal-actions__return {
  border-color: #faad14;
  background: #faad14;
  color: #1f1f1f;
}
.fc100-terminal-actions__primary[disabled],
.fc100-terminal-actions__neutral[disabled],
.fc100-terminal-actions__danger[disabled],
.fc100-terminal-actions__return[disabled] {
  border-color: #595959;
  background: #f5f5f5;
  color: #bfbfbf;
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
  .wayline-map-planning-bar {
    grid-template-columns: max-content repeat(3, minmax(58px, 1fr)) auto;
  }
  .wayline-map-planning-actions {
    grid-column: 1 / -1;
    justify-content: flex-start;
  }
  .wayline-map-planning-content {
    grid-template-columns: 1fr;
  }
  .wayline-map-planning-drawer {
    top: 148px;
    left: 12px;
    width: min(480px, calc(100% - 24px));
    max-height: calc(100% - 166px);
  }
  .wayline-map-planning-mini {
    right: 12px;
    bottom: 64px;
  }
}
</style>
