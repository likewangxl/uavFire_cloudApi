// 天地图（Tianditu）MapLibre 底图样式构建。
// 四档栅格图层：卫星档 = img(影像)+cia(影像中文注记)；标准档 = vec(矢量)+cva(矢量中文注记)。
// 坐标 CGCS2000 ≈ WGS84，Web 墨卡托（_w）。瓦片服务密钥在 config.tiandituKey。
import type { StyleSpecification } from 'maplibre-gl'
import { CURRENT_CONFIG } from '/@/api/http/config'

const TK = (CURRENT_CONFIG as any).tiandituKey as string
const SUBDOMAINS = ['0', '1', '2', '3', '4', '5', '6', '7']

export type TiandituLayer = 'img' | 'cia' | 'vec' | 'cva'

function tileUrls (layer: TiandituLayer): string[] {
  return SUBDOMAINS.map(s =>
    `https://t${s}.tianditu.gov.cn/${layer}_w/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0` +
    `&LAYER=${layer}&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles` +
    `&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&tk=${TK}`)
}

function rasterSource (layer: TiandituLayer) {
  // 天地图瓦片本身是 256px。
  // 影像/矢量底图(img/vec)：用 tileSize:128 → MapLibre 请求深一级更精细瓦片，高分屏(2x)上更清晰(高清模型)。
  // 注记层(cia/cva)：保持 256 → 文字按当前缩放正常字号与密度渲染(对标司空2「高清路网和标注」)；
  //   若也设 128，注记会被请求成深一级、渲染成半尺寸 → 字小且稀，反而看不清(CDP 实测)。
  const tileSize = (layer === 'img' || layer === 'vec') ? 128 : 256
  return { type: 'raster' as const, tiles: tileUrls(layer), tileSize, minzoom: 1, maxzoom: 18 }
}

// 三档底图：standard=矢量路网图；satellite=纯高清影像+注记；hybrid=影像+矢量路网叠加+注记（司空2「混合」式）。
export type WaylineMapLayer = 'standard' | 'satellite' | 'hybrid'

// 混合档：天地图影像注记(cia)只画注记和主干道，不画次干路/支路路网。
// 故在影像上叠加矢量底图(vec)透出完整路网。vec 的浅色地表填充不透明，叠加必然轻雾化影像，
// 取 0.45 为「路网可辨 ↔ 影像仍清晰」折中（CDP 实测：0.6 偏雾、0.3 路网偏淡）。
const HYBRID_ROAD_OPACITY = 0.45

// 渲染顺序(下→上)：img 影像底 → vec 路网(混合档半透) → cia 影像注记 → cva 矢量注记。
// 这样：标准档 vec 在 cva 之下(注记压路网)；卫星/混合档 cia 注记压在影像/路网之上，三档共用一套顺序。
function layerVisibility (layer: WaylineMapLayer) {
  const onImg = layer === 'satellite' || layer === 'hybrid'
  return {
    img: onImg ? 'visible' : 'none',
    cia: onImg ? 'visible' : 'none',
    vec: (layer === 'standard' || layer === 'hybrid') ? 'visible' : 'none',
    cva: layer === 'standard' ? 'visible' : 'none',
    vecOpacity: layer === 'hybrid' ? HYBRID_ROAD_OPACITY : 1,
  }
}

/** 初始底图样式：按档位设四层可见性与 vec 叠加透明度。 */
export function buildTiandituStyle (layer: WaylineMapLayer = 'satellite'): StyleSpecification {
  const v = layerVisibility(layer)
  return {
    version: 8,
    // 仅自有 HTML/GeoJSON 覆盖物，无需 glyphs/sprite（注记来自天地图栅格瓦片）
    sources: {
      'td-img': rasterSource('img'),
      'td-cia': rasterSource('cia'),
      'td-vec': rasterSource('vec'),
      'td-cva': rasterSource('cva'),
    },
    layers: [
      { id: 'td-img', type: 'raster', source: 'td-img', layout: { visibility: v.img as any } },
      { id: 'td-vec', type: 'raster', source: 'td-vec', layout: { visibility: v.vec as any }, paint: { 'raster-opacity': v.vecOpacity } },
      { id: 'td-cia', type: 'raster', source: 'td-cia', layout: { visibility: v.cia as any } },
      { id: 'td-cva', type: 'raster', source: 'td-cva', layout: { visibility: v.cva as any } },
    ],
  }
}

/** 切换底图档位：只改栅格层可见性与 vec 叠加透明度，不重建 style。 */
export function applyTiandituLayer (map: any, layer: WaylineMapLayer) {
  if (!map || typeof map.setLayoutProperty !== 'function') return
  const v = layerVisibility(layer)
  const setVis = (id: string, vis: string) => {
    if (map.getLayer && map.getLayer(id)) map.setLayoutProperty(id, 'visibility', vis)
  }
  setVis('td-img', v.img)
  setVis('td-cia', v.cia)
  setVis('td-vec', v.vec)
  setVis('td-cva', v.cva)
  if (map.getLayer && map.getLayer('td-vec')) map.setPaintProperty('td-vec', 'raster-opacity', v.vecOpacity)
}
