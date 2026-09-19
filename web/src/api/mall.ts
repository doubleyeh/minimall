import type { Id, PageResult } from '@/types/api'
import type {
  AdminOrderView,
  AfterSaleView,
  CategorySaveRequest,
  CategoryTreeNode,
  CouponSaveRequest,
  CouponView,
  FreightTemplateSaveRequest,
  FreightTemplateView,
  GoodsDetailView,
  GoodsSaveRequest,
  GoodsView,
  MemberLevelSaveRequest,
  MemberLevelView,
  PromotionSaveRequest,
  PromotionView,
  ReviewView,
} from '@/types/mall'
import { request } from '@/utils/request'

/**
 * 商城管理端接口(后端 /mall/admin/**)。
 *
 * 权限码与后端一一对应(见 V4 迁移里的 sys_menu),形如 mall:goods:list、mall:order:ship。
 * 页面上用 v-perm 或 permission.hasPerm 控制按钮显隐。
 *
 * 注意:写注释时不要在块注释里出现"冒号 + 星号 + 斜杠"的连续字符,那会提前结束注释 ——
 * 这里原先就是这样写权限码前缀,导致整个文件语法错误。
 */

// ---------------------------------------------------------------- 商品分类

export function categoryTree(status?: number): Promise<CategoryTreeNode[]> {
  return request.get<CategoryTreeNode[]>('/mall/admin/categories/tree', { params: { status } })
}

export function createCategory(data: CategorySaveRequest): Promise<Id> {
  return request.post<Id>('/mall/admin/categories', data)
}

export function updateCategory(categoryId: Id, data: CategorySaveRequest): Promise<void> {
  return request.put<void>(`/mall/admin/categories/${categoryId}`, data)
}

export function deleteCategory(categoryId: Id): Promise<void> {
  return request.delete<void>(`/mall/admin/categories/${categoryId}`)
}

// ---------------------------------------------------------------- 商品与 SKU

export interface GoodsPageQuery {
  goodsName?: string
  categoryId?: Id | null
  status?: number | null
  pageNo?: number
  pageSize?: number
}

export function pageGoods(query: GoodsPageQuery): Promise<PageResult<GoodsView>> {
  return request.get<PageResult<GoodsView>>('/mall/admin/goods', { params: query })
}

export function getGoods(goodsId: Id): Promise<GoodsDetailView> {
  return request.get<GoodsDetailView>(`/mall/admin/goods/${goodsId}`)
}

export function createGoods(data: GoodsSaveRequest): Promise<Id> {
  return request.post<Id>('/mall/admin/goods', data)
}

export function updateGoods(goodsId: Id, data: GoodsSaveRequest): Promise<void> {
  return request.put<void>(`/mall/admin/goods/${goodsId}`, data)
}

/** 上架/下架。下架不会删除 SKU 与历史订单关联。 */
export function changeGoodsStatus(goodsId: Id, status: number): Promise<void> {
  return request.put<void>(`/mall/admin/goods/${goodsId}/status`, undefined, { params: { status } })
}

export function deleteGoods(goodsId: Id): Promise<void> {
  return request.delete<void>(`/mall/admin/goods/${goodsId}`)
}

// ---------------------------------------------------------------- 订单

export interface OrderPageQuery {
  orderNo?: string
  status?: number | null
  pageNo?: number
  pageSize?: number
}

export function pageOrders(query: OrderPageQuery): Promise<PageResult<AdminOrderView>> {
  return request.get<PageResult<AdminOrderView>>('/mall/admin/orders', { params: query })
}

export function getOrder(orderId: Id): Promise<AdminOrderView> {
  return request.get<AdminOrderView>(`/mall/admin/orders/${orderId}`)
}

export function shipOrder(orderId: Id, data: { logisticsCompany: string; logisticsNo: string }): Promise<void> {
  return request.post<void>(`/mall/admin/orders/${orderId}/ship`, data)
}

/** 商家取消(仅待发货):后端会同时回补库存并创建退款记录。 */
export function cancelOrder(orderId: Id, reason?: string): Promise<void> {
  return request.post<void>(`/mall/admin/orders/${orderId}/cancel`, { reason })
}

// ---------------------------------------------------------------- 售后

export interface AfterSalePageQuery {
  status?: number | null
  afterSaleNo?: string
  pageNo?: number
  pageSize?: number
}

export function pageAfterSales(query: AfterSalePageQuery): Promise<PageResult<AfterSaleView>> {
  return request.get<PageResult<AfterSaleView>>('/mall/admin/after-sales', { params: query })
}

export function getAfterSale(afterSaleId: Id): Promise<AfterSaleView> {
  return request.get<AfterSaleView>(`/mall/admin/after-sales/${afterSaleId}`)
}

/** 同意。refundAmount 传值表示下调退款金额(只能下调,后端会校验并留痕)。 */
export function approveAfterSale(afterSaleId: Id, refundAmount?: number | null): Promise<void> {
  return request.post<void>(`/mall/admin/after-sales/${afterSaleId}/approve`, { refundAmount })
}

