/**
 * 请求层(基于 `wx.request`)。
 *
 * 与 H5 版是同样的三条职责,只是换了传输方式:
 * 1. 每个请求都带 `X-Tenant-Code` —— openid 按租户隔离,不带就查不到客户;
 * 2. 业务错误统一抛 {@link ApiError} —— 后端用 HTTP 200 + `code !== 0` 承载业务错误(架构文档 7.3);
 * 3. 401 时静默重登并重试一次,页面不必各自处理令牌过期。
 *
 * **保持与 H5 版完全相同的调用签名**(get/post/put/delete),这样 `api/` 下的接口定义可以整体照搬。
 */
import { API_BASE_URL, REQUEST_TIMEOUT, TENANT_CODE } from './env'
import { getToken, silentLogin } from './auth'

export class ApiError extends Error {
  readonly code: number

  constructor(code: number, message: string) {
    super(message)
    this.code = code
    this.name = 'ApiError'
  }
}

interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

type Method = 'GET' | 'POST' | 'PUT' | 'DELETE'

/**
 * 请求头。
 *
 * `Content-Type: application/json` 必须显式带上:`fetch` 传字符串 body 时会自动设,
 * 而 `wx.request` 对对象类型的 data **默认按 form 表单序列化** —— 不声明的话,
 * 后端的 `@RequestBody` 反序列化会失败,表现为一堆看不懂的 400。
 */
function buildHeader(hasBody: boolean): Record<string, string> {
  const header: Record<string, string> = { 'X-Tenant-Code': TENANT_CODE }
  if (hasBody) {
    header['Content-Type'] = 'application/json'
  }
  const token = getToken()
  if (token) {
    header.Authorization = `Bearer ${token}`
  }
  return header
}

function rawRequest<T>(
  method: Method,
  url: string,
  body?: unknown,
): Promise<{ statusCode: number; body: ApiResponse<T> | null }> {
  return new Promise((resolve, reject) => {
    wx.request<ApiResponse<T>>({
      url: `${API_BASE_URL}${url}`,
      method,
      data: body as WechatMiniprogram.IAnyObject | undefined,
      header: buildHeader(body !== undefined),
      timeout: REQUEST_TIMEOUT,
      success: (res) => {
        resolve({ statusCode: res.statusCode, body: (res.data ?? null) as ApiResponse<T> | null })
      },
      fail: (err) => reject(new ApiError(-1, `网络请求失败:${err.errMsg}`)),
    })
  })
}

async function doRequest<T>(method: Method, url: string, body?: unknown): Promise<T> {
  let response = await rawRequest<T>(method, url, body)

  if (response.statusCode === 401) {
    // 令牌 30 天过期:静默重登后重试一次。
    // 重试是安全的 —— 401 由鉴权过滤器在进入 Controller **之前**返回,这一次请求并没有执行,
    // 所以不会出现"写操作执行了但被当成失败重试"的重复提交。
    await silentLogin()
    response = await rawRequest<T>(method, url, body)
    if (response.statusCode === 401) {
      throw new ApiError(40100, '登录已过期,请重新进入小程序')
    }
  }

  if (response.statusCode >= 400) {
    throw new ApiError(response.statusCode, `请求失败(${response.statusCode})`)
  }

  const payload = response.body
  if (payload === null || typeof payload !== 'object') {
    throw new ApiError(-1, '响应格式异常')
  }
  if (payload.code !== 0) {
    // 业务错误:后端已经给了可直接展示的文案(例如「库存不足,仅剩 2 件」)
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
