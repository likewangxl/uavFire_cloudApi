
import {
  ref,
  watch,
  computed,
  onUnmounted,
} from 'vue'
import { useMyStore } from '/@/store'
import { postDrc } from '/@/api/drc'
import {
  UranusMqtt,
} from '/@/mqtt'

type StatusOptions = {
  status: 'close';
  event?: CloseEvent;
} | {
  status: 'open';
  retryCount: number;
} | {
  status: 'error';
  data?: Error;
} | {
  status: 'pending';
}

export function useConnectMqtt () {
  const store = useMyStore()
  const dockOsdVisible = computed(() => {
    return store.state.osdVisible && store.state.osdVisible.visible && store.state.osdVisible.is_dock
  })
  const mqttState = ref<UranusMqtt | null>(null)

  const resetMqttState = () => {
    mqttState.value?.destroyed()
    mqttState.value = null
    store.commit('SET_MQTT_STATE', null)
    store.commit('SET_CLIENT_ID', '')
  }

  const isExpired = (expireTime: unknown) => {
    const value = Number(expireTime)
    if (!Number.isFinite(value) || value <= 0) {
      return false
    }
    const expiresAt = value > 10_000_000_000 ? value : value * 1000
    return expiresAt <= Date.now()
  }

  // 监听已打开的设备小窗 窗口数量
  watch(() => dockOsdVisible.value, async (val) => {
    // 1.打开小窗
    // 2.设备拥有飞行控制权
    // 3.请求建立mqtt连接的认证信息
    if (val) {
      if (mqttState.value) return
      const result = await postDrc({})
      if (result?.code === 0) {
        const { address, client_id, username, password, expire_time } = result.data
        if (isExpired(expire_time)) {
          resetMqttState()
          window.console.error('DRC MQTT credentials expired before connection.')
          return
        }
        mqttState.value = new UranusMqtt(address, {
          clientId: client_id,
          username,
          password,
        })
        mqttState.value?.initMqtt()
        mqttState.value?.on('onStatus', (statusOptions: StatusOptions) => {
          if (statusOptions.status === 'error') {
            window.console.error('DRC MQTT connection error.', statusOptions.data)
            resetMqttState()
          }
        })
        try {
          await mqttState.value?.waitForConnected()
        } catch (error) {
          window.console.error('DRC MQTT auth connection failed.', error)
          resetMqttState()
          return
        }

        store.commit('SET_MQTT_STATE', mqttState.value)
        store.commit('SET_CLIENT_ID', client_id)
        return
      }
      resetMqttState()
      window.console.error('DRC MQTT auth failed.', result)
      return
    }
    // 关闭所有小窗后
    // 1.销毁mqtt连接重置mqtt状态
    if (mqttState?.value) {
      resetMqttState()
    }
  }, { immediate: true })

  onUnmounted(() => {
    mqttState.value?.destroyed()
  })
}
