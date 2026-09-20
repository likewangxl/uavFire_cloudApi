import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { deviceModels, deviceModelName, formatDeviceText } from '../frontend/src/utils/device-models.mjs'
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..')
const require=createRequire(path.join(root,'frontend/package.json')), ts=require('typescript')
const failures=[]
function check(text,file,line) { if(formatDeviceText(text)!==text) failures.push(`${file}:${line}: ${text.slice(0,140)}`) }
function visitFile(file) {
 const text=fs.readFileSync(path.join(root,file),'utf8')
 if(file.endsWith('.vue')||file.endsWith('.wxml')) {
  const template=file.endsWith('.vue')?(text.match(/<template[\s>][\s\S]*?<\/template>/)?.[0]||''):text
  for(const match of template.matchAll(/>([^<>{}\n]+)</g))check(match[1],file,text.slice(0,match.index).split('\n').length)
  for(const match of template.matchAll(/\b(?:title|placeholder|label|description)="([^"{}]+)"/g))check(match[1],file,text.slice(0,match.index).split('\n').length)
  for(const match of template.matchAll(/\b(?:class|d|id)="([^"]*DJI [^"]*)"/g)) failures.push(file+': official label used in a technical attribute: '+match[1])
 }
 if(file.endsWith('.wxml'))return
 const source=file.endsWith('.vue')?(text.match(/<script[^>]*>([\s\S]*?)<\/script>/)?.[1]||''):text
 const tree=ts.createSourceFile(file,source,ts.ScriptTarget.Latest,true)
 function walk(node) { if(ts.isStringLiteral(node)||ts.isNoSubstitutionTemplateLiteral(node)) {
  const p=node.parent, key=ts.isPropertyAssignment(p)?p.name.getText(tree):''
  if(/^(label|title|displayName|description|placeholder)$/.test(key)||(file==='frontend/src/types/device.ts'&&key.startsWith('[DEVICE_MODEL_KEY.')))check(node.text,file,tree.getLineAndCharacterOfPosition(node.pos).line+1)
 } ts.forEachChild(node,walk) }
 walk(tree)
}
function walk(dir){for(const e of fs.readdirSync(path.join(root,dir),{withFileTypes:true})){if(['node_modules','preview','tests'].includes(e.name))continue;const name=dir+'/'+e.name;if(e.isDirectory())walk(name);else if(/\.(vue|wxml|js|mjs|ts)$/.test(name)&&!name.endsWith('device-models.mjs')&&!name.endsWith('device-models.js'))visitFile(name)}}
walk('frontend/src');walk('miniprogram/pages');walk('miniprogram/templates')
for(const row of deviceModels)for(const alias of [row.code,row.name,...row.aliases])if(deviceModelName(alias)!==row.name)failures.push('Catalog roundtrip failed: '+alias)
if(failures.length){console.error(failures.join('\n'));process.exitCode=1}else console.log('Device names: UI labels, technical attributes, and catalog aliases passed')