export function rejectAfterSale(afterSaleId: Id, reason: string): Promise<void> {
  return request.post<void>(`/mall/admin/after-sales/${afterSaleId}/reject`, { reason })
}

/** 确认收到退货。换货场景需要传新的 SKU 与重新发货的物流。 */
export function confirmReceiveAfterSale(
  afterSaleId: Id,
  data: { newSkuId?: Id | null; logisticsCompany?: string | null; logisticsNo?: string | null },
): Promise<void> {
  return request.post<void>(`/mall/admin/after-sales/${afterSaleId}/confirm-receive`, data)
}

export function rejectReceiveAfterSale(afterSaleId: Id, reason: string): Promise<void> {
  return request.post<void>(`/mall/admin/after-sales/${afterSaleId}/reject-receive`, { reason })
}

export function arbitrateAfterSale(afterSaleId: Id, pass: boolean, remark?: string): Promise<void> {
  return request.post<void>(`/mall/admin/after-sales/${afterSaleId}/arbitrate`, { pass, remark })
}

// ---------------------------------------------------------------- 优惠券

export interface CouponPageQuery {
  couponName?: string
  status?: number | null
  pageNo?: number
  pageSize?: number
}

export function pageCoupons(query: CouponPageQuery): Promise<PageResult<CouponView>> {
  return request.get<PageResult<CouponView>>('/mall/admin/coupons', { params: query })
}

export function createCoupon(data: CouponSaveRequest): Promise<Id> {
  return request.post<Id>('/mall/admin/coupons', data)
}

export function updateCoupon(couponId: Id, data: CouponSaveRequest): Promise<void> {
  return request.put<void>(`/mall/admin/coupons/${couponId}`, data)
}

export function changeCouponStatus(couponId: Id, status: number): Promise<void> {
  return request.put<void>(`/mall/admin/coupons/${couponId}/status`, undefined, { params: { status } })
}

// ---------------------------------------------------------------- 满减活动

export interface PromotionPageQuery {
  activityName?: string
  status?: number | null
  pageNo?: number
  pageSize?: number
}

export function pagePromotions(query: PromotionPageQuery): Promise<PageResult<PromotionView>> {
  return request.get<PageResult<PromotionView>>('/mall/admin/promotions', { params: query })
}

export function createPromotion(data: PromotionSaveRequest): Promise<Id> {
  return request.post<Id>('/mall/admin/promotions', data)
}

export function updatePromotion(activityId: Id, data: PromotionSaveRequest): Promise<void> {
  return request.put<void>(`/mall/admin/promotions/${activityId}`, data)
}

export function changePromotionStatus(activityId: Id, status: number): Promise<void> {
  return request.put<void>(`/mall/admin/promotions/${activityId}/status`, undefined, { params: { status } })
}

// ---------------------------------------------------------------- 运费模板

export function listFreightTemplates(): Promise<FreightTemplateView[]> {
  return request.get<FreightTemplateView[]>('/mall/admin/freight-templates')
}

export function getFreightTemplate(templateId: Id): Promise<FreightTemplateView> {
  return request.get<FreightTemplateView>(`/mall/admin/freight-templates/${templateId}`)
}

export function createFreightTemplate(data: FreightTemplateSaveRequest): Promise<Id> {
  return request.post<Id>('/mall/admin/freight-templates', data)
}

export function updateFreightTemplate(templateId: Id, data: FreightTemplateSaveRequest): Promise<void> {
  return request.put<void>(`/mall/admin/freight-templates/${templateId}`, data)
}

export function deleteFreightTemplate(templateId: Id): Promise<void> {
  return request.delete<void>(`/mall/admin/freight-templates/${templateId}`)
}

// ---------------------------------------------------------------- 会员等级

export function listMemberLevels(): Promise<MemberLevelView[]> {
  return request.get<MemberLevelView[]>('/mall/admin/member-levels')
}

export function createMemberLevel(data: MemberLevelSaveRequest): Promise<Id> {
  return request.post<Id>('/mall/admin/member-levels', data)
}

export function updateMemberLevel(levelId: Id, data: MemberLevelSaveRequest): Promise<void> {
  return request.put<void>(`/mall/admin/member-levels/${levelId}`, data)
}

// ---------------------------------------------------------------- 商品评价

export interface ReviewPageQuery {
  goodsId?: Id | null
  status?: number | null
  pageNo?: number
  pageSize?: number
}

export function pageReviews(query: ReviewPageQuery): Promise<PageResult<ReviewView>> {
  return request.get<PageResult<ReviewView>>('/mall/admin/reviews', { params: query })
}

export function replyReview(reviewId: Id, content: string): Promise<void> {
  return request.put<void>(`/mall/admin/reviews/${reviewId}/reply`, { content })
}

export function changeReviewStatus(reviewId: Id, status: number): Promise<void> {
  return request.put<void>(`/mall/admin/reviews/${reviewId}/status`, undefined, { params: { status } })
}
