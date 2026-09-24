/**
 * 后端要的 `LocalDateTime` 字符串:`yyyy-MM-ddTHH:mm:ss`,不带时区后缀。
 *
 * **不要用 `toISOString()`**:它按 UTC 输出,在东八区会把时间整体偏移 8 小时 ——
 * 表现是"筛选条件对不上"或"存进去的时间差了 8 小时",而且不容易一眼看出来。
 */
export function toLocalDateTime(timestamp: number): string {
  const date = new Date(timestamp)
  const pad = (value: number): string => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
    + `T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}
