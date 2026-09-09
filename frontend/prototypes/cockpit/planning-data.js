import { reactive } from 'vue'
const coords = [[108.82,34.07],[108.84,34.06],[108.86,34.073],[108.875,34.095],[108.86,34.113],[108.838,34.104]]
export const initialRoutes = [
  { id:'R-001', name:'东区林缘日常巡护', area:'东区 · 林缘防火带', model:'M300 RTK', height:100, speed:8, finish:'返航', image:'visible', updated:'今日 13:50', uses:12, points:coords.map(([lng,lat])=>({lng,lat,height:100,action:'拍照'})) },
  { id:'R-002', name:'中区重点网格复查', area:'中区 · 农林交界', model:'M350 RTK', height:80, speed:6, finish:'返航', image:'thermal', updated:'今日 11:20', uses:7, points:coords.slice(0,5).map(([lng,lat])=>({lng:lng+.035,lat:lat+.02,height:80,action:'悬停观察'})) },
  { id:'R-003', name:'西区热源复查', area:'西区 · 山脚林地', model:'Matrice 4T', height:90, speed:7, finish:'返航', image:'field', updated:'昨日 10:45', uses:4, points:coords.slice(0,4).map(([lng,lat])=>({lng:lng-.035,lat:lat+.015,height:90,action:'拍照'})) }
]
export const planning = reactive({ routes: structuredClone(initialRoutes), selectedRouteId:'R-001', draft:null, executions:[] })
export function resetPlanning () { planning.routes=structuredClone(initialRoutes); planning.selectedRouteId='R-001'; planning.draft=null; planning.executions=[] }
export function routeDistance (points) {
  return points.slice(1).reduce((sum,p,i)=>{const a=points[i];return sum+Math.hypot((p.lng-a.lng)*92.2,(p.lat-a.lat)*111.2)},0)
}
