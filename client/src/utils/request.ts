import router from '@/router'
import { clearAuth, getTenantCode, getToken } from '@/utils/auth'

/**
 * 请求层(基于 fetch,不额外引 axios:这里只需要 GET/POST/PUT/DELETE + 统一错误处理)。
 *
 * 三个必须在请求层解决的问题:
 * 1. **每个请求都带 X-Tenant-Code**:否则后端不知道是哪个商家,客户也会查不到(openid 按租户隔离);
 * 2. **业务错误码统一抛异常**:后端按 7.3 用 HTTP 200 + code != 0 承载业务错误,
 *    不统一处理的话每个调用点都要写一遍 if (code !== 0);
 * 3. **401 统一跳登录**:令牌 30 天过期后前端静默重登,页面不该各自处理。
 */

/** 后端统一响应体(架构文档 7.3) */
interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

export class ApiError extends Error {
  readonly code: number

  constructor(code: number, message: string) {
    super(message)
    this.code = code
    this.name = 'ApiError'
  }
}

async function doRequest<T>(method: string, url: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {
    'X-Tenant-Code': getTenantCode(),
  }
  const token = getToken()
  if (token) {
    // 与后端 ClientAuthFilter 约定一致:Authorization: Bearer <token>
    headers.Authorization = `Bearer ${token}`
  }
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }

  const response = await fetch(url, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })

  if (response.status === 401) {
    // 令牌失效:清掉本地令牌并回登录页(带上原地址,登录后回到用户原本要去的页面)
    clearAuth()
    const current = router.currentRoute.value
    if (current.name !== 'Login') {
      await router.replace({ name: 'Login', query: { redirect: current.fullPath } })
    }
    throw new ApiError(40100, '登录已过期,请重新登录')
  }

  if (!response.ok) {
    throw new ApiError(response.status, `请求失败(${response.status})`)
  }

  const payload = (await response.json()) as ApiResponse<T>
  if (payload.code !== 0) {
    // 业务错误:后端已经给了可直接展示的文案(如"库存不足,仅剩 2 件")
    throw new ApiError(payload.code, payload.message)
  }
  return payload.data
}

export const request = {
  get<T>(url: string): Promise<T> {
    return doRequest<T>('GET', url)
  },
  post<T>(url: string, body?: unknown): Promise<T> {
    return doRequest<T>('POST', url, body)
  },
  put<T>(url: string, body?: unknown): Promise<T> {
    return doRequest<T>('PUT', url, body)
  },
  delete<T>(url: string, body?: unknown): Promise<T> {
    return doRequest<T>('DELETE', url, body)
  },
}
