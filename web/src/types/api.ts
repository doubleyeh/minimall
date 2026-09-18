/**
 * 后端统一响应体(后端 architecture.md 7.3)。
 *
 * `code === 0` 表示成功。响应拦截器会把它拆开,业务代码拿到的直接是 `data`(见前端文档 3.1)。
 */
export interface ApiEnvelope<T> {
  code: number
  message: string
  data?: T
}

/**
 * 后端把所有 Long 都序列化成字符串(避免雪花 ID 超过 JS 安全整数,后端 architecture.md 4.3)。
 * 所以 id 类字段在前端一律是 `string`,不要写成 number —— 写成 number 会在类型上"看起来对",
 * 实际比较时 `'12' === 12` 永远为假。
 */
export type Id = string

/** 分页体(后端 7.3 统一分页格式)。 */
export interface PageResult<T> {
  total: number
  list: T[]
}
