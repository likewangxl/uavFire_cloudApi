import { message } from 'ant-design-vue'
import { login, LoginBody } from '/@/api/manage'
import { demoLogin as demoLoginApi } from '/@/api/captcha'
import { getRoot } from '/@/root'
import { ELocalStorageKey, ERouterName, EUserType } from '/@/types'

interface FormSubmitPayload {
  username: string,
  password: string,
  captcha: string,
  captchaToken: string,
  remember: boolean,
}

interface FormHandle {
  setSubmitting: (v: boolean) => void,
  setDemoLoading: (v: boolean) => void,
  refreshCaptcha: () => void,
}

function persistLogin (data: any) {
  localStorage.setItem(ELocalStorageKey.Token, data.access_token)
  localStorage.setItem(ELocalStorageKey.WorkspaceId, data.workspace_id)
  localStorage.setItem(ELocalStorageKey.Username, data.username)
  localStorage.setItem(ELocalStorageKey.UserId, data.user_id)
  localStorage.setItem(ELocalStorageKey.Flag, EUserType.Web.toString())
}

export function readRememberedUsername (): string {
  return localStorage.getItem(ELocalStorageKey.RememberUsername) ?? ''
}

export function useLogin () {

  async function onLogin (payload: FormSubmitPayload, formHandle: FormHandle) {
    formHandle.setSubmitting(true)
    try {
      const body: LoginBody = {
        username: payload.username,
        password: payload.password,
        flag: EUserType.Web,
        captcha: payload.captcha,
        captcha_token: payload.captchaToken,
      }
      const result = await login(body)
      if (result.code === 0) {
        persistLogin(result.data)
        if (payload.remember) {
          localStorage.setItem(ELocalStorageKey.RememberUsername, payload.username)
        } else {
          localStorage.removeItem(ELocalStorageKey.RememberUsername)
        }
        getRoot().$router.push(ERouterName.LEADERSHIP_COCKPIT)
        return
      }
      message.error(result.message ?? '登录失败')
    } catch (e) {
      message.error('网络错误,请重试')
    } finally {
      formHandle.setSubmitting(false)
      formHandle.refreshCaptcha()
    }
  }

  async function onDemoLogin (formHandle: FormHandle) {
    formHandle.setDemoLoading(true)
    try {
      const result = await demoLoginApi()
      if (result.code === 0) {
        persistLogin(result.data)
        getRoot().$router.push(ERouterName.LEADERSHIP_COCKPIT)
        return
      }
      message.error(result.message ?? '演示模式不可用')
    } catch (e) {
      message.error('网络错误,请重试')
    } finally {
      formHandle.setDemoLoading(false)
    }
  }

  return { onLogin, onDemoLogin, readRememberedUsername }
}
