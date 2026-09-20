import { categories, goodsPage } from '../../api/client'
import type { CategoryTreeNode, ClientGoodsView, Id } from '../../types/client'

/**
 * 商品卡片。
 *
 * 展示所需的字段**全部在这里算好**:WXML 里不能调用 JS 函数(只能绑字段),
 * 所以金额格式化、字段改名这类事必须在 JS 侧完成 —— 这也是与 Vue 模板最明显的差别。
 */
interface GoodsCard {
  id: Id
  name: string
  subtitle: string
  image: string
  priceText: string
  /** 有价格区间时显示"起",避免把最低价当成唯一价 */
  priceSuffix: string
  saleCountText: string
}

const PAGE_SIZE = 10

Page({
  data: {
    tabs: [] as Array<{ id: Id; label: string }>,
    activeCategoryId: '',
    cards: [] as GoodsCard[],
    pageNo: 1,
    loading: false,
    finished: false,
  },

  onLoad() {
    void this.loadCategories()
    void this.loadFirstPage()
  },

  onPullDownRefresh() {
    // 无论成功失败都要收回下拉态,否则转圈会一直挂着
    this.loadFirstPage().then(
      () => wx.stopPullDownRefresh(),
      () => wx.stopPullDownRefresh(),
    )
  },

  onReachBottom() {
    void this.loadMore()
  },

  onCategoryTap(e: WechatMiniprogram.TouchEvent) {
    const id = String(e.currentTarget.dataset.id || '')
    if (id === this.data.activeCategoryId) {
      return
    }
    this.setData({ activeCategoryId: id })
    void this.loadFirstPage()
  },

  onGoodsTap(e: WechatMiniprogram.TouchEvent) {
    const id = String(e.currentTarget.dataset.id || '')
    wx.navigateTo({ url: `/pages/goods/detail/index?id=${id}` })
  },

  onSearchTap() {
    // 搜索页还没实现。明确提示,而不是点了没反应 —— 静默无响应会被当成"卡了"
    wx.showToast({ title: '搜索即将开放', icon: 'none' })
  },

  async loadCategories() {
    try {
      const tree = await categories()
      const tabs = tree.map((node: CategoryTreeNode) => ({ id: node.id, label: node.categoryName }))
      this.setData({ tabs })
    } catch (err) {
      // 分类失败不阻塞商品列表:首页的主要信息是商品,不该因为一个筛选条整页报错
      console.warn('加载分类失败:', err)
    }
  },

  async loadFirstPage(): Promise<void> {
    this.setData({ finished: false })
    await this.loadPage(1, true)
  },

  async loadMore(): Promise<void> {
    await this.loadPage(this.data.pageNo + 1, false)
  },

  async loadPage(pageNo: number, replace: boolean): Promise<void> {
    if (this.data.loading) {
      return
    }
    this.setData({ loading: true })
    try {
      const result = await goodsPage({
        categoryId: this.data.activeCategoryId || null,
        pageNo,
        pageSize: PAGE_SIZE,
      })
      const cards = result.list.map(toCard)
      const merged = replace ? cards : this.data.cards.concat(cards)
      this.setData({
        cards: merged,
        pageNo,
        // 用累计条数与总数比较,而不是"这一页没满就结束":
        // 后端按可售过滤时,某页返回不足 PAGE_SIZE 并不代表没有下一页
        finished: merged.length >= result.total,
      })
    } catch (err) {
      // 失败时不推进页码,避免漏掉一页数据(下次触底会重试同一页)
      wx.showToast({ title: err instanceof Error ? err.message : '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },
})

/**
 * 后端 DTO → 卡片视图模型。
 *
 * 金额固定两位小数:否则同一个列表里会出现 `9.9` 与 `9.90` 两种写法。
 */
function toCard(item: ClientGoodsView): GoodsCard {
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
