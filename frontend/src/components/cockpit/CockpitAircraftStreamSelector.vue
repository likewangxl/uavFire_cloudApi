<template>
  <div
    ref="selectorRef"
    class="stream-target-selector"
    :class="{ open: menuOpen, loading: props.loading }"
  >
    <button
      class="stream-target-trigger"
      type="button"
      :disabled="props.loading"
      :aria-expanded="menuOpen"
      aria-haspopup="listbox"
      @click="toggleMenu"
      @keydown.escape="closeMenu"
    >
      <span class="trigger-main">
        <span class="status-dot" :class="selectedStatusClass"></span>
        <span class="trigger-copy">
          <span class="role-label">{{ activeRoleLabel }}</span>
          <strong>{{ selectedPrimaryLabel }}</strong>
          <span v-if="selectedTarget" class="trigger-subtitle">{{ selectedSecondaryLabel }}</span>
        </span>
      </span>
      <span class="trigger-meta">
        <span class="chevron" :class="{ up: menuOpen }"></span>
      </span>
    </button>

    <div v-if="menuOpen" class="stream-target-menu" role="listbox">
      <template v-if="hasTargets">
        <section
          v-for="group in groupedTargets"
          :key="group.role"
          class="target-group"
        >
          <div class="group-label">{{ roleLabel(group.role) }}</div>
          <button
            v-for="target in group.targets"
            :key="target.key"
            class="stream-target-option"
            :class="[
              `status-${target.streamStatus}`,
              {
                active: target.key === props.value,
                offline: !target.online || target.streamStatus === 'offline'
              }
            ]"
            type="button"
            role="option"
            :disabled="!target.online || target.streamStatus === 'offline'"
            :aria-selected="target.key === props.value"
            :aria-disabled="!target.online || target.streamStatus === 'offline'"
            @click="selectTarget(target)"
          >
            <span class="option-status-dot"></span>
            <span class="option-copy">
              <span class="option-title">
                <strong>{{ target.callsign }}</strong>
                <span class="option-sn">{{ fullSnLabel(target.deviceSn) }}</span>
              </span>
              <span v-if="!target.compactLabel" class="option-subtitle">
                {{ streamStatusLabel(target) }}
                <template v-if="target.taskStatus"> · {{ target.taskStatus }}</template>
                <template v-if="target.message"> · {{ target.message }}</template>
              </span>
            </span>
            <span class="option-side">
              <span v-if="hasProgress(target)" class="progress-text">{{ target.progressPercent }}%</span>
              <span class="stream-badges">
                <span class="stream-badge" :class="target.online ? 'online' : 'offline'">
                  {{ target.online ? '在线' : '离线' }}
                </span>
                <span v-if="target.primaryPlayUrl" class="stream-badge">可见光</span>
                <span v-if="target.thermalPlayUrl" class="stream-badge thermal">热成像</span>
              </span>
            </span>
          </button>
        </section>
      </template>

      <div v-else class="stream-target-empty">
        暂无可播放飞行器
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

export interface CockpitStreamTarget {
  key: string
  role: 'fire-monitor' | 'delivery'
  deviceSn: string
  callsign: string
  online: boolean
  taskStatus?: string
  progressPercent?: number
  primaryPlayUrl?: string
  thermalPlayUrl?: string
  streamStatus: 'running' | 'idle' | 'offline' | 'error'
  message?: string
  compactLabel?: boolean
}

type CockpitStreamRole = CockpitStreamTarget['role']

const props = withDefaults(defineProps<{
  value?: string
  targets: CockpitStreamTarget[]
  role: CockpitStreamRole
  loading?: boolean
}>(), {
  value: undefined,
  loading: false
})

const emit = defineEmits(['update:value', 'change'])

const selectorRef = ref<HTMLElement | null>(null)
const menuOpen = ref(false)

const roleLabels: Record<CockpitStreamRole, string> = {
  'fire-monitor': '火情监测飞机',
  delivery: '投放执行飞机'
}

const selectedTarget = computed(() => {
  return props.targets.find(target => target.key === props.value)
})

const selectedPrimaryLabel = computed(() => {
  return selectedTarget.value?.callsign || '选择飞行器'
})

const selectedSecondaryLabel = computed(() => {
  const target = selectedTarget.value
  if (!target) return ''
  const status = streamStatusLabel(target)
  return `${fullSnLabel(target.deviceSn)} · ${status}`
})

const activeRoleLabel = computed(() => {
  return selectedTarget.value ? roleLabel(selectedTarget.value.role) : roleLabel(props.role)
})

const selectedStatusClass = computed(() => {
  const target = selectedTarget.value
  if (!target) return 'status-idle'
  if (!target.online) return 'status-offline'
  return `status-${target.streamStatus}`
})

