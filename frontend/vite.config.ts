import vue from '@vitejs/plugin-vue'
// config alias
import path from 'path'
import { ConfigEnv, defineConfig, UserConfigExport } from 'vite'
import ViteComponents, { AntDesignVueResolver } from 'vite-plugin-components'
// Introduce eslint plugin
import eslintPlugin from 'vite-plugin-eslint'
import OptimizationPersist from 'vite-plugin-optimize-persist'
import PkgConfig from 'vite-plugin-package-config'
import viteSvgIcons from 'vite-plugin-svg-icons'
import { viteVConsole } from 'vite-plugin-vconsole'

function manualChunks (id: string) {
  if (!id.includes('node_modules')) {
    return
  }

  if (id.includes('@ant-design/icons-svg')) {
    const iconMatch = id.match(/icons-svg\/(?:es|lib)\/asn\/([^/.]+)/)
    const iconGroup = iconMatch ? iconMatch[1].charAt(0).toLowerCase() : 'core'
    return `vendor-ant-icons-${iconGroup}`
  }

  if (id.includes('@ant-design/icons-vue')) {
    return 'vendor-ant-icons'
  }

  if (id.includes('ant-design-vue')) {
    // 整库放进单一 chunk：按子目录拆会让 vc-pagination 等在其依赖的 _util(PropTypes)
    // chunk 初始化前就执行顶层 props，导致生产构建里 PropTypes 为 undefined 崩溃（循环依赖）。
    return 'vendor-antd'
  }

  if (id.includes('mqtt') || id.includes('reconnecting-websocket') || id.includes('eventemitter3')) {
    return 'vendor-realtime'
  }

  if (id.includes('vue') || id.includes('@vue')) {
    return 'vendor-vue'
  }

  if (id.includes('@amap')) {
    return 'vendor-map'
  }

  if (id.includes('moment')) {
    return 'vendor-moment'
  }

  if (id.includes('lodash')) {
    return 'vendor-lodash'
  }

  if (id.includes('axios') || id.includes('query-string')) {
    return 'vendor-http'
  }

  return 'vendor'
}

// https://vitejs.dev/config/
export default ({ command, mode }: ConfigEnv): UserConfigExport => defineConfig({
  plugins: [
    vue(),
    eslintPlugin({
      fix: true
    }),
    ViteComponents({
      customComponentResolvers: [AntDesignVueResolver()],
    }),
    viteSvgIcons({
      // 指定需要缓存的图标文件夹
      iconDirs: [path.resolve(process.cwd(), 'src/assets/icons')],
      // 指定symbolId格式
      symbolId: 'icon-[dir]-[name]',
    }),
    ...(command === 'serve'
      ? [viteVConsole({
          entry: path.resolve(__dirname, './src/main.ts'),
          localEnabled: true,
          config: {
            maxLogNumber: 1000,
            theme: 'light'
          }
        })]
      : []),
    PkgConfig(),
    OptimizationPersist()
    // [svgBuilder('./src/assets/icons/')] // All svg under src/icons/svg/ have been imported here, no need to import separately
  ],
  server: {
    open: true,
    host: '0.0.0.0',
    port: 8080
  },
  envDir: './env',
  resolve: {
    alias: [{
      // https://github.com/vitejs/vite/issues/279#issuecomment-635646269
      find: '/@',
      replacement: path.resolve(__dirname, './src'),
    }
    ]
  },
  css: {
    preprocessorOptions: {
      scss: {
        additionalData: '@use "./src/styles/variables" as *;'
      },
    }
  },
  base: '/',
  build: {
    target: ['es2015'], // 最低支持 es2015
    sourcemap: true,
    rollupOptions: {
      output: {
        manualChunks
      }
    }
  }
})
