import { afterSaleApply } from '../../../api/client'
import { toast, toastError } from '../../../utils/ui'

Page({
  data: {
    orderItemId: '',
    afterSaleType: 1,
    refundAmount: '',
    applyReason: '',
    applyDesc: '',
    submitting: false,
    /**
     * 三种类型都列出来,不做前置限制。
     *
     * 可用范围取决于订单状态(待发货只能是仅退款,待收货/已完成才可选退货或换货,
     * 见 AfterSaleServiceImpl 的校验),但那需要先知道订单状态,而这里只有一个明细 id,
     * 接口里没有"按明细查订单"的入口。硬造一份本地规则,只会在后端规则调整后
     * 变成"界面允许、提交被拒" —— 所以交给后端判定,它给的就是可直接展示的中文。
     */
    types: [
      { value: 1, label: '仅退款' },
      { value: 2, label: '退货退款' },
      { value: 3, label: '换货' },
    ],
  },

  onLoad(query: Record<string, string | undefined>) {
    const orderItemId = query.orderItemId || ''
    if (!orderItemId) {
      toast('缺少订单明细参数')
      return
    }
    this.setData({
      orderItemId,
      // 金额由订单页带过来,省得用户自己去订单里翻
      refundAmount: query.amount || '',
    })
  },

  onTypeTap(e: WechatMiniprogram.TouchEvent) {
    this.setData({ afterSaleType: Number(e.currentTarget.dataset.value) })
  },

  onAmountInput(e: WechatMiniprogram.Input) {
    this.setData({ refundAmount: e.detail.value })
  },

  onReasonInput(e: WechatMiniprogram.Input) {
    this.setData({ applyReason: e.detail.value })
  },

  onDescInput(e: WechatMiniprogram.Input) {
    this.setData({ applyDesc: e.detail.value })
  },

  async onSubmit() {
    if (this.data.submitting) {
      return
    }
    const reason = this.data.applyReason.trim()
    const amount = Number(this.data.refundAmount)

    if (!reason) {
      toast('请填写申请原因')
      return
    }
    if (!isFinite(amount) || amount <= 0) {
      toast('请填写正确的退款金额')
      return
    }

    this.setData({ submitting: true })
    try {
      await afterSaleApply({
        orderItemId: this.data.orderItemId,
        afterSaleType: this.data.afterSaleType,
        applyReason: reason,
        applyDesc: this.data.applyDesc.trim() || undefined,
        refundAmount: amount,
        // images 不传:后端没有上传接口(见页面提示)
      })
      toast('申请已提交')
      // 用 redirectTo 到列表:申请页不该留在栈里,回来时订单已经不能重复申请
      wx.redirectTo({ url: '/pages/after-sale/index' })
    } catch (err) {
      toastError(err, '提交失败')
    } finally {
      this.setData({ submitting: false })
    }
  },
})
