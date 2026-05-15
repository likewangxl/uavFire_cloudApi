<template>
  <div class="login-form">
    <div class="title-row">
      <span class="line" />
      <h2 class="title">系统登录</h2>
      <span class="line" />
    </div>
    <p class="subtitle">欢迎进入无人机消防指挥平台</p>

    <a-form layout="vertical" :model="form" class="form">
      <a-form-item label="账号">
        <a-input v-model:value="form.username" placeholder="请输入账号" size="large">
          <template #prefix><UserOutlined /></template>
        </a-input>
      </a-form-item>

      <a-form-item label="密码">
        <a-input-password v-model:value="form.password" placeholder="请输入密码" size="large">
          <template #prefix><LockOutlined /></template>
        </a-input-password>
      </a-form-item>

      <a-form-item label="验证码">
        <div class="captcha-row">
          <a-input v-model:value="form.captcha" placeholder="请输入验证码" size="large" :maxlength="8">
            <template #prefix><SafetyOutlined /></template>
          </a-input>
          <CaptchaImage ref="captchaRef" @update:token="(t) => (form.captchaToken = t)" />
        </div>
      </a-form-item>

      <div class="row-between">
        <a-checkbox v-model:checked="form.remember">记住我</a-checkbox>
        <a class="link" @click="forgotOpen = true">忘记密码?</a>
      </div>

      <a-button
        type="primary"
        size="large"
        block
        :loading="submitting"
        :disabled="!canSubmit"
        @click="onSubmit"
        class="btn-primary"
      >登录系统</a-button>

      <a-button
        size="large"
        block
        :loading="demoLoading"
        @click="onDemo"
        class="btn-demo"
      >演示模式</a-button>

      <p class="version">Version 1.0</p>
    </a-form>

    <ForgotPasswordModal v-model:open="forgotOpen" />
  </div>
</template>

<script lang="ts" setup>
import { reactive, ref, computed, defineEmits, defineExpose } from 'vue'
import { UserOutlined, LockOutlined, SafetyOutlined } from '@ant-design/icons-vue'
import CaptchaImage from './CaptchaImage.vue'
import ForgotPasswordModal from './ForgotPasswordModal.vue'

interface FormState {
  username: string,
  password: string,
  captcha: string,
  captchaToken: string,
  remember: boolean,
}

const form = reactive<FormState>({
  username: '',
  password: '',
  captcha: '',
  captchaToken: '',
  remember: false,
})

const submitting = ref(false)
const demoLoading = ref(false)
const forgotOpen = ref(false)
const captchaRef = ref<{ refresh: () => void } | null>(null)

const canSubmit = computed(
  () => !!form.username && !!form.password && !!form.captcha && !!form.captchaToken
)

const emit = defineEmits<{
  (e: 'submit', payload: FormState): void,
  (e: 'demo'): void,
}>()

function onSubmit () {
  if (!canSubmit.value || submitting.value) return
  emit('submit', { ...form })
}

function onDemo () {
  emit('demo')
}

defineExpose({
  setSubmitting: (v: boolean) => (submitting.value = v),
  setDemoLoading: (v: boolean) => (demoLoading.value = v),
  refreshCaptcha: () => captchaRef.value?.refresh(),
  setUsername: (u: string) => { form.username = u; form.remember = true },
})
</script>

<style lang="scss" scoped>
.login-form {
  color: #cfd8e7;
}
.title-row {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  margin-bottom: 8px;
  .line { width: 32px; height: 1px; background: #1890ff; }
  .title { margin: 0; color: #fff; font-size: 28px; font-weight: 700; letter-spacing: 2px; }
}
.subtitle {
  text-align: center;
  color: rgba(255, 255, 255, 0.65);
  font-size: 13px;
  margin-bottom: 24px;
  padding-bottom: 12px;
  border-bottom: 1px solid rgba(64, 158, 255, 0.15);
}
.form { color: #cfd8e7; }
.form :deep(.ant-form-item-label > label) { color: #cfd8e7; }
.form :deep(.ant-input),
.form :deep(.ant-input-affix-wrapper),
.form :deep(.ant-input-password) {
  background: rgba(255, 255, 255, 0.04);
  border-color: rgba(64, 158, 255, 0.2);
  color: #fff;
}
.captcha-row {
  display: flex;
  gap: 8px;
  align-items: stretch;
}
.row-between {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: 8px 0 20px;
  .link { color: #40a9ff; cursor: pointer; font-size: 13px; }
}
.btn-primary {
  margin-bottom: 12px;
  transition: box-shadow 150ms ease;
  &:hover:not(:disabled) { box-shadow: 0 0 16px rgba(24, 144, 255, 0.4); }
}
.btn-demo {
  background: transparent;
  border: 1px solid #1890ff;
  color: #40a9ff;
  transition: box-shadow 150ms ease;
  &:hover { box-shadow: 0 0 16px rgba(24, 144, 255, 0.3); }
}
.version {
  text-align: center;
  color: rgba(255, 255, 255, 0.4);
  font-size: 12px;
  margin-top: 16px;
}
</style>