const groupedTargets = computed(() => {
  const roles = props.role === 'fire-monitor'
    ? ['fire-monitor', 'delivery'] as CockpitStreamRole[]
    : ['delivery', 'fire-monitor'] as CockpitStreamRole[]

  return roles
    .map(role => ({
      role,
      targets: sortStreamTargets(props.targets.filter(target => target.role === role))
    }))
    .filter(group => group.targets.length > 0)
})

const hasTargets = computed(() => groupedTargets.value.length > 0)

function roleLabel (role: CockpitStreamRole) {
  return roleLabels[role]
}

function fullSnLabel (deviceSn: string) {
  if (!deviceSn) return 'SN --'
  return `SN ${deviceSn}`
}

function sortStreamTargets (targets: CockpitStreamTarget[]) {
  return [...targets].sort((a, b) => {
    const onlineDelta = Number(b.online) - Number(a.online)
    if (onlineDelta !== 0) return onlineDelta
    const statusWeight: Record<CockpitStreamTarget['streamStatus'], number> = {
      running: 0,
      idle: 1,
      error: 2,
      offline: 3
    }
    const statusDelta = statusWeight[a.streamStatus] - statusWeight[b.streamStatus]
    if (statusDelta !== 0) return statusDelta
    return a.callsign.localeCompare(b.callsign, 'zh-Hans-CN')
  })
}

function hasProgress (target: CockpitStreamTarget) {
  return typeof target.progressPercent === 'number' && Number.isFinite(target.progressPercent)
}

function streamStatusLabel (target: CockpitStreamTarget) {
  if (!target.online || target.streamStatus === 'offline') return '离线'
  const statusMap: Record<CockpitStreamTarget['streamStatus'], string> = {
    running: '直播中',
    idle: '待播放',
    offline: '离线',
    error: '异常'
  }
  return statusMap[target.streamStatus]
}

function toggleMenu () {
  if (props.loading) return
  menuOpen.value = !menuOpen.value
}

function closeMenu () {
  menuOpen.value = false
}

function selectTarget (target: CockpitStreamTarget) {
  if (!target.online || target.streamStatus === 'offline') return
  emit('update:value', target.key)
  emit('change', target)
  closeMenu()
}

function onDocumentClick (event: MouseEvent) {
  if (!selectorRef.value || selectorRef.value.contains(event.target as Node)) return
  closeMenu()
}

function onDocumentKeydown (event: KeyboardEvent) {
  if (event.key === 'Escape') closeMenu()
}

onMounted(() => {
  document.addEventListener('click', onDocumentClick)
  document.addEventListener('keydown', onDocumentKeydown)
})

onBeforeUnmount(() => {
  document.removeEventListener('click', onDocumentClick)
  document.removeEventListener('keydown', onDocumentKeydown)
})
</script>

<style lang="scss" scoped>
.stream-target-selector {
  position: relative;
  min-width: 260px;
  color: #e8f4ff;
  font-size: 13px;
}

.stream-target-trigger {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  width: 100%;
  min-height: 46px;
  padding: 8px 12px;
  color: inherit;
  cursor: pointer;
  background:
    linear-gradient(180deg, rgba(14, 32, 52, 0.92), rgba(6, 17, 31, 0.88));
  border: 1px solid rgba(103, 184, 255, 0.24);
  border-radius: 8px;
  box-shadow: 0 12px 28px rgba(0, 0, 0, 0.22), inset 0 1px 0 rgba(255, 255, 255, 0.06);
  transition: border-color 0.16s ease, box-shadow 0.16s ease, background 0.16s ease;
}

.stream-target-trigger:hover,
.stream-target-selector.open .stream-target-trigger {
  border-color: rgba(69, 221, 255, 0.48);
  box-shadow: 0 14px 30px rgba(0, 0, 0, 0.28), 0 0 0 1px rgba(69, 221, 255, 0.08) inset;
}

.stream-target-trigger:disabled {
  cursor: wait;
  opacity: 0.68;
}

.trigger-main,
.trigger-meta,
.option-title,
.option-side,
.stream-badges {
  display: flex;
  align-items: center;
}

.trigger-main {
  min-width: 0;
  gap: 10px;
}

.trigger-copy {
  display: grid;
  min-width: 0;
  gap: 2px;
  text-align: left;
}

.role-label,
.group-label,
.option-subtitle,
.trigger-subtitle,
.option-sn {
  color: rgba(192, 218, 241, 0.68);
}

.role-label,
.group-label {
  font-size: 11px;
  letter-spacing: 0;
}

.trigger-copy strong {
  overflow: hidden;
  color: #f5fbff;
  font-weight: 700;
  line-height: 1.25;
  text-overflow: ellipsis;
  white-space: normal;
}

