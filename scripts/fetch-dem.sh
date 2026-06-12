#!/usr/bin/env bash
# 下载 Copernicus GLO-30 DEM 并转为 SRTM .hgt（供 HgtTerrainElevationService 使用）。
# 用法: scripts/fetch-dem.sh <west> <south> <east> <north> [outdir]
# 例:   scripts/fetch-dem.sh 115 39 117 41 backend/data/dem
# 注意: GLO-30 是 DSM（含树冠）。瓦片为 3600x3600 pixel-is-area，必须 gdalwarp
#       重采样到 3601x3601 pixel-is-point，gdal_translate 直转会被 SRTMHGT 驱动拒绝。
set -euo pipefail

if [ $# -lt 4 ]; then
  echo "用法: $0 <west> <south> <east> <north> [outdir]" >&2
  exit 1
fi
WEST=$1; SOUTH=$2; EAST=$3; NORTH=$4
OUTDIR=${5:-backend/data/dem}

command -v gdalwarp >/dev/null 2>&1 || { echo "需要 gdal: brew install gdal" >&2; exit 1; }
mkdir -p "$OUTDIR"

for ((lat=SOUTH; lat<NORTH; lat++)); do
  for ((lon=WEST; lon<EAST; lon++)); do
    if [ "$lat" -ge 0 ]; then ns=N; alat=$lat; else ns=S; alat=$((-lat)); fi
    if [ "$lon" -ge 0 ]; then we=E; alon=$lon; else we=W; alon=$((-lon)); fi
    tile=$(printf 'Copernicus_DSM_COG_10_%s%02d_00_%s%03d_00_DEM' "$ns" "$alat" "$we" "$alon")
    hgt=$(printf '%s%02d%s%03d.hgt' "$ns" "$alat" "$we" "$alon")
    if [ -f "$OUTDIR/$hgt" ]; then echo "跳过(已存在) $hgt"; continue; fi
    tmp=$(mktemp -d)
    url="https://copernicus-dem-30m.s3.amazonaws.com/${tile}/${tile}.tif"
    if ! curl -fSs -o "$tmp/in.tif" "$url"; then
      echo "瓦片不存在(海洋?) $tile" >&2; rm -rf "$tmp"; continue
    fi
    gdalwarp -q -overwrite -ts 3601 3601 -te "$lon" "$lat" $((lon+1)) $((lat+1)) \
      -r bilinear "$tmp/in.tif" "$OUTDIR/$hgt"
    rm -rf "$tmp"
    echo "完成 $hgt"
  done
done
echo "输出目录: $OUTDIR （后端配置 uavfire.terrain.dem-dir 或环境变量 UAVFIRE_DEM_DIR 指向它）"
