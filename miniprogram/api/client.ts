import type {
  AddressSaveRequest,
  AddressView,
  AfterSaleView,
  CartItemView,
  CategoryTreeNode,
  ClientCouponView,
  ClientGoodsDetailView,
  ClientGoodsView,
  ClientOrderView,
  ClientProfileView,
  Id,
  OrderCreateResponse,
  PageResult,
  ReviewCreateRequest,
  ReviewView,
} from '../types/client'
import { IS_RELEASE } from '../utils/env'
import { buildQuery } from '../utils/query'
import { request } from '../utils/request'

/**
 * 小程序端接口(/mall/api/**)。
 *
 * 与后端的三个约定:
 * - 登录返回 30 天令牌,过期后后端返回 401,由 request.ts 静默重登并重试(3.1);
 * - 领券/下单这类"改变状态"的接口都需要令牌;
 * - 所有请求都带 X-Tenant-Code(在 request.ts 里统一加),后端据此确定是哪个商家。
 *
 * 相对 H5 联调版的两处改写:
 * 1. 查询串改用 {@link buildQuery},不再用 `URLSearchParams`(小程序运行时没有这个构造函数);
 * 2. 登录不在这里 —— 它由 `utils/auth.ts` 直接发,因为请求层要在 401 时回调它,互相 import 会成环。
 */

// ---------------------------------------------------------------- 商品浏览

export function categories(): Promise<CategoryTreeNode[]> {
  return request.get<CategoryTreeNode[]>('/mall/api/categories')
}

export function goodsPage(query: {
  categoryId?: Id | null
  keyword?: string
  pageNo?: number
  pageSize?: number
}): Promise<PageResult<ClientGoodsView>> {
  const qs = buildQuery({
    categoryId: query.categoryId,
    keyword: query.keyword,
    pageNo: query.pageNo ?? 1,
    pageSize: query.pageSize ?? 10,
  })
  return request.get<PageResult<ClientGoodsView>>(`/mall/api/goods?${qs}`)
}

export function goodsDetail(goodsId: Id): Promise<ClientGoodsDetailView> {
  return request.get<ClientGoodsDetailView>(`/mall/api/goods/${goodsId}`)
}

// ---------------------------------------------------------------- 购物车

export function cartList(): Promise<CartItemView[]> {
  return request.get<CartItemView[]>('/mall/api/cart')
}

export function cartAdd(skuId: Id, quantity: number): Promise<Id> {
  return request.post<Id>('/mall/api/cart', { skuId, quantity })
}

export function cartUpdate(cartId: Id, data: { quantity?: number; selected?: number }): Promise<void> {
  return request.put<void>(`/mall/api/cart/${cartId}`, data)
}

/**
 * 删除若干条目。
 *
 * 请求体是 id 数组,所以必须带 `Content-Type: application/json` —— 见 request.ts 里的说明:
 * `wx.request` 对对象/数组类型的 data 默认按 form 表单序列化,会把数组拍成 `0=..&1=..`。
 */
export function cartRemove(cartIds: Id[]): Promise<void> {
  return request.delete<void>('/mall/api/cart', cartIds)
}

/**
 * 清空购物车。
 *
 * 与"删除若干条目"是两个接口:`DELETE /mall/api/cart` 的请求体是要删的 id 列表,
 * 而这个是 `DELETE /mall/api/cart/all`,不接受参数 —— 想清空时别去拼一个"全部 id"的列表,
 * 客户端手里的列表可能已经过期(别的端删过、或分页没取全)。
 */
export function cartClear(): Promise<void> {
  return request.delete<void>('/mall/api/cart/all')
}

// ---------------------------------------------------------------- 收货地址

export function addressList(): Promise<AddressView[]> {
  return request.get<AddressView[]>('/mall/api/addresses')
}

export function addressCreate(data: AddressSaveRequest): Promise<Id> {
  return request.post<Id>('/mall/api/addresses', data)
}

export function addressUpdate(addressId: Id, data: AddressSaveRequest): Promise<void> {
  return request.put<void>(`/mall/api/addresses/${addressId}`, data)
}

export function addressDelete(addressId: Id): Promise<void> {
  return request.delete<void>(`/mall/api/addresses/${addressId}`)
}

export function addressSetDefault(addressId: Id): Promise<void> {
  return request.put<void>(`/mall/api/addresses/${addressId}/default`)
}

// ---------------------------------------------------------------- 订单

export function createOrder(data: {
  items?: Array<{ skuId: Id; quantity: number }>
  addressId: Id
  couponRecordId?: Id | null
  remark?: string
}): Promise<OrderCreateResponse> {
  return request.post<OrderCreateResponse>('/mall/api/orders', data)
}

export function orderList(
  status?: number | null,
  pageNo = 1,
  pageSize = 10,
): Promise<PageResult<ClientOrderView>> {
  const qs = buildQuery({ status, pageNo, pageSize })
  return request.get<PageResult<ClientOrderView>>(`/mall/api/orders?${qs}`)
}

export function orderDetail(orderId: Id): Promise<ClientOrderView> {
  return request.get<ClientOrderView>(`/mall/api/orders/${orderId}`)
}

