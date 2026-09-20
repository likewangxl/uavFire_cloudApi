import { createApi, tasks, events } from './fixtures.js'
const { app, files } = await (await fetch('/sources')).json()
const params=new URLSearchParams(location.search),scenario=params.get('scenario')||'normal'
const respond=createApi(scenario),storage=new Map(),modules=new Map(),instances=new Map(),stack=[]
const surface=document.querySelector('#surface'),tabs=document.querySelector('#native-tabs'),nav=document.querySelector('#nav-title')
let current,renderQueued=false,currentPath='',toastTimer
const clone = value => JSON.parse(JSON.stringify(value))
function normalize(path) {const parts=[];for(const p of path.split('/')){if(p==='..')parts.pop();else if(p&&p!=='.')parts.push(p)}return parts.join('/')}
function load(name) {
  name=normalize(name.endsWith('.js')?name:name+'.js')
  if(modules.has(name))return modules.get(name).exports
  if(!files[name])throw new Error('Missing source: '+name)
  const module={exports:{}};modules.set(name,module)
  new Function('require','module','exports','wx','Page',files[name])(relative=>load(normalize(name.split('/').slice(0,-1).join('/')+'/'+relative)),module,module.exports,wx,definition=>{module.exports=definition})
  return module.exports
}
function toast(title){const node=document.querySelector('#toast');node.textContent=title;node.style.display='block';clearTimeout(toastTimer);toastTimer=setTimeout(()=>node.style.display='none',1600)}
const wx={
  getStorageSync:key=>storage.get(key),setStorageSync:(key,value)=>storage.set(key,value),removeStorageSync:key=>storage.delete(key),
  getDeviceInfo:()=>({platform:'devtools'}),getSystemInfoSync:()=>({platform:'devtools'}),stopPullDownRefresh(){},
  login:({success})=>setTimeout(()=>success({code:'preview-code'}),80),
  request:options=>setTimeout(()=>{if(scenario==='error')return options.fail({errMsg:'request:fail network'});try{options.success({statusCode:200,data:{code:'OK',data:respond(new URL(options.url),options.method,options.data)}})}catch(error){options.success({statusCode:404,data:{code:'NOT_FOUND',message:error.message}})}},80),
  showToast:({title})=>toast(title),setClipboardData:({data})=>{document.body.dataset.copied=data;toast('编号已复制（检查环境）')},
  pageScrollTo:({scrollTop})=>{surface.scrollTop=scrollTop},
  setNavigationBarTitle:({title})=>{nav.textContent=title},
  switchTab:({url})=>navigate(url,false),navigateTo:({url})=>navigate(url,true),
}
const session=load('services/session.js')
if(!['logged-out','binding','pending'].includes(scenario))session.setSession({sessionId:'preview-session',accessToken:'preview-access',refreshToken:'preview-refresh'})
function queueRender(){if(renderQueued)return;renderQueued=true;queueMicrotask(()=>{renderQueued=false;render()})}
function page(path) {
  const definition=load(path+'.js'),instance={...definition,data:clone(definition.data||{})}
  instance.setData=values=>{for(const[key,value]of Object.entries(values)){const parts=key.split('.');let target=instance.data;for(const p of parts.slice(0,-1))target=target[p];target[parts.at(-1)]=value}if(instance===current)queueRender()}
  return instance
}
async function navigate(url,detail=false){
  if(current){current._previewScroll=surface.scrollTop;current.onHide?.();if(detail)stack.push({page:current,path:currentPath})}
  const parsed=new URL(url,'http://preview'),path=parsed.pathname.replace(/^\//,'')
  if(!detail)stack.length=0
  currentPath=path
  if(detail||!instances.has(path)){current=page(path);if(!detail)instances.set(path,current);current.onLoad?.(Object.fromEntries(parsed.searchParams))}else current=instances.get(path)
  const metadata=JSON.parse(files[path+'.json']||'{}');nav.textContent=path.includes('/detail/')?(parsed.searchParams.get('kind')==='events'?'火情详情':parsed.searchParams.get('kind')==='reports'?'执行报告':'任务详情'):metadata.navigationBarTitleText
  current.onShow?.();render();surface.scrollTop=current._previewScroll||0
}
function invoke(handler,node,event,value){if(!handler||typeof current[handler]!=='function')return;const e={currentTarget:{dataset:{...node.dataset}},target:{dataset:{...node.dataset}},detail:{value:value??event?.target?.value}};Promise.resolve(current[handler](e)).catch(error=>toast(error.message||String(error)))}
function dom(vnode,key='0') {
  if(vnode==null)return document.createTextNode('')
  if(typeof vnode!=='object')return document.createTextNode(String(vnode))
  const attrs=vnode.attr||{},tag=vnode.tag||'virtual'
  const native=tag.replace(/^wx-/,'')
  const mapped={view:'div',page:'div',text:'span',block:'div',virtual:'div',button:'button',input:'input',label:'label',picker:'div','scroll-view':'div',switch:'input'}[native]||'div'
  const node=document.createElement(mapped);node.dataset.renderKey=key
  node.className=(attrs.class||'')+(native==='text'?' wx-text':['virtual','block'].includes(native)?' wx-block':'')
  for(const[name,value]of Object.entries(attrs)){
    if(name.startsWith('data-'))node.setAttribute(name,String(value))
    if(['id','placeholder','maxlength','aria-label','for'].includes(name))node.setAttribute(name,String(value))
    if(name==='aria-role')node.setAttribute('role',String(value))
    if(name==='ariaRole')node.setAttribute('role',String(value))
    if(name==='ariaLabel')node.setAttribute('aria-label',String(value))
    if(name==='maxLength')node.setAttribute('maxlength',String(value))
  }
  for(let i=0;i<(vnode.children||[]).length;i++)node.append(dom(vnode.children[i],key+'-'+i))
  if(native==='button'){node.disabled=Boolean(attrs.disabled);node.type='button'}
  if(native==='input'){node.value=attrs.value||'';node.addEventListener('input',event=>invoke(attrs.bindinput,node,event));node.addEventListener('keydown',event=>{if(event.key==='Enter')invoke(attrs.bindconfirm,node,event)})}
  if(native==='switch'){node.type='checkbox';node.checked=Boolean(attrs.checked);node.disabled=Boolean(attrs.disabled);node.className+=' wx-switch-preview';node.setAttribute('aria-label',attrs.ariaLabel||attrs['aria-label']||attrs['data-key']||'偏好');node.addEventListener('change',event=>invoke(attrs.bindchange,node,event,node.checked))}
  if(native==='picker'){node.className+=' picker-preview';const select=document.createElement('select');select.setAttribute('aria-label',attrs.ariaLabel||attrs['aria-label']||'筛选');(attrs.range||[]).forEach((entry,index)=>{const option=document.createElement('option');option.value=index;option.textContent=entry[attrs.rangeKey||attrs['range-key']]||entry;select.append(option)});select.value=attrs.value||0;select.onchange=event=>invoke(attrs.bindchange,node,event,select.value);node.append(select)}
  if(attrs.bindtap)node.addEventListener('click',event=>{event.stopPropagation();invoke(attrs.bindtap,node,event)})
  return node
}
function render(){
  if(!current)return
  const active=document.activeElement,activeKey=active?.dataset?.renderKey,selection=active?.selectionStart
  document.querySelector('#page-style').textContent=(files['app.wxss']+'\n'+files[currentPath+'.wxss']).replace(/(-?[\d.]+)rpx/g,(_,value)=>'calc('+value+' * 100vw / 750)').replace(/\bpage\s*\{/g,'#surface {').replace(/\bview\b(?=[, {])/g,'div')
  const compiled=window.$gwx(currentPath+'.wxml');surface.replaceChildren(dom(compiled(current.data,{},{})))
  document.body.dataset.page=currentPath;surface.dataset.ready=String(!current.data.loading && !current.data.profileLoading)
  if(activeKey){const next=surface.querySelector('[data-render-key="'+activeKey+'"]');if(next&&next.tagName==='INPUT'){next.focus({preventScroll:true});try{next.setSelectionRange(selection,selection)}catch{}}}
  const detail=currentPath.includes('/detail/')
  tabs.hidden=detail;tabs.style.display=detail?'none':'flex';document.querySelector('#back').hidden=!detail
  tabs.replaceChildren(...app.tabBar.list.map(entry=>{const button=document.createElement('button');button.textContent=entry.text;button.className=currentPath===entry.pagePath?'active':'';button.onclick=()=>navigate('/'+entry.pagePath);return button}))
}
document.querySelector('#back').onclick=()=>{current?.onUnload?.();const previous=stack.pop();if(previous){current=previous.page;currentPath=previous.path;current.onShow?.();nav.textContent=JSON.parse(files[currentPath+'.json']).navigationBarTitleText;render();surface.scrollTop=current._previewScroll||0}else navigate('/pages/home/index')}
surface.addEventListener('scroll',()=>{if(surface.scrollTop+surface.clientHeight>=surface.scrollHeight-20)current?.onReachBottom?.()})
const initial=params.get('screen')||'home'
if(initial.endsWith('-detail')){const kind=initial.split('-')[0];await navigate('/pages/detail/index?kind='+({task:'tasks',event:'events',report:'reports'}[kind])+'&id='+encodeURIComponent(kind==='event'?events[0].eventId:tasks[0].taskId),true)}else await navigate('/pages/'+initial+'/index')
if(['binding','pending'].includes(scenario)&&initial==='mine'){current.setData({status:scenario==='binding'?'待绑定':'待审核',bindingTicket:scenario==='binding'?'preview-ticket':''});current._bindingExpiresAt=Date.now()+600000}
