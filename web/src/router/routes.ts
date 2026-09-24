import type { RouteRecordRaw, Router } from 'vue-router'

import Layout from '@/layout/index.vue'
import type { MenuTreeNode } from '@/types/system'

/**
 * 路由定义(前端文档 5.2、架构文档 7.4)。
 *
 * 三件事必须一起看:
 * 1. 静态路由:登录、强制改密、403、404,以及挂在 Layout 下的首页 —— 这些不做权限过滤;
 * 2. 业务路由:全部由后端返回的菜单树生成(登录响应与 `GET /auth/permissions` 里的 menuTree),
 *    业务页面一律不静态注册。后端加一个菜单,前端不用改代码;
 * 3. 404 通配必须最后加(见 addBusinessRoutes),否则刷新业务页面时会被通配先拦成 404。
 */

const viewModules = import.meta.glob('/src/views/**/*.vue')

/**
 * route_path 到视图文件的映射约定(5.2):/system/user 对应 views/system/user/index.vue。
 *
 * 这是隐式约定,填错的表现是白屏,所以这里在找不到文件时显式报错并回退 404,
 * 而不是静默给一个空组件(那样只会看到"页面一片空白",排查方向会跑偏)。
 */
function resolveView(path: string): () => Promise<unknown> {
  const key = `/src/views${path}/index.vue`
  const loader = viewModules[key]
  if (!loader) {
    console.error(`[router] 找不到路由对应的视图文件:${key}(映射约定见前端文档 5.2 与后端 sys_menu.route_path)`)
    return () => import('@/views/error/404.vue')
  }
  return loader
}

/** 布局路由的名字:动态业务路由作为它的子路由添加,避免嵌套一层 Layout。 */
export const LAYOUT_ROUTE_NAME = 'Layout'

export const staticRoutes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/index.vue'),
    meta: { title: '登录', public: true },
  },
  {
    path: '/change-password',
    name: 'ChangePassword',
    component: () => import('@/views/password/index.vue'),
    meta: { title: '修改密码' },
  },
  {
    path: '/403',
    name: 'Forbidden',
    component: () => import('@/views/error/403.vue'),
    meta: { title: '无权限', public: true },
  },
  {
    path: '/404',
    name: 'NotFound',
    component: () => import('@/views/error/404.vue'),
    meta: { title: '页面不存在', public: true },
  },
  {
    path: '/',
    name: LAYOUT_ROUTE_NAME,
    component: Layout,
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/dashboard/index.vue'),
        meta: { title: '首页', icon: 'home' },
      },
    ],
  },
]

/**
 * 拼出完整路径:后端目录给的是全路径(/system),页面给的是父级下的片段(user),
 * 所以只有不以 / 开头的片段才需要拼到父路径后面。
 *
 * 侧边栏也用这一个函数 —— 两处各写一遍迟早会长歪,而菜单项的 key 必须与路由 path 完全一致。
 */
export function resolveMenuPath(node: MenuTreeNode, parentPath: string): string {
  const segment = node.routePath ?? ''
  if (segment === '') {
    return ''
  }
  return segment.startsWith('/') ? segment : `${parentPath}/${segment}`
}

/** 目录只往下递归(它自己没有页面),页面才注册成路由。 */
function registerBusinessRoutes(router: Router, nodes: MenuTreeNode[], parentPath: string): void {
  for (const node of nodes) {
    const fullPath = resolveMenuPath(node, parentPath)
    if (node.menuType === 1) {
      registerBusinessRoutes(router, node.children ?? [], fullPath)
      continue
    }
    if (node.menuType !== 2 || fullPath === '') {
      continue
    }
    removeHandles.push(
      router.addRoute(LAYOUT_ROUTE_NAME, {
        path: fullPath,
        name: fullPath,
        component: resolveView(fullPath),
        meta: { title: node.menuName, icon: node.icon ?? '' },
      }),
    )
  }
}

/** 移除函数集合:登出时必须逐个调用,否则换账号后会残留上一个账号的越权路由(5.2 第 3 条)。 */
let removeHandles: Array<() => void> = []

/**
 * 按后端返回的菜单树添加业务路由。
 *
 * 树是服务端按当前用户算好的(只含可见的目录与页面,按钮不进树),这里不再做权限过滤 ——
 * 前端的显隐只是显示层,真正的鉴权始终在服务端。
 */
export function addBusinessRoutes(router: Router, menuTree: MenuTreeNode[]): void {
  resetBusinessRoutes()
  registerBusinessRoutes(router, menuTree, '')
  // 404 通配最后加(5.2 第 2 条):先加的话,业务页面刷新会被它先捕获
  removeHandles.push(
    router.addRoute({
      path: '/:pathMatch(.*)*',
      name: 'NotFoundCatchAll',
      redirect: '/404',
    }),
  )
}

/** 登出/重建路由前调用:把动态路由(含 404 通配)清理干净。 */
export function resetBusinessRoutes(): void {
  for (const remove of removeHandles) {
    remove()
  }
  removeHandles = []
}