.option-title strong {
  overflow: hidden;
  color: #f5fbff;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.trigger-subtitle {
  overflow: hidden;
  font-size: 11px;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.trigger-meta {
  flex: 0 0 auto;
  gap: 10px;
}

.status-dot,
.option-status-dot {
  flex: 0 0 auto;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: #8293a6;
  box-shadow: 0 0 0 3px rgba(130, 147, 166, 0.14);
}

.status-running,
.status-running .option-status-dot {
  background: #42e29d;
  box-shadow: 0 0 12px rgba(66, 226, 157, 0.7), 0 0 0 3px rgba(66, 226, 157, 0.12);
}

.status-idle,
.status-idle .option-status-dot {
  background: #67b8ff;
  box-shadow: 0 0 10px rgba(103, 184, 255, 0.56), 0 0 0 3px rgba(103, 184, 255, 0.12);
}

.status-offline,
.status-offline .option-status-dot {
  background: #78889a;
  box-shadow: 0 0 0 3px rgba(120, 136, 154, 0.12);
}

.status-error,
.status-error .option-status-dot {
  background: #ff6172;
  box-shadow: 0 0 12px rgba(255, 97, 114, 0.6), 0 0 0 3px rgba(255, 97, 114, 0.12);
}

.chevron {
  width: 7px;
  height: 7px;
  border-right: 1px solid rgba(232, 244, 255, 0.78);
  border-bottom: 1px solid rgba(232, 244, 255, 0.78);
  transform: rotate(45deg) translateY(-2px);
  transition: transform 0.16s ease;
}

.chevron.up {
  transform: rotate(225deg) translateY(-2px);
}

.stream-target-menu {
  position: absolute;
  top: calc(100% + 8px);
  left: 0;
  right: auto;
  z-index: 20;
  width: min(420px, 92vw);
  max-height: 390px;
  padding: 8px;
  overflow: hidden auto;
  background:
    linear-gradient(180deg, rgba(10, 25, 42, 0.98), rgba(4, 12, 22, 0.98));
  border: 1px solid rgba(103, 184, 255, 0.22);
  border-radius: 8px;
  box-shadow: 0 18px 42px rgba(0, 0, 0, 0.34), inset 0 1px 0 rgba(255, 255, 255, 0.05);
  backdrop-filter: blur(14px);
}

.target-group + .target-group {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}

.group-label {
  padding: 4px 7px 7px;
}

.stream-target-option {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-height: 58px;
  padding: 9px 10px;
  color: inherit;
  text-align: left;
  cursor: pointer;
  background: rgba(255, 255, 255, 0.035);
  border: 1px solid transparent;
  border-radius: 7px;
  transition: background 0.14s ease, border-color 0.14s ease;
}

.stream-target-option + .stream-target-option {
  margin-top: 6px;
}

.stream-target-option:hover,
.stream-target-option.active {
  background: rgba(103, 184, 255, 0.1);
  border-color: rgba(103, 184, 255, 0.24);
}

.stream-target-option.offline {
  opacity: 0.72;
  cursor: not-allowed;
}

.stream-target-option:disabled:hover {
  background: rgba(255, 255, 255, 0.035);
  border-color: transparent;
}

.option-copy {
  display: grid;
  min-width: 0;
  gap: 4px;
}

.option-title {
  min-width: 0;
  gap: 8px;
}

.option-sn {
  flex: 0 0 auto;
  font-size: 11px;
}

.option-subtitle {
  overflow: hidden;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.option-side {
  justify-content: flex-end;
  gap: 8px;
}

.progress-text {
  min-width: 36px;
  color: #42e29d;
  font-weight: 700;
  text-align: right;
}

.stream-badges {
  gap: 4px;
}

.stream-badge {
  padding: 2px 6px;
  color: #bfe4ff;
  font-size: 11px;
  line-height: 1.4;
  background: rgba(103, 184, 255, 0.12);
  border: 1px solid rgba(103, 184, 255, 0.2);
  border-radius: 999px;
}

.stream-badge.thermal {
  color: #ffd9b8;
  background: rgba(255, 171, 74, 0.12);
  border-color: rgba(255, 171, 74, 0.22);
}
.stream-badge.online {
  color: #8df0c4;
  background: rgba(64, 224, 160, 0.14);
  border-color: rgba(64, 224, 160, 0.32);
}
.stream-badge.offline {
  color: #ff9ba6;
  background: rgba(255, 96, 112, 0.12);
  border-color: rgba(255, 96, 112, 0.3);
}

.stream-target-empty {
  display: grid;
  min-height: 86px;
  place-items: center;
  color: rgba(192, 218, 241, 0.72);
  border: 1px dashed rgba(103, 184, 255, 0.18);
  border-radius: 7px;
  background: rgba(255, 255, 255, 0.03);
}

@media (max-width: 640px) {
  .stream-target-selector {
    min-width: 220px;
  }

  .stream-target-menu {
    left: 0;
    right: auto;
    width: min(360px, 92vw);
  }

  .stream-target-option {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .option-side {
    grid-column: 2;
    justify-content: flex-start;
  }
}
</style>
