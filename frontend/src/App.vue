<template>
  <div class="demo-app">
    <div v-if="trialExpired" class="trial-expired" role="alert">
      <div class="trial-expired__card">
        <div class="trial-expired__eyebrow">TRIAL EXPIRED</div>
        <h1>试用期已结束</h1>
        <p>本试用版本已于 {{ trialExpiresAtDisplay }} 到期，系统已停止提供服务。</p>
        <p class="trial-expired__contact">如需继续使用，请联系软件供应方获取正式授权。</p>
      </div>
    </div>
    <router-view v-else />
    <!-- <div class="map-wrapper">
      <GMap/>
    </div> -->
  </div>
</template>

<script lang="ts">
import { defineComponent, onBeforeUnmount, onMounted, ref } from 'vue'
import { CURRENT_CONFIG } from '/@/api/http/config'
import { resolveTrialDecision } from '/@/trial/trial-expiration-policy.mjs'

export default defineComponent({
  name: 'App',

  setup () {
    const trialExpired = ref(false)
    const trialExpiresAtDisplay = ref('正在确认试用授权')
    let expirationTimer: number | undefined
    let expiresAtMs: number | null = null

    const applyDecision = (decision: { expired: boolean, display: string, expiresAtMs: number | null }) => {
      trialExpired.value = decision.expired
      trialExpiresAtDisplay.value = decision.display
      expiresAtMs = decision.expiresAtMs
    }

    const refreshTrialState = async () => {
      try {
        const response = await fetch(`${CURRENT_CONFIG.baseURL}manage/api/v1/trial-status`)
        if (!response.ok) {
          throw new Error('trial status unavailable')
        }
        const body = await response.json()
        applyDecision(resolveTrialDecision(body.data || body))
      } catch (error) {
        if (import.meta.env.PROD) {
          applyDecision({ expired: true, display: '无法确认试用授权，已停止服务', expiresAtMs: null })
        } else {
          applyDecision({ expired: false, display: '开发环境未连接到试用授权接口', expiresAtMs: null })
        }
      }
    }

    const scheduleExpirationCheck = async () => {
      await refreshTrialState()
      if (trialExpired.value || expiresAtMs == null) return
      const remainingMs = expiresAtMs - Date.now()
      expirationTimer = window.setTimeout(
        () => { scheduleExpirationCheck() },
        Math.min(Math.max(remainingMs, 1), 60 * 60 * 1000),
      )
    }

    const onFocus = () => { refreshTrialState() }
    const onVisibility = () => { refreshTrialState() }

    onMounted(() => {
      scheduleExpirationCheck()
      window.addEventListener('focus', onFocus)
      document.addEventListener('visibilitychange', onVisibility)
    })

    onBeforeUnmount(() => {
      if (expirationTimer !== undefined) window.clearTimeout(expirationTimer)
      window.removeEventListener('focus', onFocus)
      document.removeEventListener('visibilitychange', onVisibility)
    })

    return {
      trialExpired,
      trialExpiresAtDisplay,
    }
  }
})
</script>
<style lang="scss" scoped>
.demo-app {
  width: 100%;
  height: 100%;

  .map-wrapper {
    height: 100%;
    width: 100%;
  }
}

.trial-expired {
  align-items: center;
  background:
    radial-gradient(circle at 20% 20%, rgb(19 87 147 / 32%), transparent 36%),
    linear-gradient(145deg, #061326, #0b2340 58%, #071525);
  color: #eaf5ff;
  display: flex;
  height: 100%;
  justify-content: center;
  padding: 32px;
  text-align: center;
  width: 100%;
}

.trial-expired__card {
  background: rgb(8 25 46 / 88%);
  border: 1px solid rgb(76 174 255 / 35%);
  border-radius: 12px;
  box-shadow: 0 24px 80px rgb(0 0 0 / 35%);
  max-width: 680px;
  padding: 54px 64px;
}

.trial-expired__eyebrow {
  color: #5ecbff;
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.22em;
  margin-bottom: 14px;
}

.trial-expired h1 {
  color: #fff;
  font-size: 34px;
  margin: 0 0 22px;
}

.trial-expired p {
  font-size: 17px;
  line-height: 1.8;
  margin: 0;
}

.trial-expired__contact {
  color: #9cb6cf;
  margin-top: 12px !important;
}
</style>

<style lang="scss">
#demo-app {
  width: 100%;
  height: 100%
}
</style>
