import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..')
const source=fs.readFileSync(path.join(root,'shared/device-models.json'),'utf8')
const catalog=JSON.parse(source)
const body=`// Generated from shared/device-models.json. Run node scripts/sync-device-models.mjs after changes.
const deviceModels = ${JSON.stringify(catalog.models,null,2)}
const normalize = value => String(value || '').trim().toUpperCase().replace(/[\\s_-]+/g, '')
const lookup = new Map()
for (const row of deviceModels) for (const alias of [row.code, row.name, ...row.aliases]) lookup.set(normalize(alias), row)
function deviceModelCode(value) { return lookup.get(normalize(value))?.code || String(value || '').trim() }
function deviceModelName(value) { if (value == null || !String(value).trim()) return '未记录'; return lookup.get(normalize(value))?.name || formatDeviceText(value) }
function formatDeviceText(value) {
  let text = String(value == null ? '' : value)
  const protectedNames = []
  for (const row of deviceModels) text = text.replace(new RegExp(row.name, 'gi'), () => '\\u0001' + (protectedNames.push(row.name) - 1) + '\\u0002')
  const escaped = value => value.replace(/[.*+?^$\{\}()|[\\]\\\\]/g, '\\\\$&')
  const aliases = [...new Set(deviceModels.flatMap(row => [row.code, ...row.aliases]).filter(value => !/^\\d+-/.test(value) && !['L1','L2','P1','DOCK'].includes(value.toUpperCase())))].sort((a,b)=>b.length-a.length)
  const pattern = new RegExp('(^|[^A-Za-z0-9_])(' + aliases.map(escaped).join('|') + ')(?=$|[^A-Za-z0-9_])', 'gi')
  text = text.replace(pattern, (_, prefix, alias) => prefix + lookup.get(normalize(alias)).name)
  return text.replace(/\\u0001(\\d+)\\u0002/g, (_, index) => protectedNames[Number(index)])
}
`
const outputs={
  'miniprogram/services/device-models.js':body+'module.exports = { deviceModels, deviceModelCode, deviceModelName, formatDeviceText }\n',
  'frontend/src/utils/device-models.mjs':body+'export { deviceModels, deviceModelCode, deviceModelName, formatDeviceText }\n',
  'backend/uavfire/src/main/resources/device-models.json':source,
}
let bad=false
for(const [name,content]of Object.entries(outputs)){
 const file=path.join(root,name)
 if(process.argv.includes('--check')){if(!fs.existsSync(file)||fs.readFileSync(file,'utf8')!==content){console.error('Stale catalog output: '+name);bad=true}}
 else{fs.mkdirSync(path.dirname(file),{recursive:true});fs.writeFileSync(file,content)}
}
if(bad)process.exitCode=1
