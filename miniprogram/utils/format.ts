/**
 * 展示格式化。
 *
 * 为什么单独一个模块:WXML 里**不能调用 JS 函数**(只能绑字段),所以「金额保留两位」
 * 「时间截到分钟」这类格式化必须在 JS 侧算好再塞进 data。于是每个页面各写一遍 ——
 * 集中在这里,至少保证同一个值在列表页与详情页长得一样。
 */

/** 金额:固定两位小数。否则同一屏里会出现 `9.9` 与 `9.90` 两种写法。 */
export function money(value: number | null | undefined): string {
  const n = typeof value === 'number' && isFinite(value) ? value : 0
  return n.toFixed(2)
}

/**
 * 时间:后端返回 `yyyy-MM-dd HH:mm:ss`,列表里只需要到分钟。
 *
 * 用字符串切片而不是 `new Date()`:iOS 对 `yyyy-MM-dd HH:mm:ss` 这种带空格的格式解析不稳,
 * 而切片在所有平台上行为一致。
 */
export function dateTime(value: string | null | undefined): string {
  if (!value) {
    return ''
  }
  return value.length >= 16 ? value.slice(0, 16) : value
}

/** 评分:实心 + 空心,长度固定为 5,列表里不会因为评分不同而左右错位。 */
export function stars(rating: number | null | undefined): string {
  const n = Math.max(0, Math.min(5, Math.round(rating || 0)))
  return '★'.repeat(n) + '☆'.repeat(5 - n)
}

/**
 * 订单状态文案。
 *
 * 取值与后端一致(mall_architecture.md 3.4 的状态机,与 MallOrder 的常量一一对应)——
 * 不要凭印象写:5 是"已取消"不是"已完成",6 是"售后中"。
 * 售后单不需要这个映射:接口直接返回 statusText。
 */
const ORDER_STATUS_TEXT: Record<number, string> = {
  1: '待支付',
  2: '待发货',
  3: '待收货',
  4: '已完成',
  5: '已取消',
  6: '售后中',
}

export function orderStatusText(status: number): string {
  return ORDER_STATUS_TEXT[status] || '未知状态'
}

/** 已取消订单的关闭原因(3.4 的 close_reason)。 */
const CLOSE_REASON_TEXT: Record<number, string> = {
  1: '超时未支付,系统自动关闭',
  2: '买家主动取消',
  3: '商家取消',
}

export function closeReasonText(reason: number | null | undefined): string {
  if (reason == null) {
    return ''
  }
  return CLOSE_REASON_TEXT[reason] || ''
}

/** 售后类型(与 MallAfterSale 的常量一致)。 */
export const AFTER_SALE_TYPE = {
  REFUND_ONLY: 1,
  RETURN_REFUND: 2,
  EXCHANGE: 3,
} as const

export function afterSaleTypeText(type: number): string {
  if (type === AFTER_SALE_TYPE.REFUND_ONLY) return '仅退款'
  if (type === AFTER_SALE_TYPE.RETURN_REFUND) return '退货退款'
  if (type === AFTER_SALE_TYPE.EXCHANGE) return '换货'
  return '未知类型'
}

/** 优惠券类型(与后端一致:1 满减、2 折扣)。 */
export function couponValueText(item: {
  couponType: number
  discountAmount?: number | null
  discountRate?: number | null
  minOrderAmount: number
}): string {
  const threshold = item.minOrderAmount > 0 ? `满 ¥${money(item.minOrderAmount)} 可用` : '无门槛'
  if (item.couponType === 2) {
    // 折扣率是 0-1 的小数,例如 0.85 表示打 8.5 折
    const rate = typeof item.discountRate === 'number' ? item.discountRate : 0
    return `${(rate * 10).toFixed(1)} 折 · ${threshold}`
  }
  return `减 ¥${money(item.discountAmount)} · ${threshold}`
}
