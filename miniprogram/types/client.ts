/**
 * 小程序端类型定义(与后端 /mall/api 的 DTO 对应)。
 *
 * Id 一律是字符串:雪花 ID 超出 JS 安全整数,后端 Long 能直接解析字符串(与管理端同一约定)。
 *
 * 本文件从 H5 联调版(client/src/types/client.ts)整体照搬 —— 它只依赖后端契约,
 * 不含任何运行时相关代码,所以换端不需要改动。
 */

export type Id = string

export interface PageResult<T> {
  total: number
  list: T[]
}

export interface CategoryTreeNode {
  id: Id
  parentId: Id
  categoryName: string
  icon?: string | null
  children?: CategoryTreeNode[]
}

export interface ClientGoodsView {
  id: Id
  goodsName: string
  goodsSubtitle?: string | null
  mainImage: string
  salePriceMin: number
  salePriceMax: number
  saleCount: number
}

export interface ClientSkuView {
  id: Id
  skuName: string
  skuImage?: string | null
  price: number
  /** 可售库存 = stock - lockedStock,端上展示与下单校验都用它 */
  availableStock: number
  specValues: string[]
}

export interface ClientGoodsDetailView {
  id: Id
  goodsName: string
  goodsSubtitle?: string | null
  mainImage: string
  detailContent?: string | null
  salePriceMin: number
  salePriceMax: number
  totalStock: number
  saleCount: number
  images: string[]
  specs: Array<{ specName: string; values: string[] }>
  skus: ClientSkuView[]
}

export interface CartItemView {
  id: Id
  skuId: Id
  goodsId: Id
  goodsName?: string | null
  skuName?: string | null
  image?: string | null
  price?: number | null
  availableStock: number
  quantity: number
  selected: number
  /** false 表示商品已下架或售罄,结算时会跳过 */
  valid: boolean
}

export interface AddressView {
  id: Id
  receiverName: string
  receiverPhone: string
  province: string
  city: string
  district: string
  detailAddress: string
  isDefault: number
}

export interface AddressSaveRequest {
  receiverName: string
  receiverPhone: string
  province: string
  city: string
  district: string
  detailAddress: string
  defaultAddress?: boolean
}

export interface OrderItemView {
  id: Id
  skuId: Id
  goodsId: Id
  goodsName: string
  skuName: string
  goodsImage: string
  price: number
  quantity: number
  totalAmount: number
  afterSaleStatus: number
}

export interface ClientOrderView {
  id: Id
  orderNo: string
  status: number
  goodsAmount: number
  freightAmount: number
  promotionDiscountAmount: number
  couponDiscountAmount: number
  payAmount: number
  receiverName: string
  receiverPhone: string
  receiverAddress: string
  remark?: string | null
  logisticsCompany?: string | null
  logisticsNo?: string | null
  closeReason?: number | null
  createTime: string
  payTime?: string | null
  shipTime?: string | null
  receiveTime?: string | null
  finishTime?: string | null
  items: OrderItemView[]
}

export interface OrderCreateResponse {
  orderId: Id
  orderNo: string
  goodsAmount: number
  freightAmount: number
  promotionDiscountAmount: number
  couponDiscountAmount: number
  payAmount: number
  payParams?: {
    timeStamp: string
    nonceStr: string
    packageValue: string
    signType: string
    paySign: string
  } | null
}

export interface ClientCouponView {
  couponId: Id
  recordId?: Id | null
  couponName: string
  couponType: number
  discountAmount?: number | null
  discountRate?: number | null
  minOrderAmount: number
  validEndTime: string
  status?: number | null
  statusText?: string | null
}

export interface AfterSaleView {
  id: Id
  afterSaleNo: string
  orderId: Id
  orderNo?: string | null
  orderItemId: Id
  afterSaleType: number
  status: number
  statusText: string
  applyReason: string
  applyDesc?: string | null
  refundAmount: number
  rejectReason?: string | null
  returnLogisticsCompany?: string | null
  returnLogisticsNo?: string | null
  reshipLogisticsCompany?: string | null
  reshipLogisticsNo?: string | null
  createTime: string
  finishTime?: string | null
  images?: string[]
  item?: { goodsName: string; skuName: string; goodsImage: string; price: number; quantity: number } | null
}

export interface ClientProfileView {
  customerId: Id
  nickname?: string | null
  avatarUrl?: string | null
  phone?: string | null
  gender?: number | null
  points: number
  growthValue: number
  orderCounts: {
    pendingPay: number
    pendingShip: number
    pendingReceive: number
    finished: number
  }
}

export interface ClientLoginResponse {
  token: string
  expiresIn: number
  isNew: boolean
  customerId: Id
  nickname?: string | null
  avatarUrl?: string | null
}

export interface ReviewView {
  id: Id
  goodsId: Id
  goodsName: string
  /** 匿名评价时后端已做脱敏处理,直接展示即可 */
  customerNickname?: string | null
  rating: number
  content?: string | null
  /**
   * 图片地址,**JSON 数组字符串**(形如 ["https://a.png","https://b.png"])。
   * 后端为了不给几张图单独建表就存成了字符串,展示前必须先 JSON.parse ——
   * 按逗号切分会把方括号和引号一起渲染出来。
   */
  images?: string | null
  isAnonymous: number
  replyContent?: string | null
  replyTime?: string | null
  status: number
  createTime: string
}

export interface ReviewCreateRequest {
  orderItemId: Id
  /** 1-5 星,后端会校验范围 */
  rating: number
  content?: string | null
  images?: string[]
  anonymous?: boolean
}
