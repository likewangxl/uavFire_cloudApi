<template>
  <div class="livestream-sidebar">
    <div class="livestream-header">
      <a-row>
        <a-col :span="1"></a-col>
        <a-col :span="22">直播画面</a-col>
        <a-col :span="1"></a-col>
      </a-row>
    </div>

    <div class="livestream-hint">
      WebRTC/ZLM 直播现已显示在工作台右侧面板。下方入口保留给第二阶段传输方式，仍沿用旧版演示窗口。
    </div>

    <div class="livestream-phase2">
      <div class="phase2-label">第二阶段（旧版演示）</div>
      <router-link
        v-for="item in phase2Options"
        :key="item.key"
        style="width: 90%; margin: auto;"
        :to="item.path"
        class="menu-item"
      >
        <a-button class="mt10" style="width:100%;" @click="selectLivestream(item.routeName)">
          {{ item.label }}
        </a-button>
      </router-link>
    </div>

    <div class="live" v-if="showLive" v-drag-window>
      <div style="height: 40px; width: 100%" class="drag-title"></div>
      <a
        style="position: absolute; right: 10px; top: 10px; font-size: 16px; color: white;"
        @click="() => root.$router.push('/' + ERouterName.LIVESTREAM)"
      ><CloseOutlined /></a>
      <router-view :name="routeName" />
    </div>
  </div>
</template>

<script lang="ts" setup>
import { onMounted, ref, watch } from 'vue'
import { CloseOutlined } from '@ant-design/icons-vue'
import { getRoot } from '/@/root'
import { ERouterName } from '/@/types'

const root = getRoot()
const routeName = ref<string>('LiveOthers')
const showLive = ref<boolean>(root.$route.name === ERouterName.LIVING)

const phase2Options = [
  { key: 1, label: 'RTMP/GB28181 直播（第二阶段）', path: '/' + ERouterName.LIVESTREAM + '/' + ERouterName.LIVING, routeName: 'LiveOthers' }
]

const selectLivestream = (route: string) => {
  showLive.value = root.$route.name === ERouterName.LIVING
  routeName.value = route
}

onMounted(() => {
  watch(() => root.$route.name, data => {
    showLive.value = data === ERouterName.LIVING
  }, { deep: true })
})
</script>

<style lang="scss">
.full-modal {
  .ant-modal {
    max-width: 100%;
    top: 0;
    padding-bottom: 0;
    margin: 0;
  }
  .ant-modal-content {
    display: flex;
    flex-direction: column;
    height: calc(100vh);
  }
  .ant-modal-body {
    flex: 1;
  }
}
.live {
  position: absolute;
  z-index: 1;
  left: 0;
  top: 10px;
  margin-left: 345px;
  text-align: center;
  width: 800px;
  height: 720px;
  background: #232323;
}
</style>

<style lang="scss" scoped>
.livestream-sidebar {
  height: 100%;
  color: #ffffff;
}

.livestream-header {
  height: 50px;
  line-height: 50px;
  border-bottom: 1px solid #4f4f4f;
  font-weight: 450;
}

.livestream-hint {
  padding: 16px;
  color: #9aa0a6;
  font-size: 13px;
  line-height: 1.6;
}

.livestream-phase2 {
  padding: 0 16px;

  .phase2-label {
    margin: 12px 0 6px;
    color: #9aa0a6;
    font-size: 12px;
    text-transform: uppercase;
    letter-spacing: 0.5px;
  }
}
</style>
