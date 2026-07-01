const SOURCE_ID = 'uom-airspace-reference-source'
const FILL_LAYER_ID = 'uom-airspace-reference-fill'
const OUTLINE_LAYER_ID = 'uom-airspace-reference-outline'
const DATA_URL = '/geojson/xian_flyable_airspace_cells_zoom18.geojson'

type GeoJsonFeatureCollection = {
  type: 'FeatureCollection'
  features: unknown[]
}

function emptyCollection (): GeoJsonFeatureCollection {
  return { type: 'FeatureCollection', features: [] }
}

async function fetchGeoJson (): Promise<GeoJsonFeatureCollection> {
  const response = await fetch(DATA_URL)
  if (!response.ok) {
    throw new Error(`Failed to load UOM airspace layer: ${response.status}`)
  }
  return await response.json()
}

export function useUomAirspaceReferenceLayer (getMap: () => any) {
  let loadedData: GeoJsonFeatureCollection | null = null
  let visible = true
  let loading: Promise<void> | null = null

  function mapReady (map: any) {
    return !!map && (typeof map.isStyleLoaded !== 'function' || map.isStyleLoaded())
  }

  function setLayerVisibility (map: any) {
    const visibility = visible ? 'visible' : 'none'
    if (map.getLayer?.(FILL_LAYER_ID)) {
      map.setLayoutProperty(FILL_LAYER_ID, 'visibility', visibility)
    }
    if (map.getLayer?.(OUTLINE_LAYER_ID)) {
      map.setLayoutProperty(OUTLINE_LAYER_ID, 'visibility', visibility)
    }
  }

  function ensureLayers () {
    const map = getMap()
    if (!mapReady(map)) return false

    if (!map.getSource?.(SOURCE_ID)) {
      map.addSource(SOURCE_ID, {
        type: 'geojson',
        data: loadedData || emptyCollection(),
      })
    }

    if (!map.getLayer?.(FILL_LAYER_ID)) {
      map.addLayer({
        id: FILL_LAYER_ID,
        type: 'fill',
        source: SOURCE_ID,
        paint: {
          'fill-color': '#00d5d5',
          'fill-opacity': 0.2,
        },
      })
    }

    if (!map.getLayer?.(OUTLINE_LAYER_ID)) {
      map.addLayer({
        id: OUTLINE_LAYER_ID,
        type: 'line',
        source: SOURCE_ID,
        paint: {
          'line-color': '#00f0f0',
          'line-width': ['interpolate', ['linear'], ['zoom'], 9, 0.3, 14, 0.7, 18, 1.1],
          'line-opacity': 0.72,
        },
      })
    }

    const source = map.getSource?.(SOURCE_ID)
    if (source?.setData && loadedData) {
      source.setData(loadedData)
    }
    setLayerVisibility(map)
    return true
  }

  function scheduleEnsure () {
    const map = getMap()
    if (!map) return
    if (ensureLayers()) return

    if (typeof map.once === 'function') {
      map.once('load', () => ensureLayers())
    }
    if (typeof map.on === 'function') {
      const onStyleData = () => {
        if (!ensureLayers()) return
        map.off?.('styledata', onStyleData)
      }
      map.on('styledata', onStyleData)
    }
  }

  async function load () {
    if (!loading) {
      loading = fetchGeoJson()
        .then(data => {
          loadedData = data
          scheduleEnsure()
        })
        .catch(error => {
          console.error(error)
        })
    }
    return loading
  }

  function setVisible (nextVisible: boolean) {
    visible = nextVisible
    if (!loadedData) {
      load()
      return
    }
    scheduleEnsure()
  }

  function dispose () {
    const map = getMap()
    if (!map) return
    if (map.getLayer?.(OUTLINE_LAYER_ID)) map.removeLayer(OUTLINE_LAYER_ID)
    if (map.getLayer?.(FILL_LAYER_ID)) map.removeLayer(FILL_LAYER_ID)
    if (map.getSource?.(SOURCE_ID)) map.removeSource(SOURCE_ID)
  }

  return {
    load,
    setVisible,
    dispose,
  }
}
