<template>
  <div
    class="login flex-column flex-justify-center flex-align-center m0 b0">
    <p class="fz35 pb50" style="color: #2d8cf0">云端管理平台</p>
    <a-form
      layout="inline"
      :model="formState"
      class="flex-row flex-justify-center flex-align-center"
    >
      <a-form-item>
        <a-input v-model:value="formState.username" placeholder="请输入账号">
          <template #prefix
            ><UserOutlined style="color: rgba(0, 0, 0, 0.25)"
          /></template>
        </a-input>
      </a-form-item>
      <a-form-item>
        <a-input
          v-model:value="formState.password"
          type="password"
          placeholder="请输入密码"
        >
          <template #prefix
            ><LockOutlined style="color: rgba(0, 0, 0, 0.25)"
          /></template>
        </a-input>
      </a-form-item>
      <a-form-item>
        <a-button
          class="m0"
          type="primary"
          html-type="submit"
          :disabled="loginBtnDisabled"
          @click="onSubmit"
        >
          登录
        </a-button>
      </a-form-item>
    </a-form>
  </div>

</template>

<script lang="ts" setup>
import { LockOutlined, UserOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { reactive, computed, UnwrapRef } from 'vue'
import { login, LoginBody } from '/@/api/manage'
import { getRoot } from '/@/root'
import { ELocalStorageKey, ERouterName, EUserType } from '/@/types'

const formState: UnwrapRef<LoginBody> = reactive({
  username: 'adminPC',
  password: 'adminPC',
  flag: EUserType.Web,
})

const loginBtnDisabled = computed(() => {
  return !formState.username || !formState.password
})

const onSubmit = async (e: any) => {
  const result = await login(formState)
  if (result.code === 0) {
    localStorage.setItem(ELocalStorageKey.Token, result.data.access_token)
    localStorage.setItem(ELocalStorageKey.WorkspaceId, result.data.workspace_id)
    localStorage.setItem(ELocalStorageKey.Username, result.data.username)
    localStorage.setItem(ELocalStorageKey.UserId, result.data.user_id)
    localStorage.setItem(ELocalStorageKey.Flag, EUserType.Web.toString())
    getRoot().$router.push(ERouterName.LEADERSHIP_COCKPIT)
  } else {
    message.error(result.message)
  }
}

</script>

<style lang="scss" scoped>
@use '/@/styles/index.scss';
.login {
  background-color: $dark-highlight;
  height: 100vh;
}
</style>
