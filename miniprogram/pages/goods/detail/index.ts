import { cartAdd, goodsDetail, goodsReviews } from '../../../api/client'
import type { ClientSkuView, Id, ReviewView } from '../../../types/client'
import { money, stars, dateTime } from '../../../utils/format'
import { toastError, toastOk, toast } from '../../../utils/ui'

/** 规格组。WXML 里不能调函数,所以规格名与候选值都预先摆好。 */
interface SpecGroup {
  name: string
  values: string[]
}

/** 评价条目:昵称、时间、星级都算好。 */
interface ReviewItem {
  id: Id
  nicknameText: string
  starsText: string
  content: string
  replyContent: string
  createTimeText: string
}

Page({
  data: {
    goodsId: '',
    loadFailed: '',
    images: [] as string[],
    goodsName: '',
    goodsSubtitle: '',
    saleCountText: '0',
    priceText: '0.00',
    priceSuffix: '',
    specGroups: [] as SpecGroup[],
    /** 与 specGroups 下标对齐:selected[0] 是第一个规格选中的值 */
    selected: [] as string[],
    quantity: 1,
    matchedSkuId: '',
    matchedStock: 0,
    detailHtml: '',
    reviews: [] as ReviewItem[],
    reviewTotal: 0,
  },

  /** 商品详情,用于本地匹配 SKU;不放进 data(它会整包发给渲染层,没必要) */
  skus: [] as ClientSkuView[],
  priceMin: 0,
  priceMax: 0,

  onLoad(query: Record<string, string | undefined>) {
    const goodsId = query.id || ''
    if (!goodsId) {
      this.setData({ loadFailed: '缺少商品参数' })
      return
    }
    this.setData({ goodsId })
    void this.loadGoods()
    void this.loadReviews()
  },

  async loadGoods() {
    try {
      const detail = await goodsDetail(this.data.goodsId)
      this.skus = detail.skus || []
      this.priceMin = Number(detail.salePriceMin)
      this.priceMax = Number(detail.salePriceMax)

      // 主图与轮播图可能都为空,兜底成一张空图:否则 <swiper> 里没有任何 swiper-item,会占一块空白
      const images = detail.images && detail.images.length > 0
        ? detail.images
        : detail.mainImage
          ? [detail.mainImage]
          : ['']

      // 规格与候选值:同一规格名下的值去重后保持原顺序
      const specGroups: SpecGroup[] = (detail.specs || []).map((spec) => {
        const values: string[] = []
        ;(spec.values || []).forEach((value) => {
          if (values.indexOf(value) < 0) {
            values.push(value)
          }
        })
        return { name: spec.specName, values }
      })

      this.setData({
        images,
        goodsName: detail.goodsName,
        goodsSubtitle: detail.goodsSubtitle || '',
        saleCountText: String(detail.saleCount || 0),
        detailHtml: detail.detailContent || '',
        specGroups,
        // 只有一个 SKU 时直接替用户选好:让用户为一个选择唯一选项的规格点一下没有意义
        selected: specGroups.length > 0 && this.skus.length === 1 ? this.skus[0].specValues.slice() : [],
        quantity: 1,
      })
      this.refreshPriceAndStock()
    } catch (err) {
      this.setData({ loadFailed: err instanceof Error ? err.message : '商品加载失败' })
    }
  },

  async loadReviews() {
    try {
      const result = await goodsReviews(this.data.goodsId, 1, 5)
      const reviews = (result.list || []).map((item: ReviewView) => ({
        id: item.id,
        nicknameText: item.customerNickname || '匿名用户',
        starsText: stars(item.rating),
        content: item.content || '',
        replyContent: item.replyContent || '',
        createTimeText: dateTime(item.createTime),
      }))
      this.setData({ reviews, reviewTotal: result.total || 0 })
    } catch (err) {
      // 评价加载失败不影响购买:它只是详情页的一段附加信息
      console.warn('加载评价失败:', err)
    }
  },

  onValueTap(e: WechatMiniprogram.TouchEvent) {
    const groupIndex = Number(e.currentTarget.dataset.gi)
    const value = String(e.currentTarget.dataset.value || '')
    const selected = this.data.selected.slice()
    // 再点一次已选中的值 = 取消选择。规格可以重选,不该强迫用户只能换不能撤
    selected[groupIndex] = selected[groupIndex] === value ? '' : value
    this.setData({ selected, quantity: 1 })
    this.refreshPriceAndStock()
  },

  onMinus() {
    if (this.data.quantity <= 1) {
      return
    }
    this.setData({ quantity: this.data.quantity - 1 })
  },

  onPlus() {
    const limit = this.data.matchedSkuId ? this.data.matchedStock : 0
    if (this.data.matchedSkuId && this.data.quantity >= limit) {
      toast(`最多可买 ${limit} 件`)
      return
    }
    this.setData({ quantity: this.data.quantity + 1 })
  },

  /**
   * 重新计算价格与库存。
   *
   * 规格没选全时**不显示某一个 SKU 的价格**,而是显示商品的价格区间 ——
   * 否则用户会以为"看到的就是我要买的那个价",选了规格才发现不一样。
   */
  refreshPriceAndStock() {
    const chosen = this.data.selected.filter((value) => !!value)
    const specCount = this.data.specGroups.length
    let matched: ClientSkuView | null = null

    if (specCount > 0 && chosen.length === specCount) {
      matched = this.skus.find((sku) => {
        const values = sku.specValues || []
        // 逐值比对:SKU 的规格值集合与所选集合完全一致才算命中
        return values.length === chosen.length && chosen.every((value) => values.indexOf(value) >= 0)
      }) || null
    } else if (specCount === 0 && this.skus.length === 1) {
      matched = this.skus[0]
    }

    if (matched) {
      this.setData({
        matchedSkuId: matched.id,
        matchedStock: matched.availableStock,
        priceText: money(matched.price),
        priceSuffix: '',
      })
      return
    }

    this.setData({
      matchedSkuId: '',
      matchedStock: 0,
      priceText: money(this.priceMin),
      priceSuffix: this.priceMax > this.priceMin ? '起' : '',
    })
  },

  /** 加购与下单都要求先选中一个真实存在的 SKU —— 把判断集中在这里,两处调用不会走岔 */
  requireSku(): ClientSkuView | null {
    if (!this.data.matchedSkuId) {
      toast('请选择商品规格')
      return null
    }
    const sku = this.skus.find((item) => item.id === this.data.matchedSkuId) || null
    if (!sku) {
      toast('该规格已下架,请重新选择')
      return null
    }
    if (sku.availableStock < this.data.quantity) {
      toast(`库存不足,仅剩 ${sku.availableStock} 件`)
      return null
    }
    return sku
  },

  async onAddCart() {
    const sku = this.requireSku()
    if (!sku) {
      return
    }
    try {
      await cartAdd(sku.id, this.data.quantity)
      toastOk('已加入购物车')
    } catch (err) {
      toastError(err)
    }
  },

  onBuyNow() {
    const sku = this.requireSku()
    if (!sku) {
      return
    }
    const url = `/pages/checkout/index?skuId=${sku.id}&quantity=${this.data.quantity}`
    wx.navigateTo({ url })
  },

  /** 预览轮播大图 */
  onImageTap(e: WechatMiniprogram.TouchEvent) {
    const current = String(e.currentTarget.dataset.src || '')
    const urls = this.data.images.filter((item) => !!item)
    if (urls.length === 0) {
      return
    }
    wx.previewImage({ current, urls })
  },
})
