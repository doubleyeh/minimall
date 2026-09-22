import { mockPaySuccess, orderCancel, orderDetail, orderPrepay, orderReceive } from '../../api/client'
import type { ClientOrderView, Id } from '../../types/client'
import { IS_RELEASE } from '../../utils/env'
import { closeReasonText, dateTime, money, orderStatusText } from '../../utils/format'
import { isPayCancelled, requestPayment } from '../../utils/pay'
import { confirmModal, toast, toastError } from '../../utils/ui'

interface DetailLine {
  id: Id
  name: string
  skuName: string
  image: string
  quantity: number
  amountText: string
  afterSaleText: string
}

Page({
  data: {
    orderId: '',
    loadFailed: '',
    status: 0,
    statusText: '',
    payTip: '',
    closeReason: '',
    receiverName: '',
    receiverPhone: '',
    receiverAddress: '',
    logisticsCompany: '',
    logisticsNo: '',
    remark: '',
    lines: [] as DetailLine[],
    goodsAmountText: '0.00',
    freightAmountText: '0.00',
    promotionText: '0.00',
    couponText: '0.00',
    payAmountText: '0.00',
    timeLines: [] as Array<{ label: string; value: string }>,
    canAfterSale: false,
    canReview: false,
  },

  onLoad(query: Record<string, string | undefined>) {
    const orderId = query.id || ''
    if (!orderId) {
      this.setData({ loadFailed: '缺少订单参数' })
      return
    }
    this.setData({ orderId })
    void this.load()
  },

  async load() {
    try {
      const order = await orderDetail(this.data.orderId)
      this.apply(order)
    } catch (err) {
      this.setData({ loadFailed: err instanceof Error ? err.message : '订单加载失败' })
    }
  },

  apply(order: ClientOrderView) {
    const timeLines: Array<{ label: string; value: string }> = []
    if (order.createTime) timeLines.push({ label: '下单时间', value: dateTime(order.createTime) })
    if (order.payTime) timeLines.push({ label: '支付时间', value: dateTime(order.payTime) })
    if (order.shipTime) timeLines.push({ label: '发货时间', value: dateTime(order.shipTime) })
    if (order.receiveTime) timeLines.push({ label: '收货时间', value: dateTime(order.receiveTime) })
    if (order.finishTime) timeLines.push({ label: '完成时间', value: dateTime(order.finishTime) })

    this.setData({
      status: order.status,
      statusText: orderStatusText(order.status),
      // 待支付的时限来自后端字典(order_pay_timeout_minutes),这里只做提示不求值
      payTip: order.status === 1 ? '请在 15 分钟内完成支付,超时订单会自动关闭' : '',
      closeReason: closeReasonText(order.closeReason),
      receiverName: order.receiverName,
      receiverPhone: order.receiverPhone,
      receiverAddress: order.receiverAddress,
      logisticsCompany: order.logisticsCompany || '',
      logisticsNo: order.logisticsNo || '',
      remark: order.remark || '',
      lines: (order.items || []).map((item) => ({
        id: item.id,
        name: item.goodsName,
        skuName: item.skuName,
        image: item.goodsImage,
        quantity: item.quantity,
        amountText: money(item.totalAmount),
        afterSaleText: afterSaleStatusText(item.afterSaleStatus),
      })),
      goodsAmountText: money(order.goodsAmount),
      freightAmountText: money(order.freightAmount),
      promotionText: money(order.promotionDiscountAmount),
      couponText: money(order.couponDiscountAmount),
      payAmountText: money(order.payAmount),
      timeLines,
      // 与 H5 版一致:已支付(2)、待收货(3)、已完成(4)可以申请售后
      canAfterSale: order.status === 2 || order.status === 3 || order.status === 4,
      // 只有已完成才能评价(后端也是这个规则,前端先不给入口免得用户白填一遍)
      canReview: order.status === 4,
    })
  },

  async onCancel() {
    if (!(await confirmModal('取消后库存会释放,确定取消这笔订单吗?'))) {
      return
    }
    try {
      await orderCancel(this.data.orderId)
      toast('已取消')
      await this.load()
    } catch (err) {
      toastError(err)
    }
  },

  async onReceive() {
    if (!(await confirmModal('确认已收到货?'))) {
      return
    }
    try {
      await orderReceive(this.data.orderId)
      toast('已确认收货')
      await this.load()
    } catch (err) {
      toastError(err)
    }
  },

  async onPay() {
    const order = await this.safeOrder()
    if (!order) {
      return
    }
    try {
      const payParams = await orderPrepay(order.id)
      if (!IS_RELEASE) {
        await mockPaySuccess(order.orderNo, order.payAmount)
        toast('支付成功(本地为模拟)')
        await this.load()
        return
      }
      try {
        await requestPayment(payParams)
        await this.load()
      } catch (err) {
        if (!isPayCancelled(err)) {
          toast('支付未完成')
        }
      }
    } catch (err) {
      toastError(err, '支付失败')
    }
  },

  /** 重新取一次订单:支付要用 orderNo 与金额,拿页面 data 拼容易漏字段 */
  async safeOrder(): Promise<ClientOrderView | null> {
    try {
      return await orderDetail(this.data.orderId)
    } catch (err) {
      toastError(err)
      return null
    }
  },

  onReview(e: WechatMiniprogram.TouchEvent) {
    const orderItemId = String(e.currentTarget.dataset.itemId || '')
    wx.navigateTo({ url: `/pages/review/create/index?orderItemId=${orderItemId}` })
  },

  onAfterSale() {
    if (this.data.lines.length === 0) {
      toast('该订单没有可申请售后的商品')
      return
    }
    // 与列表页一致:详情页的订单级售后也取第一件,逐件售后在下面的商品行里做
    wx.navigateTo({
      url: `/pages/after-sale/apply/index?orderItemId=${this.data.lines[0].id}&amount=${this.data.lines[0].amountText}`,
    })
  },
})

/** 逐件售后状态(与 H5 版口径一致:1 处理中、2 已完成、其余为未申请)。 */
function afterSaleStatusText(status: number): string {
  if (status === 1) return '售后处理中'
  if (status === 2) return '售后已完成'
  return '未申请售后'
}
