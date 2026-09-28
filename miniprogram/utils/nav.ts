/**
 * 页面间的一次性传递,给**不方便走 query 的场景**用。
 *
 * 两种场景:
 * 1. tabBar 页面只能用 `wx.switchTab` 打开,而它**不接受 query 参数**(传了也会被忽略)。
 *    所以"我的"页点"待付款 3"想跳到订单 tab 并切到待支付,没法 `navigateTo('...?status=1')`;
 * 2. 要传的是一个**对象**(结算页选中的收货地址),塞进 query 得自己拼分隔符,
 *    而地址里本就可能出现分隔符。
 *
 * 用一个模块级的槽位传递:设的人写一次,读的人取一次并清空 ——
 * 清空是必要的,否则用户下次进来会莫名其妙停在上一次的状态。
 *
 * 不用 globalData 是因为它没有类型:这里的每一项都要能被 `tsc` 检查
 * (写错状态值、忘了清空,都应该在编译期或读代码时看出来)。
 */

import type { AddressView } from '../types/client'

let pendingOrdersTab: number | null = null

/** 跳到订单 tab 前设置要打开的状态(1 待支付 / 2 待发货 / 3 待收货 / 4 已完成)。 */
export function setPendingOrdersTab(status: number): void {
  pendingOrdersTab = status
}

/** 订单页在 onShow 里调用。取走即清空,只生效一次。 */
export function takePendingOrdersTab(): number | null {
  const value = pendingOrdersTab
  pendingOrdersTab = null
  return value
}

/**
 * 地址页选好后回传给结算页。
 *
 * 用槽位而不是 query:地址是一个对象(收件人/电话/省市区/详址),塞进 query 要自己拼分隔符、
 * 还要处理地址里本就可能出现的分隔符。而结算页就在栈里等着,回传一个对象最简单。
 *
 * 与订单 tab 的槽位一样,**取走即清空**:不清的话用户下次进结算页会莫名选中上一次的那条。
 */
let pendingCheckoutAddress: AddressView | null = null

export function setPendingCheckoutAddress(address: AddressView): void {
  pendingCheckoutAddress = address
}

/** 结算页在 onShow 里调用。取走即清空,只生效一次。 */
export function takePendingCheckoutAddress(): AddressView | null {
  const value = pendingCheckoutAddress
  pendingCheckoutAddress = null
  return value
}
