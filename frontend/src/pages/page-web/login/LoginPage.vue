<template>
  <div class="login-page">
    <div class="login-card">
      <LoginForm ref="formRef" @submit="onSubmit" @demo="onDemo" />
    </div>
  </div>
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
.login-page {
  position: fixed;
  inset: 0;
  background-image: url('/@/assets/login-bg.png');
  background-size: cover;
  background-position: center bottom;
  background-repeat: no-repeat;
  display: flex;
  overflow-y: auto;
  padding: 24px 16px;
}
.login-card {
  width: 400px;
  max-width: 100%;
  // 上下 auto 保证垂直居中且高于视口时可滚动；左 auto 右 0 让卡片始终贴右侧，
  // 不遮挡左侧品牌视觉（手机宽度下卡片占满整行，观感即居中）
  margin: auto 0 auto auto;
  padding: 32px;
  background: rgba(11, 28, 58, 0.85);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  border: 1px solid rgba(64, 158, 255, 0.3);
  border-radius: 12px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.45);
  animation: slideUp 400ms cubic-bezier(0.22, 1, 0.36, 1);
}
// 宽屏保持原设计的 80px 右边距，窄屏收窄为 16px
@media (min-width: 1200px) {
  .login-page { padding-right: 80px; }
}
@media (max-width: 480px) {
  .login-card { padding: 24px 20px; }
}
@keyframes slideUp {
  from { opacity: 0; transform: translateY(20px); }
  to   { opacity: 1; transform: translateY(0); }
}
</style>
