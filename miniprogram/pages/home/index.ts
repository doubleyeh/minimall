import { categories, goodsPage, seckillHome } from '../../api/client'
import type { Id } from '../../types/client'
import { toCard, type GoodsCard } from '../../utils/goods'

/** 金刚区一格 */
interface CategoryCell {
  id: Id
  label: string
  /** 圆形徽标里显示的字 */
  char: string
  /** 浅底色号(0-3),四种轮换 */
  tone: number
}

interface Banner {
  id: Id
  name: string
  image: string
  priceText: string
}

const PAGE_SIZE = 10

Page({
  data: {
    banners: [] as Banner[],
    catCells: [] as CategoryCell[],
    activeCategoryId: '' as Id,
    activeCategoryName: '',
    sectionTitle: '为你推荐',
    cards: [] as GoodsCard[],
    pageNo: 1,
    loading: false,
    finished: false,
    /** 秒杀入口:没有场次时整块不渲染 */
    seckillCount: 0,
    seckillHint: '',
  },

  onLoad() {
    void this.loadCategories()
    void this.loadFirstPage()
    void this.loadSeckill()
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

  /**
   * 分类点击。
   *
   * 再点一次已选中的分类 = 回到全部 —— 这样就不用在下面再摆一排"全部/分类"的筛选按钮,
   * 首页少一行按钮,商品就多一行可见空间。
   */
  onCategoryTap(e: WechatMiniprogram.TouchEvent) {
    const raw = e.currentTarget.dataset.id
    const id = raw === undefined || raw === null ? '' : String(raw)
    const next = id === this.data.activeCategoryId ? '' : id
    const cell = this.data.catCells.find((item) => item.id === next)
    this.setData({
      activeCategoryId: next,
      activeCategoryName: cell ? cell.label : '',
      sectionTitle: cell ? cell.label : '为你推荐',
    })
    void this.loadFirstPage()
  },

  onSeckillTap() {
    wx.navigateTo({ url: '/pages/seckill/index' })
  },

  /**
   * 只拉"有几场",不拉商品明细。
   *
   * 首页每次曝光都会跑这段代码,把整份秒杀列表拉下来等于把秒杀的读压力前置到首屏。
   */
  async loadSeckill() {
    try {
      const home = await seckillHome()
      const running = (home.running || []).length
      const upcoming = (home.upcoming || []).length
      const count = running + upcoming
      if (count === 0) {
        return
      }
      const hint = running > 0 ? `${running} 场正在疯抢` : `${upcoming} 场即将开始`
      this.setData({ seckillCount: count, seckillHint: hint })
    } catch {
      // 入口条是锦上添花,拉不到就不显示,不能因为秒杀接口把首屏拖垮
      this.setData({ seckillCount: 0 })
    }
  },

  onGoodsTap(e: WechatMiniprogram.TouchEvent) {
    const id = String(e.currentTarget.dataset.id || '')
    if (!id) {
      return
    }
    wx.navigateTo({ url: `/pages/goods/detail/index?id=${id}` })
  },

  onSearchTap() {
    // focus=1:从"搜索"进来就直接弹键盘,不用再点一下输入框
    wx.navigateTo({ url: '/pages/goods/list/index?focus=1' })
  },

  async loadCategories() {
    try {
      const tree = await categories()
      const catCells = (tree || []).slice(0, 5).map((node, index) => ({
        id: node.id,
        label: node.categoryName,
        char: node.categoryName.slice(0, 1),
        // 用下标轮换色号:同一个分类每次进来颜色一致,不会闪
        tone: index % 4,
      }))
      this.setData({ catCells })
    } catch (err) {
      // 分类失败不阻塞商品列表:首页的主要信息是商品
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
      const cards = (result.list || []).map(toCard)
      const merged = replace ? cards : this.data.cards.concat(cards)

      const patch: Record<string, unknown> = {
        cards: merged,
        pageNo,
        // 用累计条数与总数比较,而不是"这一页没满就结束":
        // 后端按可售过滤时,某页返回不足 PAGE_SIZE 并不代表没有下一页
        finished: merged.length >= result.total,
      }
      // 轮播只在最开始的"全部"列表里取一次:用户切了分类之后不该把轮播也换掉
      if (replace && this.data.activeCategoryId === '' && this.data.banners.length === 0 && cards.length > 0) {
        patch.banners = cards.slice(0, 3).map((card) => ({
          id: card.id,
          name: card.name,
          image: card.image,
          priceText: card.priceText,
        }))
      }
      this.setData(patch)
    } catch (err) {
      // 失败时不推进页码,避免漏掉一页数据(下次触底会重试同一页)
      wx.showToast({ title: err instanceof Error ? err.message : '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },
})
