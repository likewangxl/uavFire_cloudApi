const runtimeConfig = Object.freeze({
  // Must be an HTTPS origin registered in the WeChat Mini Program console for real devices.
  apiBaseUrl: '',
  clientVersion: '0.1.0',
  flightControlEnabled: false,
})

function getRuntimeConfig() {
  return runtimeConfig
}

module.exports = {
  getRuntimeConfig,
}
