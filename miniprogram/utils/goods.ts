import type { ClientGoodsView, Id } from '../types/client'

/**
 * 商品卡片视图模型:展示所需的字段全部在这里算好 —— WXML 不能调函数,只能绑字段。
 *
 * 首页与商品列表页共用同一份,避免两处各算一遍导致价格/销量显示不一致。
 */
export interface GoodsCard {
  id: Id
  name: string
  subtitle: string
  image: string
  priceText: string
  /** 有价格区间时显示"起",避免把最低价当成唯一价 */
  priceSuffix: string
  saleCountText: string
}

/** 后端 DTO → 卡片视图模型。金额固定两位小数,否则同一屏会出现 9.9 与 9.90 两种写法 */
export function toCard(item: ClientGoodsView): GoodsCard {
  const min = Number(item.salePriceMin)
  const max = Number(item.salePriceMax)
  return {
    id: item.id,
    name: item.goodsName,
    subtitle: item.goodsSubtitle || '',
    image: item.mainImage,
    priceText: min.toFixed(2),
    priceSuffix: max > min ? '起' : '',
    saleCountText: String(item.saleCount),
  }
}