export function orderCancel(orderId: Id): Promise<void> {
  return request.post<void>(`/mall/api/orders/${orderId}/cancel`)
}

export function orderReceive(orderId: Id): Promise<void> {
  return request.post<void>(`/mall/api/orders/${orderId}/receive`)
}

/**
 * 拉起支付:拿到参数后交给 `wx.requestPayment`(见 `utils/pay.ts`)。
 *
 * 注意字段名:后端返回 `packageValue`,而 `wx.requestPayment` 要的是 `package`。
 */
export interface WxPayParams {
  timeStamp: string
  nonceStr: string
  packageValue: string
  signType: string
  paySign: string
}

export function orderPrepay(orderId: Id): Promise<WxPayParams> {
  return request.post<WxPayParams>(`/mall/api/orders/${orderId}/prepay`)
}

/**
 * **仅本地联调**:模拟"微信支付成功"的回调,把订单推进到"待发货"。
 *
 * 真实环境下这个回调由**微信服务器**发起,客户端没有任何理由调用它。
 * 所以这里加了发布守卫:正式/体验版直接抛错,避免它被打进线上包 ——
 * 不校验来源的回调接口等于任何人都能"通知"你把订单置为已支付(见 WxPayClient 的说明)。
 *
 * H5 联调版没有这道守卫,谁都能在浏览器控制台里调一下把自己的订单标成已付。
 */
export function mockPaySuccess(outTradeNo: string, amount: number, transactionId?: string): Promise<void> {
  if (IS_RELEASE) {
    return Promise.reject(new Error('模拟支付回调仅限开发环境使用'))
  }
  return request.post<void>('/pay/callback/wx', {
    outTradeNo,
    transactionId: transactionId ?? `mock-${Date.now()}`,
    amount,
    success: true,
    rawBody: '{"mock":true}',
  })
}

// ---------------------------------------------------------------- 优惠券

export function claimableCoupons(): Promise<ClientCouponView[]> {
  return request.get<ClientCouponView[]>('/mall/api/coupons/claimable')
}

export function myCoupons(status?: number | null): Promise<ClientCouponView[]> {
  const qs = buildQuery({ status })
  return request.get<ClientCouponView[]>(`/mall/api/coupons/mine${qs ? `?${qs}` : ''}`)
}

export function claimCoupon(couponId: Id): Promise<Id> {
  return request.post<Id>(`/mall/api/coupons/${couponId}/claim`)
}

// ---------------------------------------------------------------- 售后

export function afterSaleApply(data: {
  orderItemId: Id
  afterSaleType: number
  applyReason: string
  applyDesc?: string
  refundAmount: number
  images?: string[]
}): Promise<Id> {
  return request.post<Id>('/mall/api/after-sales', data)
}

export function afterSaleMine(status?: number | null): Promise<AfterSaleView[]> {
  const qs = buildQuery({ status })
  return request.get<AfterSaleView[]>(`/mall/api/after-sales${qs ? `?${qs}` : ''}`)
}

export function afterSaleDetail(afterSaleId: Id): Promise<AfterSaleView> {
  return request.get<AfterSaleView>(`/mall/api/after-sales/${afterSaleId}`)
}

export function afterSaleCancel(afterSaleId: Id): Promise<void> {
  return request.post<void>(`/mall/api/after-sales/${afterSaleId}/cancel`)
}

export function afterSaleReturnLogistics(afterSaleId: Id, company: string, no: string): Promise<void> {
  return request.post<void>(`/mall/api/after-sales/${afterSaleId}/return-logistics`, { company, no })
}

export function afterSaleArbitration(afterSaleId: Id): Promise<void> {
  return request.post<void>(`/mall/api/after-sales/${afterSaleId}/arbitration`)
}

// ---------------------------------------------------------------- 个人中心

export function profile(): Promise<ClientProfileView> {
  return request.get<ClientProfileView>('/mall/api/profile')
}

export function updateProfile(data: { nickname?: string; avatarUrl?: string; gender?: number }): Promise<void> {
  return request.put<void>('/mall/api/profile', data)
}

// ---------------------------------------------------------------- 商品评价

/**
 * 某商品的评价列表。**公开接口**(游客也能看):商品页一直没有评价会显得"没人买过"。
 *
 * 这个接口曾被漏在小程序端公开路径白名单之外,导致未登录用户打不开评价区
 * (修复见后端提交 fix(mall): 商品评价列表漏在小程序端的公开路径白名单外)。
 */
export function goodsReviews(goodsId: Id, pageNo = 1, pageSize = 5): Promise<PageResult<ReviewView>> {
  const qs = buildQuery({ pageNo, pageSize })
  return request.get<PageResult<ReviewView>>(`/mall/api/reviews/goods/${goodsId}?${qs}`)
}

/**
 * 提交评价(需要令牌)。
 *
 * 两条后端规则要在端上体现出来,否则用户只会看到一个"失败":
 * ①订单必须是"已完成"状态;②同一订单明细只能评价一次。
 */
export function createReview(data: ReviewCreateRequest): Promise<Id> {
  return request.post<Id>('/mall/api/reviews', data)
}
