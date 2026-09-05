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
import {
  isTrialExpired,
  TRIAL_EXPIRES_AT_DISPLAY,
  TRIAL_EXPIRES_AT_EPOCH_MS,
} from '/@/trial/trial-expiration-policy.mjs'

export default defineComponent({
  name: 'App',

  setup () {
    const trialExpired = ref(isTrialExpired())
    let expirationTimer: number | undefined

    const refreshTrialState = () => {
      trialExpired.value = isTrialExpired()
    }

    const scheduleExpirationCheck = () => {
      refreshTrialState()
      if (trialExpired.value) return
      const remainingMs = TRIAL_EXPIRES_AT_EPOCH_MS - Date.now()
      expirationTimer = window.setTimeout(
        scheduleExpirationCheck,
        Math.min(Math.max(remainingMs, 1), 60 * 60 * 1000),
      )
    }

    onMounted(() => {
      scheduleExpirationCheck()
      window.addEventListener('focus', refreshTrialState)
      document.addEventListener('visibilitychange', refreshTrialState)
    })

    onBeforeUnmount(() => {
      if (expirationTimer !== undefined) window.clearTimeout(expirationTimer)
      window.removeEventListener('focus', refreshTrialState)
      document.removeEventListener('visibilitychange', refreshTrialState)
    })

    return {
      trialExpired,
      trialExpiresAtDisplay: TRIAL_EXPIRES_AT_DISPLAY,
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
