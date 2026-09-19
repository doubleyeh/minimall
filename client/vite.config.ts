import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'

/**
 * 小程序端(移动 H5)构建配置。
 *
 * 单独一个应用而不是塞进 web/(商家管理端):两者的布局、路由、登录态、目标端完全不同 ——
 * 管理端是侧边菜单 + 后台会话,这里是底部标签栏 + 30 天客户端令牌。
 * 放在一个工程里会互相牵制(比如管理端的权限路由守卫会拦住商城页面)。
 *
 * 代理:开发期把 /mall/api 与 /pay 转发到后端,浏览器里没有跨域问题;
 * 真机/小程序环境下这两个前缀直连网关,不需要改前端代码。
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5174,
    proxy: {
      '/mall/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
      '/pay': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
})
