import type {
  AddressSaveRequest,
  AddressView,
  AfterSaleView,
  CartItemView,
  CategoryTreeNode,
  ClientCouponView,
  ClientGoodsDetailView,
  ClientGoodsView,
  ClientLoginResponse,
  ClientOrderView,
  ClientProfileView,
  Id,
  OrderCreateResponse,
  PageResult,
} from '@/types/client'
import { request } from '@/utils/request'

/**
 * 小程序端接口(/mall/api/**)。
 *
 * 与后端的三个约定:
 * - 登录返回 30 天令牌,过期后后端返回 401,由 request.ts 统一跳登录(3.1);
 * - 领券/下单这类"改变状态"的接口都需要令牌;
 * - 所有请求都带 X-Tenant-Code(在 request.ts 里统一加),后端据此确定是哪个商家。
 */

// ---------------------------------------------------------------- 登录

/** 微信登录:code 由 wx.login() 取得;本地开发用后端 mock(输入任意 code 即可)。 */
export function wxLogin(code: string): Promise<ClientLoginResponse> {
  return request.post<ClientLoginResponse>('/mall/api/auth/wx-login', { code })
}

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
  const params = new URLSearchParams()
  if (query.categoryId) params.set('categoryId', query.categoryId)
  if (query.keyword) params.set('keyword', query.keyword)
  params.set('pageNo', String(query.pageNo ?? 1))
  params.set('pageSize', String(query.pageSize ?? 10))
  return request.get<PageResult<ClientGoodsView>>(`/mall/api/goods?${params.toString()}`)
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

export function cartRemove(cartIds: Id[]): Promise<void> {
  return request.delete<void>('/mall/api/cart', cartIds)
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

export function orderList(status?: number | null, pageNo = 1, pageSize = 10): Promise<PageResult<ClientOrderView>> {
  const params = new URLSearchParams()
  if (status != null) params.set('status', String(status))
  params.set('pageNo', String(pageNo))
  params.set('pageSize', String(pageSize))
  return request.get<PageResult<ClientOrderView>>(`/mall/api/orders?${params.toString()}`)
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

/** 拉起支付:真实小程序里拿到参数后调用 wx.requestPayment。 */
export function orderPrepay(orderId: Id): Promise<{
  timeStamp: string
  nonceStr: string
  packageValue: string
  signType: string
  paySign: string
}> {
  return request.post(`/mall/api/orders/${orderId}/prepay`)
}

/**
 * 本地联调用:模拟"微信支付成功"的回调。
 *
 * 真实环境下这个回调由微信服务器发起(H5 里没有任何东西能触发它),
 * 所以开发时用它在浏览器里把订单推进到"待发货" —— 否则支付之后的主流程
 * (发货、收货、售后)在本地根本走不到。
 */
export function mockPaySuccess(outTradeNo: string, amount: number, transactionId?: string): Promise<void> {
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
  const params = status == null ? '' : `?status=${status}`
  return request.get<ClientCouponView[]>(`/mall/api/coupons/mine${params}`)
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
  const params = status == null ? '' : `?status=${status}`
  return request.get<AfterSaleView[]>(`/mall/api/after-sales${params}`)
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
