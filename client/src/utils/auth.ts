/**
 * 客户端令牌与租户编码的本地存储。
 *
 * 三个约定:
 * 1. 令牌放 localStorage(小程序端没有 Cookie 会话,也不需要 —— 后端用 Authorization 头);
 * 2. **租户编码单独存**:微信 openid 按小程序发放,同一个客户在不同租户是两个身份,
 *    所以每个请求都必须带 X-Tenant-Code,它决定"连的是哪个商家";
 * 3. 令牌过期由后端返回 401,前端统一跳登录并静默重新 wx-login(见 request.ts)。
 */

const TOKEN_KEY = 'mall_token'
const TENANT_KEY = 'mall_tenant_code'

/** 默认租户编码:本地联调用种子数据里的平台租户;真机上由小程序配置下发 */
const DEFAULT_TENANT_CODE = 'platform'

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

export function getTenantCode(): string {
  return localStorage.getItem(TENANT_KEY) || DEFAULT_TENANT_CODE
}

export function setTenantCode(code: string): void {
  localStorage.setItem(TENANT_KEY, code)
}

export function isLoggedIn(): boolean {
  const token = getToken()
  return token != null && token.length > 0
}

export function clearAuth(): void {
  localStorage.removeItem(TOKEN_KEY)
}
