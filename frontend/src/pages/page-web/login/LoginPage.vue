<template>
  <main class="login-page">
    <img class="scene-background" src="/@/assets/login/fire-response-panorama-user-v9.png" alt="林区无人机消防合成场景：近景运载机通过长吊索悬挂红色封闭式灭火载荷，远处运载机吊挂红色灭火桶洒水，无现场人员" />
    <div class="scene-shade" aria-hidden="true"></div>
    <div class="scene-aircraft-layer">
      <img class="scene-recon-aircraft" src="/@/assets/login/matrice-4d-rear-transparent-v1.png" alt="远处的灰色 Matrice 4D 系列巡检无人机，侧后方视角，云台朝向远处" />
      <img class="scene-recon-aircraft scene-recon-aircraft--firepoint" src="/@/assets/login/matrice-4d-rear-transparent-v1.png" alt="右侧火点上方的灰色 Matrice 4D 系列巡检无人机，侧后方视角，云台朝向远处" />
    </div>
    <div class="login-layout">
      <section class="login-story" aria-labelledby="platform-title">
        <header class="story-heading">
          <div class="brand-line"><span class="brand-mark" aria-hidden="true">U</span><span>UAVFIRE<span class="brand-divider">/</span>森林消防 · 空地协同</span></div>
          <h1 id="platform-title">无人机消防指挥平台</h1>
          <p class="story-description">看见火情，快速抵达，精准处置。</p>
        </header>
        <footer class="scene-caption">
          <div class="response-flow" aria-label="无人机协同消防场景">
            <span><b>01</b> 火情巡检</span><span><b>02</b> 灭火弹吊运</span><span><b>03</b> 吊桶灭火</span>
          </div>
          <p class="scene-note">设备外观参考 DJI 官方素材 · 灭火桶参考提供素材 · AI 合成场景，非实拍</p>
        </footer>
      </section>
      <div class="login-card">
        <LoginForm ref="formRef" @submit="onSubmit" @demo="onDemo" />
      </div>
    </div>
  </main>
</template>

<script lang="ts" setup>
import { ref, onMounted } from 'vue'
import LoginForm from './components/LoginForm.vue'
import { useLogin } from './composables/use-login'

const formRef = ref<any>(null)
const { onLogin, onDemoLogin, readRememberedUsername } = useLogin()

onMounted(() => {
  const remembered = readRememberedUsername()
  if (remembered) formRef.value?.setUsername(remembered)
})

function onSubmit (payload: any) {
  onLogin(payload, {
    setSubmitting: (v: boolean) => formRef.value?.setSubmitting(v),
    setDemoLoading: (v: boolean) => formRef.value?.setDemoLoading(v),
    refreshCaptcha: () => formRef.value?.refreshCaptcha(),
  })
}

function onDemo () {
  onDemoLogin({
    setSubmitting: (v: boolean) => formRef.value?.setSubmitting(v),
    setDemoLoading: (v: boolean) => formRef.value?.setDemoLoading(v),
    refreshCaptcha: () => formRef.value?.refreshCaptcha(),
  })
}
</script>

<style lang="scss" scoped>
.login-page { position: fixed; inset: 0; overflow-y: auto; background: #10202c; color: #e8eef3; }
.scene-background { position: fixed; inset: 0; width: 100%; height: 100%; object-fit: cover; object-position: center; }
.scene-aircraft-layer { position: fixed; left: 50%; top: 50%; width: max(100vw, 177.6833156vh); aspect-ratio: 1672 / 941; transform: translate(-50%, -50%); pointer-events: none; }
.scene-recon-aircraft { position: absolute; left: 44.4%; top: 13.3%; width: 4.3%; height: auto; transform: translate(-50%, -50%); filter: brightness(2.2); }
.scene-recon-aircraft--firepoint { left: 63.6%; top: 46.2%; width: 5.2%; }
.scene-shade { position: fixed; inset: 0; background: linear-gradient(90deg, rgba(7, 17, 24, .08) 0%, rgba(7, 17, 24, .02) 52%, rgba(8, 22, 34, .8) 76%, rgba(8, 22, 34, .95) 100%), linear-gradient(180deg, rgba(5, 15, 24, .75) 0%, transparent 28%, transparent 77%, rgba(5, 15, 24, .86) 100%); }
.login-layout { position: relative; display: grid; grid-template-columns: minmax(0, 1fr) 380px; gap: 56px; min-height: 100%; padding: 28px 40px; }
.login-story { display: flex; flex-direction: column; justify-content: space-between; min-width: 0; }
.story-heading { text-shadow: 0 2px 14px rgba(0, 0, 0, .5); }
.brand-line { display: flex; align-items: center; gap: 10px; color: #e0e9ea; font-size: 11px; letter-spacing: 1.8px; }
.brand-mark { display: grid; place-items: center; width: 28px; height: 28px; border: 1px solid #b0c2ca; border-radius: 6px; font-size: 17px; font-weight: 700; }
.brand-divider { margin: 0 12px; color: #a6b8c2; }
h1 { margin: 15px 0 8px; color: #f7f9fa; font-size: clamp(25px, 2.25vw, 40px); line-height: 1.25; font-weight: 600; letter-spacing: 2px; }
.story-description { margin: 0; font-size: 13px; color: #e0e8eb; letter-spacing: 1px; }
.scene-caption { padding-top: 24px; }
.response-flow { display: flex; gap: clamp(20px, 4vw, 72px); font-size: 14px; color: #f4f7f8; letter-spacing: 1px; }
.response-flow b { margin-right: 8px; font-size: 11px; color: #c8d9dc; font-weight: 500; }
.scene-note { margin: 14px 0 0; font-size: 10px; color: #c2ced6; }
.login-card { align-self: center; width: 100%; padding: 28px; background: rgba(13, 29, 44, .92); border: 1px solid rgba(170, 192, 209, .24); border-radius: 12px; box-shadow: 0 20px 70px rgba(0, 0, 0, .24); backdrop-filter: blur(12px); -webkit-backdrop-filter: blur(12px); }
.login-card :deep(.ant-checkbox-wrapper) { color: #cfd8e7; }
.login-card :deep(.ant-input::placeholder) { color: #99aabc; }
.login-card :deep(.ant-input-password-icon), .login-card :deep(.ant-input-prefix) { color: #a9bccb; }
.login-card :deep(.btn-primary:disabled) { background: #263d51; border-color: #385067; color: #b0bfcb; }
@media (min-width: 1600px) { .login-layout { grid-template-columns: minmax(0, 1fr) 420px; padding: 44px 64px; gap: 80px; } .login-card { padding: 40px; } }
@media (max-width: 1050px) { .login-layout { grid-template-columns: minmax(0, 1fr) 340px; padding: 24px; gap: 24px; } .login-card { padding: 24px; } .brand-line { font-size: 10px; letter-spacing: 1px; } .response-flow { gap: 16px; font-size: 12px; } h1 { font-size: 25px; } }
@media (max-width: 760px) { .scene-background { object-position: 28% center; } .scene-shade { background: rgba(6, 19, 31, .73); } .login-layout { display: flex; flex-direction: column; align-items: center; gap: 24px; padding: 28px 18px; } .login-story { width: 100%; max-width: 400px; text-align: center; } .brand-line { justify-content: center; } h1 { font-size: 25px; } .scene-caption { display: none; } .login-card { max-width: 400px; padding: 24px 20px; } }
@media (max-height: 650px) and (min-width: 761px) { .login-layout { min-height: 680px; } }
@media (max-width: 760px) { .scene-aircraft-layer { left: 28%; transform: translate(-28%, -50%); } }
</style>
