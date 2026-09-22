import { cartClear, cartList, cartRemove, cartUpdate } from '../../api/client'
import type { CartItemView, Id } from '../../types/client'
import { money } from '../../utils/format'
import { confirmModal, toast, toastError, toastOk } from '../../utils/ui'

/**
 * 购物车行。
 *
 * 同时保留 `price`(参与合计计算)与 `priceText`(展示)—— WXML 不能调函数,
 * 但合计必须在 JS 里用数字算,不能拿字符串求和。
 */
interface CartRow {
  id: Id
  goodsId: Id
  name: string
  skuName: string
  image: string
  price: number
  priceText: string
  quantity: number
  availableStock: number
  selected: boolean
  valid: boolean
}

Page({
  data: {
    rows: [] as CartRow[],
    allSelected: false,
    totalText: '0.00',
    loading: false,
  },

  /**
   * 用 onShow 而不是 onLoad。
   *
   * 购物车是 tab 页,而且用户会从商品详情页加购后再切回来 ——
   * onLoad 只在第一次进入时触发,那样加购后回来看到的还是旧列表。
   */
  onShow() {
    void this.load()
  },

  async load() {
    this.setData({ loading: true })
    try {
      const list = await cartList()
      this.setData({ rows: (list || []).map(toRow) })
      this.refreshSummary()
    } catch (err) {
      toastError(err, '购物车加载失败')
    } finally {
      this.setData({ loading: false })
    }
  },

  /** 合计只算"选中且有效"的行:失效商品即使被勾上也结算不了,算进去会给出错误金额 */
  refreshSummary() {
    const rows = this.data.rows
    const selected = rows.filter((row) => row.valid && row.selected)
    const total = selected.reduce((sum, row) => sum + row.price * row.quantity, 0)
    const selectable = rows.filter((row) => row.valid)
    this.setData({
      totalText: money(total),
      allSelected: selectable.length > 0 && selectable.every((row) => row.selected),
    })
  },

  async onToggle(e: WechatMiniprogram.TouchEvent) {
    const id = String(e.currentTarget.dataset.id || '')
    const row = this.data.rows.find((item) => item.id === id)
    if (!row) {
      return
    }
    if (!row.valid) {
      toast('该商品已失效,不能结算')
      return
    }
    try {
      await cartUpdate(id, { selected: row.selected ? 0 : 1 })
      await this.load()
    } catch (err) {
      toastError(err)
    }
  },

  async onToggleAll() {
    const target = this.data.allSelected ? 0 : 1
    const rows = this.data.rows.filter((row) => row.valid)
    if (rows.length === 0) {
      return
    }
    try {
      // 逐个提交:后端没有批量勾选接口,而失效行不该被改动
      for (const row of rows) {
        if ((row.selected ? 1 : 0) !== target) {
          await cartUpdate(row.id, { selected: target })
        }
      }
      await this.load()
    } catch (err) {
      toastError(err)
    }
  },

  async onPlus(e: WechatMiniprogram.TouchEvent) {
    const id = String(e.currentTarget.dataset.id || '')
    const row = this.data.rows.find((item) => item.id === id)
    if (!row) {
      return
    }
    if (row.quantity >= row.availableStock) {
      toast(`库存不足,仅剩 ${row.availableStock} 件`)
      return
    }
    await this.changeQuantity(row, row.quantity + 1)
  },

  async onMinus(e: WechatMiniprogram.TouchEvent) {
    const id = String(e.currentTarget.dataset.id || '')
    const row = this.data.rows.find((item) => item.id === id)
    if (!row || row.quantity <= 1) {
      return
    }
    await this.changeQuantity(row, row.quantity - 1)
  },

  async changeQuantity(row: CartRow, quantity: number) {
    try {
      await cartUpdate(row.id, { quantity })
      await this.load()
    } catch (err) {
      // 后端会校验可售库存,超了会返回可读文案;本地也提示一次是为了少一次往返
      toastError(err)
      await this.load()
    }
  },

  async onRemove(e: WechatMiniprogram.TouchEvent) {
    const id = String(e.currentTarget.dataset.id || '')
    if (!(await confirmModal('确定删除这件商品吗?'))) {
      return
    }
    try {
      await cartRemove([id])
      await this.load()
    } catch (err) {
      toastError(err)
    }
  },

  async onClear() {
    if (!(await confirmModal('清空购物车后无法恢复,确定吗?'))) {
      return
    }
    try {
      await cartClear()
      toastOk('已清空')
      await this.load()
    } catch (err) {
      toastError(err)
    }
  },

  onGoodsTap(e: WechatMiniprogram.TouchEvent) {
    const goodsId = String(e.currentTarget.dataset.goodsId || '')
    if (!goodsId) {
      return
    }
    wx.navigateTo({ url: `/pages/goods/detail/index?id=${goodsId}` })
  },

  onCheckout() {
    const selected = this.data.rows.filter((row) => row.valid && row.selected)
    if (selected.length === 0) {
      toast('请选择要结算的商品')
      return
    }
    // 不带 items 参数:后端约定 items 为空即"结算购物车中已勾选的商品"
    wx.navigateTo({ url: '/pages/checkout/index' })
  },
})

function toRow(item: CartItemView): CartRow {
  const price = typeof item.price === 'number' ? item.price : 0
  return {
    id: item.id,
    goodsId: item.goodsId,
    name: item.goodsName || '商品',
    skuName: item.skuName || '',
    image: item.image || '',
    price,
    priceText: money(price),
    quantity: item.quantity,
    availableStock: item.availableStock,
    selected: item.selected === 1,
    valid: !!item.valid,
  }
}
