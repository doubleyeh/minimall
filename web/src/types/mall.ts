import type { Id } from '@/types/api'

/**
 * 商城(后端 /mall/admin/**)的类型定义。
 *
 * 字段与后端 DTO 一一对应,不要在这里"顺手"加字段:
 * 前端多一个后端不返回的字段,类型检查会全绿,但运行时永远是 undefined ——
 * 这种问题通常在联调后才被发现,而那时已经写了好几处依赖它的逻辑。
 */

// ---------------------------------------------------------------- 商品分类

export interface CategorySaveRequest {
  parentId?: Id | null
  categoryName: string
  icon?: string | null
  sortOrder?: number | null
  status?: number | null
}

export interface CategoryTreeNode {
  id: Id
  parentId: Id
  categoryName: string
  icon?: string | null
  sortOrder: number
  status: number
  children?: CategoryTreeNode[]
}

// ---------------------------------------------------------------- 商品与 SKU

export interface SpecSaveRequest {
  specName: string
  values: string[]
}

export interface SkuSaveRequest {
  /** 有值表示更新已有 SKU;为空表示新增。**不提交的旧 SKU 会被后端置为停售**(不是删除) */
  id?: Id | null
  skuCode: string
  skuName: string
  skuImage?: string | null
  price: number
  costPrice?: number | null
  stock: number
  weight?: number | null
  status?: number | null
  /** 该 SKU 的规格值组合(名称),必须能在 specs 里找到 */
  specValues?: string[]
}

export interface GoodsSaveRequest {
  /** 表单初始值可能为空,提交前由表单校验保证非空 */
  categoryId: Id | null
  goodsName: string
  goodsSubtitle?: string | null
  mainImage: string
  detailContent?: string | null
  freightTemplateId?: Id | null
  sortOrder?: number | null
  status?: number | null
  images?: string[]
  specs?: SpecSaveRequest[]
  skus: SkuSaveRequest[]
}

export interface GoodsView {
  id: Id
  categoryId: Id
  categoryName?: string | null
  goodsName: string
  goodsSubtitle?: string | null
  mainImage: string
  salePriceMin: number
  salePriceMax: number
  totalStock: number
  saleCount: number
  status: number
  sortOrder: number
}

export interface SkuView {
  id: Id
  skuCode: string
  skuName: string
  skuImage?: string | null
  price: number
  costPrice?: number | null
  stock: number
  lockedStock: number
  /** 可售库存 = stock - lockedStock(端上展示与下单校验都用它) */
  availableStock: number
  weight?: number | null
  status: number
  specValues?: string[]
}

export interface GoodsDetailView {
  id: Id
  categoryId: Id
  goodsName: string
  goodsSubtitle?: string | null
  mainImage: string
  detailContent?: string | null
  freightTemplateId?: Id | null
  salePriceMin: number
  salePriceMax: number
  totalStock: number
  saleCount: number
  status: number
  sortOrder: number
  images: string[]
  specs: Array<{ id: Id; specName: string; sortOrder: number; values: Array<{ id: Id; specValue: string; sortOrder: number }> }>
  skus: SkuView[]
}

// ---------------------------------------------------------------- 订单与售后

export interface AdminOrderView {
  id: Id
  orderNo: string
  customerId: Id
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
  items?: OrderItemView[] | null
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

export interface AfterSaleView {
  id: Id
  afterSaleNo: string
  orderId: Id
  orderNo?: string | null
  orderItemId: Id
  customerId: Id
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
  arbitrationRemark?: string | null
  createTime: string
  finishTime?: string | null
  images?: string[]
  item?: { goodsName: string; skuName: string; goodsImage: string; price: number; quantity: number } | null
  logs?: Array<{ fromStatus?: number | null; toStatus: number; operatorType: number; remark?: string | null; createTime: string }>
}

// ---------------------------------------------------------------- 营销与配置

export interface CouponSaveRequest {
  couponName: string
  couponType: number
  discountAmount?: number | null
  discountRate?: number | null
  minOrderAmount: number
  totalCount: number
  perCustomerLimit: number
  validStartTime: string
  validEndTime: string
  status?: number | null
}

export interface CouponView {
  id: Id
  couponName: string
  couponType: number
  discountAmount?: number | null
  discountRate?: number | null
  minOrderAmount: number
  totalCount: number
  receivedCount: number
  perCustomerLimit: number
  validStartTime: string
  validEndTime: string
  status: number
}

export interface PromotionSaveRequest {
  activityName: string
  reductionRule: string
  scopeType: number
  scopeIds?: Id[] | null
  validStartTime: string
  validEndTime: string
  status?: number | null
}

export interface PromotionView {
  id: Id
  activityName: string
  reductionRule: string
  scopeType: number
  scopeIds: Id[]
  validStartTime: string
  validEndTime: string
  status: number
}

export interface FreightRule {
  id?: Id
  region: string
  firstUnit: number
  firstFee: number
  additionalUnit: number
  additionalFee: number
  freeShippingAmount?: number | null
}

export interface FreightTemplateSaveRequest {
  templateName: string
  chargeType: number
  rules: FreightRule[]
}

export interface FreightTemplateView {
  id: Id
  templateName: string
  chargeType: number
  rules: FreightRule[]
}

export interface MemberLevelSaveRequest {
  levelName: string
  levelSort: number
  growthThreshold: number
  discountRate?: number | null
  status?: number | null
}

export interface MemberLevelView {
  id: Id
  levelName: string
  levelSort: number
  growthThreshold: number
  discountRate?: number | null
  status: number
}

export interface ReviewView {
  id: Id
  goodsId: Id
  goodsName?: string | null
  customerNickname?: string | null
  rating: number
  content?: string | null
  images?: string | null
  isAnonymous: number
  replyContent?: string | null
  replyTime?: string | null
  status: number
  createTime: string
}
