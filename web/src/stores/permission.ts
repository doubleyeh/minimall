import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { fetchPermissions } from '@/api/auth'
import type { MenuTreeNode } from '@/types/system'

/**
 * 权限快照(前端文档 5.1、5.3、5.4)。
 *
 * `menuTree` 是后端算好的**导航菜单树**(只有目录与页面,按钮不进树),侧边栏与业务路由都按它生成;
 * `permCodes` 是按钮权限码集合。两者都是登录那一刻的快照,刷新靠 `GET /auth/permissions`。
 *
 * 前端不写超管分支:超管登录时后端直接返回全部菜单与权限码(见 5.3 第 3 条)。
 */
export const usePermissionStore = defineStore('permission', () => {
  const menuTree = ref<MenuTreeNode[]>([])
  const permCodes = ref<string[]>([])
  /** 路由是否已经按当前快照生成过(守卫判断"要不要重建"用) */
  const routesReady = ref(false)

  const permCodeSet = computed(() => new Set(permCodes.value))

  /** 树里的页面节点数(仪表盘展示"可见菜单"用) */
  const menuPageCount = computed(() => countPages(menuTree.value))

  function countPages(nodes: MenuTreeNode[]): number {
    let total = 0
    for (const node of nodes) {
      if (node.menuType === 2) {
        total += 1
      }
      total += countPages(node.children ?? [])
    }
    return total
  }

  /** 是否有某个权限码(v-perm 与页面内判断都用它) */
  function hasPerm(code: string | string[]): boolean {
    const codes = Array.isArray(code) ? code : [code]
    return codes.some((item) => permCodeSet.value.has(item))
  }

  function setFromLogin(nextMenuTree: MenuTreeNode[], nextPermCodes: string[]): void {
    menuTree.value = nextMenuTree
    permCodes.value = nextPermCodes
    routesReady.value = false
  }

  /** 403 之后重建快照(5.4):不做轮询、不做推送。 */
  async function reload(): Promise<void> {
    const snapshot = await fetchPermissions()
    menuTree.value = snapshot.menuTree
    permCodes.value = snapshot.permCodes
    routesReady.value = false
  }

  function markRoutesReady(): void {
    routesReady.value = true
  }

  function reset(): void {
    menuTree.value = []
    permCodes.value = []
    routesReady.value = false
  }

  return {
    menuTree,
    permCodes,
    routesReady,
    menuPageCount,
    hasPerm,
    setFromLogin,
    reload,
    markRoutesReady,
    reset,
  }
})
