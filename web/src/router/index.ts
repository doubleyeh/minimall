import { createRouter, createWebHistory } from 'vue-router'

import { usePermissionStore } from '@/stores/permission'
import { useUserStore } from '@/stores/user'
import { getToken } from '@/utils/auth'
import { setAuthFailedHandler, setForbiddenHandler } from '@/utils/request'
import { message } from '@/utils/discrete'
import { addBusinessRoutes, resetBusinessRoutes, staticRoutes } from './routes'

export const router = createRouter({
  history: createWebHistory(),
  routes: staticRoutes,
})

/**
 * 全局守卫(前端文档 4.5、5.2)。
 *
 * 关键在最后一段:有令牌但动态路由还没建立(页面刷新、刚登录)时,
 * 用 `GET /auth/permissions` 重建,然后 `return { ...to, replace: true }` 让当前这次导航重新匹配 ——
 * 不重新匹配的话,这次导航已经按"没有这条路由"走完了,页面仍是 404。
 */
router.beforeEach(async (to) => {
  const user = useUserStore()
  const permission = usePermissionStore()

  if (!getToken()) {
    if (to.meta.public) {
      return true
    }
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  // 已登录还去登录页 -> 回首页(避免重复登录造成多端令牌)
  if (to.path === '/login') {
    return { path: '/' }
  }

  // 强制改密闸门(4.5):后端也会拦(403),前端这一步是为了不让用户看到一堆报错
  const mustChangePassword = user.profile?.mustChangePassword ?? false
  if (mustChangePassword && to.path !== '/change-password') {
    return { path: '/change-password' }
  }
  if (!mustChangePassword && to.path === '/change-password') {
    return { path: '/' }
  }

  if (!permission.routesReady) {
    // 刷新页面场景:令牌在,但权限快照是内存态(5.2 第 4 条)
    if (permission.menus.length === 0) {
      try {
        await permission.reload()
      } catch {
        // 拉不到快照说明登录态已不可用(拦截器会处理令牌清理),这里只负责别再往下走
        user.reset()
        return { path: '/login', query: { redirect: to.fullPath } }
      }
    }
    addBusinessRoutes(router, (keys) => permission.hasAllMenuKeys(keys))
    permission.markRoutesReady()
    return { ...to, replace: true }
  }

  return true
})

/**
 * 403 处理(前端文档 5.4):刷新一次权限快照并重建菜单/按钮。
 *
 * 必须限频:一个页面同时发多个请求时可能连续收到 403,不加限频会打出一串 `/auth/permissions`。
 */
let lastForbiddenHandledAt = 0
const FORBIDDEN_RELOAD_INTERVAL = 10_000

setForbiddenHandler(() => {
  const now = Date.now()
  if (now - lastForbiddenHandledAt < FORBIDDEN_RELOAD_INTERVAL) {
    return
  }
  lastForbiddenHandledAt = now
  const permission = usePermissionStore()
  void permission
    .reload()
    .then(() => {
      resetBusinessRoutes()
      addBusinessRoutes(router, (keys) => permission.hasAllMenuKeys(keys))
      permission.markRoutesReady()
    })
    .catch(() => {
      message.warning('没有访问权限,请重新登录后再试')
    })
})

/** 登录态彻底失效(刷新失败、令牌不可用):清理本地状态与动态路由,回到登录页。 */
setAuthFailedHandler(() => {
  resetBusinessRoutes()
  usePermissionStore().reset()
  const redirect = router.currentRoute.value.fullPath
  void router.replace({ path: '/login', query: redirect === '/login' ? undefined : { redirect } })
})

export { resetBusinessRoutes }
