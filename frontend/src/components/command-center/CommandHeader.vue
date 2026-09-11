<template>
  <header class="cc-header">
    <router-link class="cc-brand" to="/leadership-cockpit"><DeploymentUnitOutlined /><span>无人机火情智巡<small>{{ workspaceName || 'UAVFIRE · 巡护指挥台' }}</small></span></router-link>
    <nav class="cc-main-nav" aria-label="主导航"><router-link v-for="item in sections" :key="item.key" :to="item.path" :class="{active: current === item.key}" :aria-current="current === item.key ? 'page' : undefined"><component :is="icons[item.icon]" /><span>{{ item.label }}</span></router-link></nav>
    <div class="cc-account"><router-link to="/media" title="平台管理" aria-label="平台管理" :class="{active:current==='management'}"><SettingOutlined /></router-link><a-dropdown><button class="cc-user" type="button" aria-label="账户菜单"><UserOutlined /><span>{{ username || '当前账户' }}</span></button><template #overlay><a-menu><a-menu-item @click="logout">退出登录</a-menu-item></a-menu></template></a-dropdown></div>
  </header>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { DeploymentUnitOutlined, DashboardOutlined, VideoCameraOutlined, FireOutlined, ScheduleOutlined, AppstoreOutlined, SettingOutlined, UserOutlined } from '@ant-design/icons-vue'
import { ELocalStorageKey } from '/@/types'
import { sections, sectionFor } from './navigation.mjs'
import './command-center.css'
import './legacy-theme.scss'
const route = useRoute(); const router = useRouter()
const icons = { DashboardOutlined, VideoCameraOutlined, FireOutlined, ScheduleOutlined, AppstoreOutlined }
const current = computed(() => sectionFor(route.path, route.query))
const username = localStorage.getItem(ELocalStorageKey.Username)
const workspaceName = localStorage.getItem(ELocalStorageKey.WorkspaceName)
function logout () {
  for (const key of [ELocalStorageKey.Token, ELocalStorageKey.UserId, ELocalStorageKey.WorkspaceId, ELocalStorageKey.Username, ELocalStorageKey.Flag]) localStorage.removeItem(key)
  router.push('/project')
}
</script>
