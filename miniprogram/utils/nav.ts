/**
 * 页面间的一次性传递,给**tab 页**用。
 *
 * 为什么需要它:tabBar 里的页面只能用 `wx.switchTab` 打开,而 `switchTab` **不接受 query 参数**
 * (传了也会被忽略)。所以"我的"页点"待付款 3"想跳到订单 tab 并切到待支付,
 * 没法像普通页面那样 `navigateTo('/pages/orders/index?status=1')`。
 *
 * 用一个模块级的槽位传递:设的人写一次,读的人取一次并清空 ——
 * 清空是必要的,否则用户下次从别处切到订单 tab 时,会莫名其妙停在"待支付"。
 *
 * 不用 globalData 是因为它没有类型:这里的每一项都要能被 `tsc` 检查
 * (写错状态值、忘了清空,都应该在编译期或读代码时看出来)。
 */

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
