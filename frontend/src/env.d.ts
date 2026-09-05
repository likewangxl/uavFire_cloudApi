// Environment variable definition
// https://cn.vitejs.dev/guide/env-and-mode.html#env-files

interface ImportMetaEnv {
  VITE_APP_ENVIRONMENT: 'DEV' | 'STAG' | 'UAT' | 'PROD',
  // api gateway
  VITE_APP_APIGATEWAY_BACKEND_HOST: string
  VITE_APP_APIGATEWAY_WEBSOCKET_HOST: string
  VITE_APP_LIVESTREAM_RTMP_URL: string
  VITE_OPERATION_MOCK?: string
  // More environment variables...
}

interface Window {
  __UAVFIRE_SITE_LOCATION__?: {
    longitude: number
    latitude: number
    coordinateSystem: 'WGS84' | 'GCJ02'
    zoom?: number
  } | null
  __UAVFIRE_RUNTIME_CONFIG__?: {
    backendHost?: string
    websocketHost?: string
    rtmpUrl?: string
  }
}
