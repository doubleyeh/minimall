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
  AppsOutline,
  BagHandleOutline,
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
  StorefrontOutline,
} from '@vicons/ionicons5'
import { NIcon } from 'naive-ui'
import { computed, h, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { businessMenus } from '@/router/routes'
import { useAppStore } from '@/stores/app'
import { usePermissionStore } from '@/stores/permission'
import type { MenuOption } from '@/types/naive'

/**
 * 图标只从 @vicons/ionicons5 一套里取(前端文档 1:禁止混用多套图标库)。
 *
 * **这张表必须覆盖 businessMenus 里用到的每个 icon 名**:renderIcon 查不到就返回 undefined,
 * 菜单照常渲染、只是没有图标 —— 静默降级,不报错也不警告。商城那一整块菜单
 * (shopping/shop/appstore/profile/tool/thunderbolt/car/crown/star)此前就是这样一直没有图标的,
 * 而后端 sys_menu.icon 其实都配了。新增菜单时请连同这张表一起加。
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
}

function renderIcon(name: string) {
  const icon = icons[name]
  return icon ? () => h(NIcon, null, { default: () => h(icon) }) : undefined
}

const route = useRoute()
const router = useRouter()
const app = useAppStore()
const permission = usePermissionStore()

const activeKey = computed(() => route.path)

/**
 * 菜单项来自 `businessMenus`(与业务路由同一份定义),
 * 但只保留当前用户权限快照里存在的节点 —— 目录下没有任何可见子项时整块隐藏。
 */
const options = computed<MenuOption[]>(() => {
  const result: MenuOption[] = []
  for (const menu of businessMenus) {
    const children = (menu.children ?? []).filter((child) => permission.hasAllMenuKeys(child.menuKeys))
    // 目录下没有任何可见子项时整块隐藏,避免出现点不开的空目录
    if (children.length === 0 || !permission.hasAllMenuKeys(menu.menuKeys)) {
      continue
    }
    result.push({
      key: menu.path,
      label: menu.title,
      icon: renderIcon(menu.icon),
      children: children.map((child) => ({
        key: child.path,
        label: child.title,
        icon: renderIcon(child.icon),
      })),
    })
  }
  return result
})

function onSelect(key: string): void {
  if (key !== route.path) {
    void router.push(key)
  }
}
</script>
