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
  BusinessOutline,
  GiftOutline,
  ListOutline,
  PeopleOutline,
  SettingsOutline,
  ShieldCheckmarkOutline,
  StorefrontOutline,
} from '@vicons/ionicons5'
import { NIcon } from 'naive-ui'
import { computed, h, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { businessMenus } from '@/router/routes'
import { useAppStore } from '@/stores/app'
import { usePermissionStore } from '@/stores/permission'
import type { MenuOption } from '@/types/naive'

/** 图标只从 @vicons/ionicons5 一套里取(前端文档 1:禁止混用多套图标库)。 */
const icons: Record<string, Component> = {
  settings: SettingsOutline,
  people: PeopleOutline,
  shield: ShieldCheckmarkOutline,
  business: BusinessOutline,
  storefront: StorefrontOutline,
  gift: GiftOutline,
  list: ListOutline,
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
