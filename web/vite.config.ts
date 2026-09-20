import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { NaiveUiResolver } from 'unplugin-vue-components/resolvers'
import { defineConfig, loadEnv } from 'vite'

/**
 * 关键约束都能追溯到 frontend_architecture.md:
 * - 1.1 按需引入:组件走 `NaiveUiResolver()`;`useMessage` 这类 composable 由 auto-import 显式声明
 *   (解析器不处理它们)。naive-ui 的**类型**无法被 auto-import 注入,统一从 `@/types/naive` 收口。
 * - 7 环境配置:dev 通过 proxy 转发到后端,所以不需要后端开 CORS;生产的后端地址走 `VITE_API_BASE_URL`。
 * - 3.3 请求头:`X-Tenant-Code` 只在白名单外的公开接口带,这里不注入任何默认头(避免被全局带上)。
 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_')
  const proxyTarget = env.VITE_PROXY_TARGET || 'http://127.0.0.1:8080'

  return {
    plugins: [
      vue(),
      AutoImport({
        imports: [
          'vue',
          'vue-router',
          'pinia',
          {
            'naive-ui': ['useMessage', 'useDialog', 'useNotification', 'useLoadingBar'],
          },
        ],
        dts: 'src/types/auto-imports.d.ts',
      }),
      Components({
        resolvers: [NaiveUiResolver()],
        dts: 'src/types/components.d.ts',
      }),
    ],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      port: 5173,
      // 后端接口没有统一前缀(/auth/**、/system/**、/mall/admin/**),所以按首段分别转发。
      //
      // /mall 不能漏。漏掉时的表现不是 404 而是更坏的情况:请求被 Vite 的 SPA 兜底
      // 当成前端路由,返回 index.html 加 HTTP 200 —— axios 在网络层看不到任何错误,
      // 前端只在解析响应时拿到 HTML,表现为"商城管理端的数据出不来"。
      // 这类问题 typecheck 与 build 都发现不了,只有把应用真正跑起来才会暴露:
      // 商城页面(分类/商品/订单/售后/优惠券/运费模板/会员等级/评价)全部走 /mall/admin/**,
      // 而代理是在工程骨架阶段写的,那时还没有这些接口。
      proxy: {
        '/auth': { target: proxyTarget, changeOrigin: true },
        '/system': { target: proxyTarget, changeOrigin: true },
        '/mall': { target: proxyTarget, changeOrigin: true },
      },
    },
    build: {
      // dev 默认开 sourcemap,生产关(见 7 的环境差异表)
      sourcemap: mode !== 'production',
    },
  }
})
