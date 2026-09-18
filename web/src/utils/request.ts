import axios from 'axios'
import type { AxiosError, AxiosRequestConfig, AxiosResponse, InternalAxiosRequestConfig } from 'axios'

import type { ApiEnvelope } from '@/types/api'
import type { TokenPair } from '@/types/auth'
import { clearTokens, getRefreshToken, getToken, setTokens } from '@/utils/auth'
import { message } from '@/utils/discrete'

/** 成功码(后端 architecture.md 7.3) */
const CODE_OK = 0

/** 业务错误:带上后端返回的 code,便于调用方按需分支(禁止把文案当成判断依据) */
export class ApiError extends Error {
  constructor(
    readonly code: number,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

/** 项目内扩展的请求配置 */
export interface AppRequestConfig extends AxiosRequestConfig {
  /** 需要自行处理错误的接口(如表单字段级报错)置 true,跳过全局提示(前端文档 3.1) */
  skipErrorMessage?: boolean
}

type RetriableConfig = InternalAxiosRequestConfig & {
  /** 每个业务请求因 401 最多重试一次(4.2 第 1 条) */
  _retried?: boolean
  skipErrorMessage?: boolean
}

const baseURL = import.meta.env.VITE_API_BASE_URL || undefined

const http = axios.create({ baseURL, timeout: 15000 })

/**
 * 刷新专用实例:不挂任何拦截器。
 * 否则刷新请求自己返回 401 时又会进入拦截器的 401 分支,变成递归(4.2 第 2 条要求显式排除)。
 */
const refreshClient = axios.create({ baseURL, timeout: 15000 })

/** 并发单飞的刷新 Promise(4.2) */
let refreshPromise: Promise<TokenPair> | null = null

/**
 * 登录态失效的回调由认证层注册。不在这里 import router / store:
 * 那会形成 request → store → request 的循环依赖,而且"请求层负责跳转"本身也是职责串位。
 */
let onAuthFailed: (() => void) | null = null
let onForbidden: (() => void) | null = null

export function setAuthFailedHandler(handler: () => void): void {
  onAuthFailed = handler
}

export function setForbiddenHandler(handler: () => void): void {
  onForbidden = handler
}

http.interceptors.request.use((config) => {
  // 每次现读 localStorage:多标签页下别的标签可能刚写过新令牌(4.1)
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  // 成功路径不在这里拆包:axios 的拦截器签名要求返回 AxiosResponse,
  // 在这里返回 data 只能靠类型断言硬骗过去。拆包统一放在下面的门面里做(见 request)。
  (response) => response,
  async (error: AxiosError<ApiEnvelope<unknown>>) => {
    const config = error.config as RetriableConfig | undefined
    const status = error.response?.status

    if (!error.response) {
      if (!config?.skipErrorMessage) {
        message.error('网络异常,请稍后重试')
      }
      return Promise.reject(error)
    }

    if (status === 401) {
      return handleUnauthorized(config, error)
    }

    if (status === 403) {
      // 不清令牌、不跳登录页(3.4):先让权限层去刷新一次快照(5.4)
      if (!config?.skipErrorMessage) {
        message.error(error.response.data?.message || '没有访问权限')
      }
      onForbidden?.()
      return Promise.reject(error)
    }

    if (status === 429) {
      message.warning('操作过于频繁,请稍后再试')
      return Promise.reject(error) // 禁止自动重试(3.4)
    }

    if (!config?.skipErrorMessage) {
      message.error(error.response.data?.message || `请求失败(${status})`)
    }
    return Promise.reject(error)
  },
)

/**
 * 拼包:`code === 0` 返回 `data` 本身,业务层不再写 `.data.data`(3.1)。
 * `code !== 0` 但 HTTP 是 2xx 的情况(业务错误)也在这里统一处理。
 */
function unwrap(response: AxiosResponse<ApiEnvelope<unknown>>): unknown {
  const body = response.data
  if (body && typeof body === 'object' && 'code' in body) {
    const config = response.config as RetriableConfig
    if (body.code !== CODE_OK) {
      if (!config.skipErrorMessage) {
        message.error(body.message || '操作失败')
      }
      throw new ApiError(body.code, body.message)
    }
    return body.data
  }
  // 非统一响应体(理论上不该出现):原样返回,避免静默丢数据
  return body
}

/**
 * 401 处理(前端文档 4.2,强制)。三种情况必须区分开:
 * 1. 失败的是刷新请求本身 → 直接走登出,不能再触发刷新(否则递归)
 * 2. 已经重试过一次 → 直接失败,不无限重试
 * 3. 其他 → 单飞刷新:第一个请求发起,其余 await 同一个 Promise
 */
async function handleUnauthorized(
  config: RetriableConfig | undefined,
  error: AxiosError<ApiEnvelope<unknown>>,
): Promise<unknown> {
  if (!config) {
    return Promise.reject(error)
  }
  if (config.url?.includes('/auth/refresh')) {
    return failAuth(error)
  }
  if (config._retried) {
    return failAuth(error)
  }

  config._retried = true
  if (!refreshPromise) {
    refreshPromise = doRefresh().finally(() => {
      refreshPromise = null
    })
  }

  try {
    await refreshPromise
  } catch {
    return failAuth(error)
  }
  // 重放原请求:请求拦截器会从 localStorage 现读新令牌并覆盖 Authorization
  return http.request(config)
}

async function doRefresh(): Promise<TokenPair> {
  const refreshToken = getRefreshToken()
  if (!refreshToken) {
    throw new ApiError(401, '没有可用的刷新令牌')
  }
  const response = await refreshClient.post<ApiEnvelope<TokenPair>>('/auth/refresh', { refreshToken })
  const body = response.data
  if (!body || body.code !== CODE_OK || !body.data) {
    throw new ApiError(body?.code ?? 401, body?.message ?? '刷新登录状态失败')
  }
  const pair: TokenPair = { token: body.data.token, refreshToken: body.data.refreshToken }
  // 同一时刻覆盖写入一对,禁止出现"新 token + 旧 refreshToken"(4.1)
  setTokens(pair)
  return pair
}

function failAuth(error: unknown): Promise<never> {
  clearTokens()
  onAuthFailed?.()
  if (error instanceof Error) {
    return Promise.reject(error)
  }
  return Promise.reject(new Error('登录状态已失效'))
}

/**
 * 业务代码统一用这个门面:返回值已经拆过包,类型参数就是 `data` 的类型(3.1)。
 *
 * 拆包放在这里而不是响应拦截器里,是因为 axios 的拦截器签名要求返回 `AxiosResponse` ——
 * 在那里返回 `data` 只能靠类型断言绕过检查,一旦 axios 升级或行为变化,断言不会报错、运行期才炸。
 */
export const request = {
  async get<T>(url: string, config?: AppRequestConfig): Promise<T> {
    return unwrap(await http.get(url, config)) as T
  },
  async post<T>(url: string, data?: unknown, config?: AppRequestConfig): Promise<T> {
    return unwrap(await http.post(url, data, config)) as T
  },
  async put<T>(url: string, data?: unknown, config?: AppRequestConfig): Promise<T> {
    return unwrap(await http.put(url, data, config)) as T
  },
  async delete<T>(url: string, config?: AppRequestConfig): Promise<T> {
    return unwrap(await http.delete(url, config)) as T
  },
}
