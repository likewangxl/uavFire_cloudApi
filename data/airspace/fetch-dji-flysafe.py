#!/usr/bin/env python3
"""拉取 DJI FlySafe 限飞区(非官方接口)→ 归一化 GeoJSON,供规划页离线导入。

范围:西安市中心(对齐前端地图默认中心)周边 100km。单次空间查询即可覆盖。
用法:python3 fetch-dji-flysafe.py   (覆盖同目录 dji_flysafe_xian.geojson / _raw.json)

注意:接口非官方、无 API 文档,数据结构可能变更;数据为"限飞区"非"适飞空域";ToS 灰色,商用前法务确认。
sub_areas 展开:DJI 一个区可能含多层 sub_areas(同心圈/多块),逐层展开成独立要素以与 DJI 官网画法一致;
无 sub_areas 的区(如部分"无人机管控区域")回退用区顶层几何。
"""
import json
import os
import urllib.parse
import urllib.request
from collections import Counter

BASE = "https://www-api.dji.com/api/geo/areas"
DRONE = "industry-260"          # DJI Mavic 3E/3T/3M(本系统机队)
LNG, LAT, RADIUS = 108.9234, 34.2292, 100000   # 西安中心 WGS84 + 100km
LEVELS = "0,1,2,3,4,5,6,7,8"
OUT_DIR = os.path.dirname(os.path.abspath(__file__))

# level → 渲染/判定类别(见 airspace-import.mjs)
LEVEL_CAT = {0: "warning", 1: "nfz", 2: "nfz", 3: "warning", 4: "nfz", 6: "warning", 7: "dfence", 8: "dfence"}
LEVEL_NAME = {0: "Warning", 1: "Authorization", 2: "Restricted", 3: "Enhanced Warning",
              4: "Regulatory Restricted", 6: "Altitude", 7: "Recommended", 8: "Approved-LightUAV"}


def fetch():
    p = urllib.parse.urlencode({"drone": DRONE, "zones_mode": "total", "country": "cn",
                                "level": LEVELS, "lng": LNG, "lat": LAT, "search_radius": RADIUS})
    req = urllib.request.Request(BASE + "?" + p, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=30) as f:
        return json.load(f).get("areas", [])


def geom_circle(lng, lat, radius):
    if lng is None or lat is None or not radius or radius <= 0:
        return None
    return {"type": "Point", "coordinates": [lng, lat], "_radius": radius}


def feature(geom, props):
    g = {"type": geom["type"], "coordinates": geom["coordinates"]}
    if geom["type"] == "Point":
        props = {**props, "radius_m": geom["_radius"]}
    return {"type": "Feature", "properties": props, "geometry": g}


def area_to_features(a):
    """一个区 → 若干要素:有 sub_areas 逐层展开,否则回退区顶层几何。"""
    aid, name = a.get("area_id"), a.get("name") or "未命名区域"
    subs = a.get("sub_areas") or []
    feats = []
    if subs:
        for i, s in enumerate(subs):
            lvl = s.get("level", a.get("level"))
            base = {"area_id": f"{aid}-{i}" if len(subs) > 1 else aid, "name": name,
                    "dji_type": a.get("type"), "level": lvl, "level_name": LEVEL_NAME.get(lvl, str(lvl)),
                    "category": LEVEL_CAT.get(lvl, "warning"), "color": s.get("color") or a.get("color")}
            if s.get("shape") == 1 and s.get("polygon_points"):
                feats.append(feature({"type": "Polygon", "coordinates": s["polygon_points"]}, base))
            else:
                g = geom_circle(s.get("lng", a.get("lng")), s.get("lat", a.get("lat")), s.get("radius", a.get("radius")))
                if g:
                    feats.append(feature(g, base))
    else:
        lvl = a.get("level")
        base = {"area_id": aid, "name": name, "dji_type": a.get("type"), "level": lvl,
                "level_name": LEVEL_NAME.get(lvl, str(lvl)), "category": LEVEL_CAT.get(lvl, "warning"), "color": a.get("color")}
        if a.get("polygon_points"):
            feats.append(feature({"type": "Polygon", "coordinates": a["polygon_points"]}, base))
        else:
            g = geom_circle(a.get("lng"), a.get("lat"), a.get("radius"))
            if g:
                feats.append(feature(g, base))
    return feats


def main():
    areas = [a for a in fetch() if (a.get("country") or "").upper() in ("CN", "CHINA")]
    feats = [f for a in areas for f in area_to_features(a)]
    meta = {"source": "DJI FlySafe (unofficial) " + BASE, "scope": "西安中心100km", "drone": DRONE,
            "center_wgs84": [LNG, LAT], "radius_m": RADIUS, "note": "限飞区 only(非适飞空域);sub_areas 已逐层展开"}

    raw = {**meta, "count": len(areas), "areas": areas}
    with open(os.path.join(OUT_DIR, "dji_flysafe_xian_raw.json"), "w", encoding="utf-8") as f:
        json.dump(raw, f, ensure_ascii=False)
    fc = {"type": "FeatureCollection", "name": "dji_flysafe_xian", "metadata": {**meta, "count": len(feats)}, "features": feats}
    with open(os.path.join(OUT_DIR, "dji_flysafe_xian.geojson"), "w", encoding="utf-8") as f:
        json.dump(fc, f, ensure_ascii=False)

    poly = sum(1 for f in feats if f["geometry"]["type"] == "Polygon")
    print(f"areas={len(areas)}  features={len(feats)} (poly {poly} / circle {len(feats) - poly})")
    print("by category:", dict(Counter(f["properties"]["category"] for f in feats)))
    print("by level:", {LEVEL_NAME.get(k, k): v for k, v in sorted(Counter(f["properties"]["level"] for f in feats).items())})


if __name__ == "__main__":
    main()
