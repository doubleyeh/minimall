import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import * as authApi from '@/api/auth'
import type { LoginPayload } from '@/api/auth'
import type { Id } from '@/types/api'
import { clearTokens, getRefreshToken, setTokens } from '@/utils/auth'
import { usePermissionStore } from './permission'

/** 用户信息在 localStorage 的键。刷新页面后要靠它恢复顶栏显示(不重新调登录接口)。 */
const PROFILE_KEY = 'mini-mall:profile'

interface Profile {
  userId: Id
  tenantId: Id
  nickname: string
  isSuperUser: boolean
  mustChangePassword: boolean
}

function readProfile(): Profile | null {
  const raw = localStorage.getItem(PROFILE_KEY)
  if (!raw) {
    return null
  }
  try {
    return JSON.parse(raw) as Profile
  } catch {
    // 存坏了就当作没有,不能让一段脏数据把整个应用卡在白屏
    localStorage.removeItem(PROFILE_KEY)
    return null
  }
}

/**
 * 登录用户(前端文档 4.4)。
 *
 * 只放"身份与展示"信息;权限集合在 permission store,令牌在 localStorage(4.1)。
 * 刷新页面后:令牌还在 → 用 localStorage 里的 profile 恢复顶栏;权限则由路由守卫拉 `/auth/permissions` 重建。
 */
export const useUserStore = defineStore('user', () => {
  const profile = ref<Profile | null>(readProfile())

  const isLoggedIn = computed(() => profile.value !== null)
  const isSuperUser = computed(() => profile.value?.isSuperUser ?? false)

  async function login(payload: LoginPayload): Promise<void> {
    const result = await authApi.login(payload)
    // 令牌与用户信息一起落盘:顺序上先落令牌,后面任何一步失败都不会出现"有令牌但没有身份"
    setTokens({ token: result.token, refreshToken: result.refreshToken })
    profile.value = {
      userId: result.userId,
      tenantId: result.tenantId,
      nickname: result.nickname,
      isSuperUser: result.isSuperUser,
      mustChangePassword: result.mustChangePassword,
    }
    localStorage.setItem(PROFILE_KEY, JSON.stringify(profile.value))

    // 权限快照只在此刻写一次:登录响应的 menus/permCodes 是"登录那一刻"的(5.1)
    usePermissionStore().setFromLogin(result.menus, result.permCodes)
  }

  /**
   * 登出。必须先请求后端再清本地:后端要按 refreshToken 精确撤销本次登录的刷新令牌(4.6)。
   * 但请求失败时本地照样要清干净 —— 否则用户会卡在"点了登出还是登录态"。
   */
  async function logout(): Promise<void> {
    const refreshToken = getRefreshToken()
    if (refreshToken) {
      try {
        await authApi.logout(refreshToken)
      } catch {
        // 忽略:本地登出不允许被网络问题阻断
      }
    }
    reset()
  }

  /** 只清本地状态(刷新令牌失效、改密后强制重登等场景用)。 */
  function reset(): void {
    clearTokens()
    localStorage.removeItem(PROFILE_KEY)
    profile.value = null
    usePermissionStore().reset()
  }

  /** 改密成功后后端会让全部会话失效,前端必须回到未登录状态(4.5)。 */
  function markPasswordChanged(): void {
    if (profile.value) {
      profile.value = { ...profile.value, mustChangePassword: false }
      localStorage.setItem(PROFILE_KEY, JSON.stringify(profile.value))
    }
  }

  return {
    profile,
    isLoggedIn,
    isSuperUser,
    login,
    logout,
    reset,
    markPasswordChanged,
  }
})
