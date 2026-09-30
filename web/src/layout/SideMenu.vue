<template>
  <n-menu
    :value="activeKey"
    :collapsed="app.sidebarCollapsed"
    :collapsed-width="64"
    :collapsed-icon-size="20"
    :options="options"
    :root-indent="18"
    @update:value="onSelect"
  />
</template>

<script setup lang="ts">
import {
  AlertCircleOutline,
  AppsOutline,
  BagHandleOutline,
  BarChartOutline,
  BookOutline,
  BuildOutline,
  BusinessOutline,
  CarOutline,
  CartOutline,
  FlashOutline,
  GiftOutline,
  ListOutline,
  PeopleOutline,
  PricetagsOutline,
  RibbonOutline,
  SettingsOutline,
  ShieldCheckmarkOutline,
  StarOutline,
  TimeOutline,
  StorefrontOutline,
  WalletOutline,
} from '@vicons/ionicons5'
import { NIcon } from 'naive-ui'
import { computed, h, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { resolveMenuPath } from '@/router/routes'
import { useAppStore } from '@/stores/app'
import { usePermissionStore } from '@/stores/permission'
import type { MenuOption } from '@/types/naive'
import type { MenuTreeNode } from '@/types/system'

/**
 * 图标只从 @vicons/ionicons5 一套里取(前端文档 1:禁止混用多套图标库)。
 *
 * **图标名以后端 `sys_menu.icon` 为准,这张表是它的取值清单**:菜单里的名字查不到时
 * 会打一条 warn 并回退通用图标,而不是静默没有图标 —— 后者只表现为"这个菜单就是没图标",
 * 没人会去查。新增菜单时请把名字补进这张表,或改用后端已有的名字。
 *
 * 另外两个名字在 ionicons5 里并不存在(ShoppingOutline、CrownOutline),
 * 所以商城目录用 CartOutline、会员等级用 RibbonOutline。
 */
const icons: Record<string, Component> = {
  // 系统管理
  settings: SettingsOutline,
  people: PeopleOutline,
  shield: ShieldCheckmarkOutline,
  business: BusinessOutline,
  // 平台管理
  storefront: StorefrontOutline,
  gift: GiftOutline,
  list: ListOutline,
  book: BookOutline,
  wallet: WalletOutline,
  // 商城管理
  shopping: CartOutline,
  shop: PricetagsOutline,
  appstore: AppsOutline,
  profile: BagHandleOutline,
  tool: BuildOutline,
  thunderbolt: FlashOutline,
  car: CarOutline,
  crown: RibbonOutline,
  star: StarOutline,
  alert: AlertCircleOutline,
  chart: BarChartOutline,
  // 平台管理 · 定时任务
  time: TimeOutline,
}

function renderIcon(name: string | null) {
  if (!name) {
    return undefined
  }
  const icon = icons[name]
  if (!icon) {
    console.warn(`[SideMenu] 图标名未登记:${name}(请在 icons 表补上,或改后端 sys_menu.icon)`)
    return () => h(NIcon, null, { default: () => h(icons.appstore!) })
  }
  return () => h(NIcon, null, { default: () => h(icon) })
}

const route = useRoute()
const router = useRouter()
const app = useAppStore()
const permission = usePermissionStore()

const activeKey = computed(() => route.path)

/**
 * 把后端菜单树转成 n-menu 的 options。
 *
 * 路径拼法直接复用路由侧的函数 —— 菜单项的 key 必须与路由 path 完全一致,两处各写一遍迟早会长歪。
 */
function toOptions(nodes: MenuTreeNode[], parentPath: string): MenuOption[] {
  return nodes.map((node) => {
    const fullPath = resolveMenuPath(node, parentPath)
    const children = toOptions(node.children ?? [], fullPath)
    return {
      key: fullPath,
      label: node.menuName,
      icon: renderIcon(node.icon),
      ...(children.length > 0 ? { children } : {}),
    }
  })
}

/** 菜单项直接来自后端算好的导航树:树里有的就是当前用户能看到的,前端不再过滤。 */
const options = computed<MenuOption[]>(() => toOptions(permission.menuTree, ''))

function onSelect(key: string): void {
  if (key !== route.path) {
    void router.push(key)
  }
}
</script>
