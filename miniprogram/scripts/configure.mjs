import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const args = process.argv.slice(2)
const value = name => args[args.indexOf(name) + 1]
if (!args.includes('--app-id') || !args.includes('--api-url')) throw new Error('需要 --app-id wx... --api-url https://域名/miniapp/api/v1')
const appId = value('--app-id'), url = value('--api-url').replace(/\/+$/, '')
if (!/^wx[0-9a-f]{16}$/i.test(appId)) throw new Error('AppID 格式不正确；此工具不接受 AppSecret')
if (!/^https:\/\/[^/?#@]+\/miniapp\/api\/v1$/.test(url)) throw new Error('接口地址必须是 HTTPS 且以 /miniapp/api/v1 结尾')
const project = JSON.parse(fs.readFileSync(path.join(root, 'project.config.json'), 'utf8'))
project.appid = appId
fs.writeFileSync(path.join(root, 'project.config.json'), JSON.stringify(project, null, 2) + '\n')
fs.writeFileSync(path.join(root, 'config/runtime.js'), `const runtimeConfig = Object.freeze(${JSON.stringify({ apiBaseUrl: url, clientVersion: '0.2.0', flightControlEnabled: false }, null, 2)})\nmodule.exports = { getRuntimeConfig: () => runtimeConfig }\n`)
console.log('已配置 AppID 和 HTTPS 地址；请检查这两个配置文件的差异。AppSecret 仍只在服务端配置。')
