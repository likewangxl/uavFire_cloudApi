import request, { IWorkspaceResponse } from '/@/api/http/request'

const HTTP_PREFIX = '/manage/api/v1'

export interface CaptchaResponse {
  token: string,
  image_base64: string,
}

export const getCaptcha = async function (): Promise<IWorkspaceResponse<CaptchaResponse>> {
  const url = `${HTTP_PREFIX}/captcha`
  const result = await request.get(url)
  return result.data
}

export const demoLogin = async function (): Promise<IWorkspaceResponse<any>> {
  const url = `${HTTP_PREFIX}/demo-login`
  const result = await request.post(url, {})
  return result.data
}
