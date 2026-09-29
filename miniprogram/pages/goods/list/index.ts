import { categories, goodsPage } from '../../../api/client'
import type { Id } from '../../../types/client'
import { toCard, type GoodsCard } from '../../../utils/goods'

const PAGE_SIZE = 10

/** 分类筛选的一格。"全部"用空 id 表示 —— 后端不传 categoryId 就是全部 */
interface CategoryChip {
  id: Id
  label: string
}

Page({
  data: {
    /** 输入框里正在编辑的内容。与下面已提交的 keyword 分开:否则每敲一个字都会发一次请求 */
    inputValue: '',
    /** 已提交的搜索词,列表当前就是按它查的 */
    keyword: '',
    /** 从首页搜索入口进来时自动聚焦,直接就是搜索的心智;从分类进来则不弹键盘 */
    focus: false,
    chips: [{ id: '', label: '全部' }] as CategoryChip[],
    activeCategoryId: '' as Id,
    cards: [] as GoodsCard[],
    pageNo: 1,
    loading: false,
    finished: false,
  },

  onLoad(query: Record<string, string | undefined>) {
    this.setData({
      focus: query.focus === '1',
      activeCategoryId: query.categoryId || '',
    })
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

  onInput(e: WechatMiniprogram.Input) {
    this.setData({ inputValue: e.detail.value })
  },

  onSearch() {
    const keyword = this.data.inputValue.trim()
    if (keyword === this.data.keyword) {
      // 词没变就不重复请求;但用户按了搜索总得有点反馈
      void this.loadFirstPage()
      return
    }
    this.setData({ keyword })
    void this.loadFirstPage()
  },

  onClearKeyword() {
    if (!this.data.inputValue && !this.data.keyword) {
      return
    }
    this.setData({ inputValue: '', keyword: '' })
    void this.loadFirstPage()
  },

  onCategoryTap(e: WechatMiniprogram.TouchEvent) {
    const raw = e.currentTarget.dataset.id
    const id = raw === undefined || raw === null ? '' : String(raw)
    if (id === this.data.activeCategoryId) {
      return
    }
    this.setData({ activeCategoryId: id })
    void this.loadFirstPage()
  },

  onGoodsTap(e: WechatMiniprogram.TouchEvent) {
    const id = String(e.currentTarget.dataset.id || '')
    if (!id) {
      return
    }
    wx.navigateTo({ url: `/pages/goods/detail/index?id=${id}` })
  },

  async loadCategories() {
    try {
      const tree = await categories()
      // 只取一级分类:二级分类的筛选粒度太细,放在这里会把筛选条撑得很长
      const chips: CategoryChip[] = [{ id: '', label: '全部' }]
      for (const node of tree || []) {
        chips.push({ id: node.id, label: node.categoryName })
      }
      this.setData({ chips })
    } catch (err) {
      // 分类失败不影响搜索与商品列表 —— 它的主要信息是商品
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
        keyword: this.data.keyword || undefined,
        pageNo,
        pageSize: PAGE_SIZE,
      })
      const cards = (result.list || []).map(toCard)
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
