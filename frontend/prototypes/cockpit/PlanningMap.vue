<template>
  <div class="planning-map-shell">
    <div ref="canvas" class="planning-map-canvas" aria-label="航线规划地图"></div>
    <div class="planning-map-caption"><strong>{{ title }}</strong><span>{{ editable ? (drawing ? '点击地图添加航点，点击航点选中' : '浏览航线 · 点击航点查看参数') : '示例航线预览' }}</span></div>
    <button class="button secondary planning-fit" @click="fit"><AimOutlined />适配航线</button>
    <span class="planning-map-note">示例坐标 · 仅用于规划流程预览</span>
    <span v-if="mapError" class="planning-map-error">底图加载失败，仍可从右侧编辑航点</span>
  </div>
</template>
<script setup>
import { ref, watch, onMounted, onBeforeUnmount } from 'vue'
import maplibregl from 'maplibre-gl'
import { AimOutlined } from '@ant-design/icons-vue'
const props=defineProps({points:{type:Array,default:()=>[]},selected:{type:Number,default:0},editable:Boolean,drawing:Boolean,title:{type:String,default:'巡护航线'}})
const emit=defineEmits(['add','select'])
const canvas=ref(null),mapError=ref(false)
let map,observer,pins=[]
const valid=p=>Number.isFinite(p.lng)&&Number.isFinite(p.lat)&&Math.abs(p.lng)<=180&&Math.abs(p.lat)<=85
function fit(){if(!map)return;if(props.points.filter(valid).length){const b=new maplibregl.LngLatBounds();props.points.filter(valid).forEach(p=>b.extend([p.lng,p.lat]));map.fitBounds(b,{padding:80,maxZoom:13,duration:200})}else map.jumpTo({center:[108.85,34.085],zoom:11.5})}
function update(){if(!map)return;for(const p of pins)p.remove();pins=[];props.points.forEach((p,i)=>{if(!valid(p))return;const el=document.createElement('button');el.className='planning-waypoint'+(props.selected===i?' chosen':'');el.textContent=String(i+1);el.setAttribute('aria-label',`选择航点 ${i+1}`);el.onclick=e=>{e.stopPropagation();emit('select',i)};pins.push(new maplibregl.Marker({element:el}).setLngLat([p.lng,p.lat]).addTo(map))});map.getSource('route')?.setData({type:'Feature',properties:{},geometry:{type:'LineString',coordinates:props.points.filter(valid).map(p=>[p.lng,p.lat])}})}
watch(()=>[props.points,props.selected],update,{deep:true})
onMounted(()=>{map=new maplibregl.Map({container:canvas.value,center:[108.85,34.085],zoom:11.5,attributionControl:{compact:false},style:{version:8,sources:{base:{type:'raster',tiles:['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],tileSize:256,attribution:'© <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap contributors</a>'}},layers:[{id:'base',type:'raster',source:'base'}]}});map.addControl(new maplibregl.NavigationControl({showCompass:false}),'bottom-right');map.on('load',()=>{map.addSource('route',{type:'geojson',data:{type:'FeatureCollection',features:[]}});map.addLayer({id:'route-line',type:'line',source:'route',paint:{'line-color':'#357eed','line-width':4}});update();fit()});map.on('error',()=>{mapError.value=true});map.on('click',e=>{if(props.editable&&props.drawing)emit('add',{lng:+e.lngLat.lng.toFixed(6),lat:+e.lngLat.lat.toFixed(6)})});observer=new ResizeObserver(()=>map?.resize());observer.observe(canvas.value);update()})
onBeforeUnmount(()=>{observer?.disconnect();for(const pin of pins)pin.remove();map?.remove()})
</script>
<style>
.planning-map-shell{height:100%;min-height:340px;position:relative;overflow:hidden;background:#152334;border-radius:7px}.planning-map-canvas{position:absolute;inset:0}.planning-map-caption{position:absolute;top:16px;left:16px;max-width:calc(100% - 32px);padding:12px 15px;background:#122033eb;border:1px solid #425673;border-radius:6px;pointer-events:none}.planning-map-caption strong{font-size:13px;display:block}.planning-map-caption span{font-size:10px;color:#adbed4;display:block;margin-top:6px}.planning-fit{position:absolute;left:16px;bottom:40px;background:#14233beb!important}.planning-map-note{position:absolute;left:16px;bottom:13px;color:#d6e4f3;background:#112034d9;padding:4px 7px;font-size:10px;border-radius:3px}.planning-map-error{position:absolute;bottom:83px;left:16px;font-size:11px;background:#33291e;padding:8px;color:#e5c18c}.planning-waypoint{width:27px;height:27px;display:grid;place-items:center;border:2px solid white;background:#316bb0;color:white;border-radius:50%;font-size:11px;box-shadow:0 2px 6px #0007}.planning-waypoint.chosen{background:#e59a42;outline:4px solid #e8a94b50}.planning-map-shell .maplibregl-ctrl-attrib{font-size:9px}
</style>
