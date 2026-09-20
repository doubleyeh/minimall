/**
 * 查询串拼接。
 *
 * **不能用 `URLSearchParams`** —— 它是浏览器的 Web API,小程序的 JS 运行时里没有这个构造函数。
 * H5 版可以随便用,换端时必须替换掉;而这类问题 `tsc` 与开发者工具的静态检查都发现不了
 * (类型声明来自 DOM lib,类型是对的,运行时才炸)。
 *
 * 两点约定:
 * 1. 值统一 `encodeURIComponent` —— 关键词里带 `&`、空格、中文都不该让请求串掉;
 * 2. **跳过 null/undefined/空串** —— 拼出 `status=undefined` 后端会当成非法参数报错,
 *    而不是"没有这个筛选条件"。
 */
export function buildQuery(params: Record<string, string | number | null | undefined>): string {
  const parts: string[] = []
  Object.keys(params).forEach((key) => {
    const value = params[key]
    if (value === null || value === undefined || value === '') {
      return
    }
    parts.push(`${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
  })
  return parts.join('&')
}
