import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { fetchPermissions } from '@/api/auth'

/**
 * 权限快照(前端文档 5.1、5.3、5.4)。
 *
 * `menus` 是后端返回的 `sys_menu.route_path` 集合(目录给全路径如 `/system`,页面给片段如 `user`),
 * `permCodes` 是按钮权限码集合。两者都是登录那一刻的快照,刷新靠 `GET /auth/permissions`。
 *
 * 前端不写超管分支:超管登录时后端直接返回全部权限码(见 5.3 第 3 条)。
 */
export const usePermissionStore = defineStore('permission', () => {
  const menus = ref<string[]>([])
  const permCodes = ref<string[]>([])
  /** 路由是否已经按当前快照生成过(守卫判断"要不要重建"用) */
  const routesReady = ref(false)

  const permCodeSet = computed(() => new Set(permCodes.value))
  const menuKeySet = computed(() => new Set(menus.value))

  /** 是否有某个权限码(v-perm 与页面内判断都用它) */
  function hasPerm(code: string | string[]): boolean {
    const codes = Array.isArray(code) ? code : [code]
    return codes.some((item) => permCodeSet.value.has(item))
  }

  /**
   * 本地路由是否对当前用户可见:要求它声明的所有 menuKey 都在快照里。
   * 例如"用户管理"页需要目录 `/system` 与页面片段 `user` 同时存在,与后端 route_path 的写法一一对应。
   */
  function hasAllMenuKeys(keys: string[]): boolean {
    return keys.every((key) => menuKeySet.value.has(key))
  }

  function setFromLogin(nextMenus: string[], nextPermCodes: string[]): void {
    menus.value = nextMenus
    permCodes.value = nextPermCodes
    routesReady.value = false
  }

  /** 403 之后重建快照(5.4):不做轮询、不做推送。 */
  async function reload(): Promise<void> {
    const snapshot = await fetchPermissions()
    menus.value = snapshot.menus
    permCodes.value = snapshot.permCodes
    routesReady.value = false
  }

  function markRoutesReady(): void {
    routesReady.value = true
  }

  function reset(): void {
    menus.value = []
    permCodes.value = []
    routesReady.value = false
  }

  return {
    menus,
    permCodes,
    routesReady,
    hasPerm,
    hasAllMenuKeys,
    setFromLogin,
    reload,
    markRoutesReady,
    reset,
  }
})
