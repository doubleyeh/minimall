/**
 * 客户登录态。
 *
 * 三条约定(见 mall_architecture.md 3.1):
 * 1. 令牌放 Storage,是**客户端 JWT**,与后台管理员的 Sa-Token 会话完全分离,不复用;
 * 2. 登录凭证是 `wx.login()` 返回的 code —— 所以**没有登录页**,启动时静默换取令牌;
 * 3. 令牌 30 天,过期后同样静默重登。`code` 可以重复获取,所以不需要 refresh token 机制。
 *
 * 本模块不 import `utils/request`:那个模块遇到 401 要回调本模块,互相 import 会成环。
 * 登录请求因此直接用 `wx.request` 发。
 */
import { ClientLoginResponse } from '../types/client'
import { API_BASE_URL, REQUEST_TIMEOUT, TENANT_CODE } from './env'

const TOKEN_KEY = 'mall_token'

export function getToken(): string {
  const token = wx.getStorageSync(TOKEN_KEY)
  return typeof token === 'string' ? token : ''
}

export function saveToken(token: string): void {
  wx.setStorageSync(TOKEN_KEY, token)
}

export function clearToken(): void {
  wx.removeStorageSync(TOKEN_KEY)
}

/**
 * 并发单飞。
 *
 * 首页会同时发出多个请求,令牌过期时它们会一起拿到 401 并各自触发重登 ——
 * 那会发出多次 code2Session。而**真实环境下 code 是一次性的**,第二次必然失败。
 * 所以同一时刻只允许一次登录在飞,其余等它的结果。
 *
 * (H5 版没有这个问题:浏览器里刷新令牌走的是并发单飞的刷新接口,而这里根本没有 refresh token。)
 */
let loginInFlight: Promise<string> | null = null

export function silentLogin(): Promise<string> {
  if (loginInFlight) {
    return loginInFlight
  }
  loginInFlight = doSilentLogin().finally(() => {
    loginInFlight = null
  })
  return loginInFlight
}

/** 已有令牌就直接用,没有才登录。启动时与进入需要鉴权的页面前调用。 */
export function ensureLogin(): Promise<string> {
  const token = getToken()
  return token ? Promise.resolve(token) : silentLogin()
}

function doSilentLogin(): Promise<string> {
  return new Promise<string>((resolve, reject) => {
    wx.login({
      success: (loginRes) => {
        if (!loginRes.code) {
          reject(new Error('wx.login 未返回 code'))
          return
        }
        wx.request<{ code: number; message: string; data: ClientLoginResponse }>({
          url: `${API_BASE_URL}/mall/api/auth/wx-login`,
          method: 'POST',
          // 必须显式声明 JSON:wx.request 默认按 form 表单序列化 data,
          // 后端是 @RequestBody(JSON),不声明会直接反序列化失败
          header: { 'Content-Type': 'application/json', 'X-Tenant-Code': TENANT_CODE },
          data: { code: loginRes.code },
          timeout: REQUEST_TIMEOUT,
          success: (res) => {
            const body = res.data
            if (res.statusCode === 200 && body && body.code === 0 && body.data && body.data.token) {
              saveToken(body.data.token)
              resolve(body.data.token)
              return
            }
            reject(new Error(body && body.message ? body.message : `登录失败(${res.statusCode})`))
          },
          fail: (err) => reject(new Error(`登录请求失败:${err.errMsg}`)),
        })
      },
      fail: (err) => reject(new Error(`wx.login 调用失败:${err.errMsg}`)),
    })
  })
}
