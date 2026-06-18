const backendHost = (import.meta.env.VITE_APP_APIGATEWAY_BACKEND_HOST || 'http://127.0.0.1:6789').replace(/\/$/, '')
const websocketHost = import.meta.env.VITE_APP_APIGATEWAY_WEBSOCKET_HOST || backendHost.replace(/^http/, 'ws')
const rtmpUrl = import.meta.env.VITE_APP_LIVESTREAM_RTMP_URL || 'rtmp://localhost:1935/live/'

export const CURRENT_CONFIG = {

  // license
  appId: '180133',
  appKey: '908c461220f3ca74d2dd5d772af8856',
  appLicense: 'KzJb/GMPTJae/7X+hpcABGVKTEI/kuRinwWwYheugyN55kzFqf1IBHrrdXWKNWckTABX4Mr1zUrNkwGRHHELK9YciBFFz8W0S477TlF80/5RrrsTm8Qf3Xs0XYhPjijSA/bojPsA9UUeJM8hn4XcO3u9Umqo7vpAIFIF9O50hHI=',

  // http
  baseURL: `${backendHost}/`, // This url must end with "/". Example: 'http://192.168.1.1:6789/'
  websocketURL: `${websocketHost.replace(/\/$/, '')}/api/v1/ws`, // Example: 'ws://192.168.1.1:6789/api/v1/ws'

  // livestreaming
  // RTMP  Note: This IP is the address of the streaming server. If you want to see livestream on web page, you need to convert the RTMP stream to WebRTC stream.
  rtmpURL: rtmpUrl, // Example: 'rtmp://192.168.1.1/live/'
  // GB28181 Note:If you don't know what these parameters mean, you can go to Pilot2 and select the GB28181 page in the cloud platform. Where the parameters same as these parameters.
  gbServerIp: 'Please enter the server ip.',
  gbServerPort: 'Please enter the server port.',
  gbServerId: 'Please enter the server id.',
  gbAgentId: 'Please enter the agent id',
  gbPassword: 'Please enter the agent password',
  gbAgentPort: 'Please enter the local port.',
  gbAgentChannel: 'Please enter the channel.',
  // RTSP
  rtspUserName: 'Please enter the username.',
  rtspPassword: 'Please enter the password.',
  rtspPort: '8554',
  // map
  // You can apply on the AMap website.
  amapKey: '6c7bd344426ae654e5345c0e5473a441',
  // 该 key（Web端）配了安全密钥，AMap JS API 2.0 必须在加载前设置 window._AMapSecurityConfig，
  // 否则请求未签名会被限流/部分服务失败（表现为地图慢、注记加载不出）。
  amapSecurityCode: '261851d21eb26fef9ed36869d32bc4d1',
  // 天地图（MapLibre 底图）服务密钥，tianditu.gov.cn 免费申请。卫星 img+cia / 标准 vec+cva。
  tiandituKey: '9ebd7a776ff13137feec1d61cce79193',

}
