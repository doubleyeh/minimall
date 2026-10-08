/**
 * 后端要的 `LocalDateTime` 字符串:`yyyy-MM-ddTHH:mm:ss`,不带时区后缀。
 *
 * **不要用 `toISOString()`**:它按 UTC 输出,在东八区会把时间整体偏移 8 小时 ——
 * 表现是"筛选条件对不上"或"存进去的时间差了 8 小时",而且不容易一眼看出来。
 */
export function toLocalDateTime(timestamp: number): string {
  const date = new Date(timestamp)
  return `${toLocalDate(timestamp)}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

/**
 * 后端要的 `LocalDate` 字符串:`yyyy-MM-dd`。
 *
 * 同样不能用 `toISOString()`:它按 UTC 输出,东八区的凌晨会被算成前一天 ——
 * 用在"套餐生效日"上就是"选今天却写成昨天"。
 */
export function toLocalDate(timestamp: number): string {
  const date = new Date(timestamp)
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

function pad(value: number): string {
  return String(value).padStart(2, '0')
}
