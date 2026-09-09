// Isolated, loopback-only UI fixture server. Never imported by the production application.
import http from 'node:http'
const aircraft=Array.from({length:4},(_,i)=>({aircraft_sn:`QA-VIDEO-${i+1}`,device_name:`测试飞机 ${i+1}（隔离验证）`,online:true,connection_state:'CONNECTED',model:'M300 RTK',battery_percent:85-i*9}))
const leases=new Map(); let failure=false
http.createServer(async(req,res)=>{
 res.setHeader('Access-Control-Allow-Origin','http://127.0.0.1:8081');res.setHeader('Access-Control-Allow-Headers','Content-Type,x-auth-token,X-Idempotency-Key');res.setHeader('Access-Control-Allow-Methods','GET,POST,OPTIONS')
 if(req.method==='OPTIONS'){res.writeHead(204);res.end();return}
 const url=new URL(req.url,'http://127.0.0.1:6790');let raw='';for await(const chunk of req)raw+=chunk
 const body=raw?JSON.parse(raw):{}; let data=[]
 if(url.pathname==='/__qa__/failure'){failure=body.enabled;data={failure}}
 else if(failure){res.writeHead(503,{'Content-Type':'application/json'});res.end(JSON.stringify({code:503,message:'隔离测试：接口不可用'}));return}
 else if(url.pathname.endsWith('/demo-login'))data={access_token:'ISOLATED-UI-TEST-ONLY',workspace_id:'QA-ISOLATED',user_id:'QA',username:'隔离测试 · 无真实设备',user_type:1}
 else if(url.pathname.endsWith('/captcha'))data={token:'QA',image_base64:''}
 else if(url.pathname.endsWith('/fire-detection/status'))data={running:false}
 else if(url.pathname.endsWith('/msdk/devices'))data=aircraft
 else if(url.pathname.includes('/dual-stream/groups/'))data={drone_sn:url.pathname.split('/').pop(),status_message:'隔离测试无媒体源'}
 else if(url.pathname.endsWith('/video-bandwidth/status'))data={aircraft:aircraft.map(a=>({drone_sn:a.aircraft_sn,online:true,agent:{applied_profile:[...leases.values()].includes(a.aircraft_sn)?'HIGH':'LOW'}}))}
 else if(url.pathname.includes('/video-bandwidth/viewers/')){leases.set(url.pathname.split('/').pop(),body.drone_sn);data={}}
 else if(url.pathname==='/__qa__/leases')data={active:[...leases.values()].filter(Boolean).length}
 else if(url.pathname.includes('/jobs'))data={list:[],pagination:{total:0,page:1,page_size:100}}
 res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify({code:0,data}))
}).listen(6790,'127.0.0.1',()=>console.log('Isolated UI fixtures http://127.0.0.1:6790; no real services or devices'))
