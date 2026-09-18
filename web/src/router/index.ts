import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'

/**
 * 路由(阶段一:只有骨架)。
 *
 * <p>正式的构成见前端文档 5.2:登录页/403/404/改密页是**静态路由**,
 * 业务页面全部由登录响应的 `menus` **动态添加**;登出时必须把动态路由移除干净。
 * 这一步先只放一个首页,后续在权限链路那一步补全。
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'dashboard',
    component: () => import('@/views/dashboard/index.vue'),
    meta: { title: '首页' },
  },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
})
