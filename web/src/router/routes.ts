import type { RouteRecordRaw, Router } from 'vue-router'

import Layout from '@/layout/index.vue'

/**
 * 路由定义(前端文档 5.2)。
 *
 * 三件事必须一起看:
 * 1. 静态路由:登录、强制改密、403、404,以及挂在 Layout 下的首页 —— 这些不做权限过滤;
 * 2. 业务路由:全部由登录响应的 menus 决定是否添加,业务页面一律不静态注册;
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

/** 侧边栏与业务路由的唯一来源:一次定义,菜单与路由都从这里生成,避免两处不一致。 */
export interface BusinessMenuNode {
  /** 完整路径,与后端 sys_menu.route_path 拼出来的路径一致 */
  path: string
  title: string
  icon: string
  /**
   * 需要同时出现在权限快照里的 route_path。
   * 后端目录给全路径(/system)、页面给片段(user),所以这里两段都要写。
   */
  menuKeys: string[]
  children?: BusinessMenuNode[]
}

export const businessMenus: BusinessMenuNode[] = [
  {
    path: '/system',
    title: '系统管理',
    icon: 'settings',
    menuKeys: ['/system'],
    children: [
      // 部门已合进用户管理页(左侧部门树 + 右侧用户列表),不再单独占一个菜单项
      { path: '/system/user', title: '用户管理', icon: 'people', menuKeys: ['/system', 'user'] },
      { path: '/system/role', title: '角色管理', icon: 'shield', menuKeys: ['/system', 'role'] },
    ],
  },
  {
    path: '/platform',
    title: '平台管理',
    icon: 'crown',
    menuKeys: ['/platform'],
    children: [
      { path: '/platform/tenant', title: '租户管理', icon: 'storefront', menuKeys: ['/platform', 'tenant'] },
      { path: '/platform/package', title: '套餐管理', icon: 'gift', menuKeys: ['/platform', 'package'] },
      { path: '/platform/menu', title: '菜单管理', icon: 'list', menuKeys: ['/platform', 'menu'] },
      /**
       * 字典管理挂在平台管理下,与后端 sys_menu(id = 9,parent_id = 5,is_platform = 1)一致。
       * 它是平台专用菜单,不在任何套餐里,所以租户账号的权限快照中不会有 'dict' —— 菜单自然不显示。
       */
      { path: '/platform/dict', title: '字典管理', icon: 'book', menuKeys: ['/platform', 'dict'] },
    ],
  },
  /**
   * 商城管理(后端 V4 迁移里的 sys_menu 数据,route_path 与这里的 path 片段一一对应)。
   *
   * 注意 menuKeys 要写 [目录, 页面] 两段:后端目录给的是全路径(/mall),
   * 页面给的是片段(goods),权限快照里两者都要能对上,否则菜单不显示(5.2)。
   */
  {
    path: '/mall',
    title: '商城管理',
    icon: 'shopping',
    menuKeys: ['/mall'],
    children: [
      // 商品分类已合进商品管理页(左侧树 + 右侧列表),不再单独占一个菜单项。
      // 分类的增删改挂在树的节点后缀上,权限码(mall:category:*)没有变
      { path: '/mall/goods', title: '商品管理', icon: 'shop', menuKeys: ['/mall', 'goods'] },
      { path: '/mall/order', title: '订单管理', icon: 'profile', menuKeys: ['/mall', 'order'] },
      { path: '/mall/after-sale', title: '售后管理', icon: 'tool', menuKeys: ['/mall', 'after-sale'] },
      { path: '/mall/coupon', title: '优惠券', icon: 'gift', menuKeys: ['/mall', 'coupon'] },
      { path: '/mall/promotion', title: '满减活动', icon: 'thunderbolt', menuKeys: ['/mall', 'promotion'] },
      { path: '/mall/freight', title: '运费模板', icon: 'car', menuKeys: ['/mall', 'freight'] },
      { path: '/mall/member-level', title: '会员等级', icon: 'crown', menuKeys: ['/mall', 'member-level'] },
      { path: '/mall/review', title: '商品评价', icon: 'star', menuKeys: ['/mall', 'review'] },
    ],
  },
]

/** 移除函数集合:登出时必须逐个调用,否则换账号后会残留上一个账号的越权路由(5.2 第 3 条)。 */
let removeHandles: Array<() => void> = []

/**
 * 按当前权限快照添加业务路由。
 *
 * isVisible 由调用方传入(通常来自 permission store),这里不直接依赖 store ——
 * 保持路由模块可以在没有 Pinia 的场景下被复用。
 */
export function addBusinessRoutes(router: Router, isVisible: (keys: string[]) => boolean): void {
  resetBusinessRoutes()
  for (const menu of businessMenus) {
    for (const child of menu.children ?? []) {
      if (!isVisible(child.menuKeys)) {
        continue
      }
      removeHandles.push(
        router.addRoute(LAYOUT_ROUTE_NAME, {
          path: child.path,
          name: child.path,
          component: resolveView(child.path),
          meta: { title: child.title, icon: child.icon },
        }),
      )
    }
  }
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
