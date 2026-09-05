const session = require('./services/session')

App({
  onLaunch() {
    session.ensureDeviceId()
  },
  globalData: {
    productName: '无人机火情智巡',
  },
})
