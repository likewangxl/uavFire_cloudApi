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
  background-position: center;
  background-repeat: no-repeat;
  min-width: 1366px;
  overflow: hidden;
}
.login-card {
  position: absolute;
  right: 80px;
  top: 50%;
  transform: translateY(-50%);
  width: 400px;
  padding: 32px;
  background: rgba(11, 28, 58, 0.85);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  border: 1px solid rgba(64, 158, 255, 0.3);
  border-radius: 12px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.45);
  animation: slideUp 400ms cubic-bezier(0.22, 1, 0.36, 1);
}
@keyframes slideUp {
  from { opacity: 0; transform: translateY(calc(-50% + 20px)); }
  to   { opacity: 1; transform: translateY(-50%); }
}
</style>
