import fs from 'node:fs'
import path from 'node:path'
import http from 'node:http'
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const root = path.dirname(here)
const cache = path.resolve(root, '../outputs/miniapp-browser-check')
fs.mkdirSync(cache, { recursive: true })
const app = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'))
const compiler = process.env.WECHAT_WCC || 'D:/微信web开发者工具/resources/app.asar.unpacked/node_modules/wcc-exec/wcc.exe'
const compiled = path.join(cache, 'compiled-wxml.js')
execFileSync(compiler, ['-o', compiled, ...app.pages.map(p => p + '.wxml'), 'templates/list.wxml'], { cwd: root, stdio: 'pipe' })
const sources = () => {
  const names = ['app.js', 'app.wxss', 'config/runtime.js', 'services/api.js', 'services/http.js', 'services/session.js', 'services/presentation.js', 'services/device-models.js', 'services/flight-report.js', ...app.pages.flatMap(p => [p + '.js', p + '.wxss', p + '.json'])]
  const read = name => fs.readFileSync(path.join(root,name),'utf8').replace(/@import\s+["']([^"']+)["'];/g,(_,relative)=>read(path.join(path.dirname(name),relative)))
  return Object.fromEntries(names.map(name => [name, name.endsWith('.wxss') ? read(name) : fs.readFileSync(path.join(root, name), 'utf8')]))
}
const server = http.createServer((request, response) => {
  const url = new URL(request.url, 'http://localhost')
  response.setHeader('Cache-Control', 'no-store')
  if (url.pathname === '/sources') { response.setHeader('Content-Type', 'application/json; charset=utf-8'); response.end(JSON.stringify({ app, files: sources() })); return }
  const files = { '/': ['index.html', 'text/html'], '/device': ['device.html', 'text/html'], '/renderer.js': ['renderer.js', 'text/javascript'], '/fixtures.js': ['fixtures.js', 'text/javascript'] }
  if (url.pathname === '/compiled.js') { response.setHeader('Content-Type', 'text/javascript'); response.end(fs.readFileSync(compiled)); return }
  if (files[url.pathname]) { const [name, type] = files[url.pathname]; response.setHeader('Content-Type', type + '; charset=utf-8'); response.end(fs.readFileSync(path.join(here, name))); return }
  response.writeHead(404); response.end('Not found')
})
server.on('error', error => { if (error.code === 'EADDRINUSE') server.listen(0, '127.0.0.1'); else throw error })
server.on('listening', () => console.log('Browser layout check: http://127.0.0.1:' + server.address().port))
server.listen(Number(process.env.MINIAPP_PREVIEW_PORT || 8092), '127.0.0.1')
