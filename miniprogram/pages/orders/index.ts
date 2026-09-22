import { mockPaySuccess, orderCancel, orderList, orderPrepay, orderReceive } from '../../api/client'
import type { ClientOrderView, Id } from '../../types/client'
import { IS_RELEASE } from '../../utils/env'
import { money, orderStatusText } from '../../utils/format'
import { takePendingOrdersTab } from '../../utils/nav'
import { isPayCancelled, requestPayment } from '../../utils/pay'
import { confirmModal, toast, toastError } from '../../utils/ui'

interface OrderLine {
  id: Id
  name: string
  skuName: string
  image: string
  quantity: number
  amountText: string
}

interface OrderCard {
  id: Id
  orderNo: string
  status: number
  statusText: string
  payAmountText: string
  lines: OrderLine[]
  itemCountText: string
}

const PAGE_SIZE = 10

Page({
  data: {
    tabs: [
      { label: '全部', status: 0 },
      { label: '待支付', status: 1 },
      { label: '待发货', status: 2 },
      { label: '待收货', status: 3 },
      { label: '已完成', status: 4 },
    ],
    activeIndex: 0,
    orders: [] as OrderCard[],
    pageNo: 1,
    loading: false,
    hasMore: false,
  },

  /** 当前筛选状态;null 表示"全部"。放在实例上而不是 data:它不参与渲染 */
  activeStatus: null as number | null,

  /**
   * tab 页用 onShow:从支付/发货等操作返回后列表要刷新。
   *
   * 顺带消费"我的"页留下的目标状态 —— switchTab 不支持 query,只能这样传(见 utils/nav.ts)。
   */
  onShow() {
    const pending = takePendingOrdersTab()
    if (pending !== null) {
      const index = this.data.tabs.findIndex((tab) => tab.status === pending)
      if (index >= 0 && index !== this.data.activeIndex) {
        this.activeStatus = pending
        this.setData({ activeIndex: index, orders: [] })
      }
    }
    void this.loadFirstPage()
  },

  onReachBottom() {
    void this.loadMore()
  },

  onTabTap(e: WechatMiniprogram.TouchEvent) {
    const index = Number(e.currentTarget.dataset.index || 0)
    if (index === this.data.activeIndex) {
      return
    }
    const tab = this.data.tabs[index]
    this.activeStatus = tab.status === 0 ? null : tab.status
    this.setData({ activeIndex: index, orders: [] })
    void this.loadFirstPage()
  },

  async loadFirstPage(): Promise<void> {
    await this.load(1, true)
  },

  async loadMore(): Promise<void> {
    if (!this.data.hasMore || this.data.loading) {
      return
    }
    await this.load(this.data.pageNo + 1, false)
  },

  async load(pageNo: number, replace: boolean): Promise<void> {
    if (this.data.loading) {
      return
    }
    this.setData({ loading: true })
    try {
      const result = await orderList(this.activeStatus, pageNo, PAGE_SIZE)
      const cards = (result.list || []).map(toCard)
      const merged = replace ? cards : this.data.orders.concat(cards)
      this.setData({
        orders: merged,
        pageNo,
        hasMore: merged.length < result.total,
      })
    } catch (err) {
      toastError(err, '订单加载失败')
    } finally {
      this.setData({ loading: false })
    }
  },

  onDetailTap(e: WechatMiniprogram.TouchEvent) {
    const id = String(e.currentTarget.dataset.id || '')
    wx.navigateTo({ url: `/pages/order-detail/index?id=${id}` })
  },

  /** 空处理器:仅用于 catchtap 拦住冒泡,否则点按钮会同时触发"进详情" */
  noop() {
    // 故意为空
  },

  async onCancel(e: WechatMiniprogram.TouchEvent) {
    const id = String(e.currentTarget.dataset.id || '')
    if (!(await confirmModal('取消后库存会释放,确定取消这笔订单吗?'))) {
      return
    }
    try {
      await orderCancel(id)
      toast('已取消')
      await this.loadFirstPage()
    } catch (err) {
      toastError(err)
    }
  },

  async onReceive(e: WechatMiniprogram.TouchEvent) {
    const id = String(e.currentTarget.dataset.id || '')
    if (!(await confirmModal('确认已收到货?确认后订单完成,并开始计算售后期限。'))) {
      return
    }
    try {
      await orderReceive(id)
      toast('已确认收货')
      await this.loadFirstPage()
    } catch (err) {
      toastError(err)
    }
  },

  async onPay(e: WechatMiniprogram.TouchEvent) {
    const id = String(e.currentTarget.dataset.id || '')
    const order = this.data.orders.find((item) => item.id === id)
    if (!order) {
      return
    }
    try {
      const payParams = await orderPrepay(id)
      if (!IS_RELEASE) {
        // 开发环境:后端返回的是假支付参数,wx.requestPayment 必然失败,
        // 所以直接打模拟回调把订单推进到"待发货",否则后面的流程本地走不到
        await mockPaySuccess(order.orderNo, Number(order.payAmountText))
        toast('支付成功(本地为模拟)')
        await this.loadFirstPage()
        return
      }
      try {
        await requestPayment(payParams)
        await this.loadFirstPage()
      } catch (err) {
        if (!isPayCancelled(err)) {
          toast('支付未完成')
        }
      }
    } catch (err) {
      toastError(err, '支付失败')
    }
  },

  /**
   * 申请售后。
   *
   * 列表页拿第一件商品去申请(与 H5 版一致):多件商品逐件售后在订单详情里做。
   * 订单为空时明确提示,而不是静默什么都不发生。
   */
  onAfterSale(e: WechatMiniprogram.TouchEvent) {
    const id = String(e.currentTarget.dataset.id || '')
    const order = this.data.orders.find((item) => item.id === id)
    const line = order && order.lines.length > 0 ? order.lines[0] : null
    if (!line) {
      toast('该订单没有可申请售后的商品')
      return
    }
    // 带上金额:申请页用它预填退款金额,省得用户回订单里翻自己付了多少
    wx.navigateTo({ url: `/pages/after-sale/apply/index?orderItemId=${line.id}&amount=${line.amountText}` })
  },
})

function toCard(order: ClientOrderView): OrderCard {
  const items = order.items || []
  const count = items.reduce((sum, item) => sum + item.quantity, 0)
  return {
    id: order.id,
    orderNo: order.orderNo,
    status: order.status,
    statusText: orderStatusText(order.status),
    payAmountText: money(order.payAmount),
    itemCountText: String(count),
    lines: items.map((item) => ({
      id: item.id,
      name: item.goodsName,
      skuName: item.skuName,
      image: item.goodsImage,
      quantity: item.quantity,
      amountText: money(item.totalAmount),
    })),
  }
}
