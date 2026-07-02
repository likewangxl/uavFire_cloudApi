import request from '/@/api/http/request'
import type { AxiosRequestConfig } from 'axios'

const camelToSnake = (s: string): string =>
  s.replace(/[A-Z]/g, (m) => '_' + m.toLowerCase())

const snakeToCamel = (s: string): string =>
  s.replace(/_([a-z])/g, (_, c: string) => c.toUpperCase())

function deepConvert (value: any, transform: (k: string) => string): any {
  if (Array.isArray(value)) return value.map((v) => deepConvert(v, transform))
  if (value !== null && typeof value === 'object' && value.constructor === Object) {
    const out: Record<string, any> = {}
    for (const k of Object.keys(value)) {
      out[transform(k)] = deepConvert(value[k], transform)
    }
    return out
  }
  return value
}

function idempotencyKey (): string {
  const c: any = typeof crypto !== 'undefined' ? crypto : undefined
  if (c && typeof c.randomUUID === 'function') return c.randomUUID()
  return Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10)
}

const client = {
  get <T = any> (url: string, config?: AxiosRequestConfig) {
    const cfg: AxiosRequestConfig = { ...(config || {}) }
    if (cfg.params) cfg.params = deepConvert(cfg.params, camelToSnake)
    return request.get<T>(url, cfg).then((r) => {
      r.data = deepConvert(r.data, snakeToCamel)
      return r
    })
  },
  post <T = any> (url: string, body?: any, config?: AxiosRequestConfig) {
    const cfg: AxiosRequestConfig = { ...(config || {}) }
    cfg.headers = { ...(cfg.headers || {}), 'X-Idempotency-Key': idempotencyKey() }
    const payload = body !== undefined ? deepConvert(body, camelToSnake) : body
    return request.post<T>(url, payload, cfg).then((r) => {
      r.data = deepConvert(r.data, snakeToCamel)
      return r
    })
  },
}

export default client
