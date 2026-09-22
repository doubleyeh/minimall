import { createReview } from '../../../api/client'
import { toast, toastError } from '../../../utils/ui'

interface Star {
  value: number
  char: string
  on: boolean
}

Page({
  data: {
    orderItemId: '',
    rating: 5,
    starList: buildStars(5),
    content: '',
    anonymous: false,
    submitting: false,
  },

  onLoad(query: Record<string, string | undefined>) {
    const orderItemId = query.orderItemId || ''
    if (!orderItemId) {
      toast('缺少订单明细参数')
      return
    }
    this.setData({ orderItemId })
  },

  onStarTap(e: WechatMiniprogram.TouchEvent) {
    const rating = Number(e.currentTarget.dataset.value)
    this.setData({ rating, starList: buildStars(rating) })
  },

  onContentInput(e: WechatMiniprogram.Input) {
    this.setData({ content: e.detail.value })
  },

  onAnonymousChange(e: WechatMiniprogram.SwitchChange) {
    this.setData({ anonymous: e.detail.value })
  },

  async onSubmit() {
    if (this.data.submitting) {
      return
    }
    this.setData({ submitting: true })
    try {
      await createReview({
        orderItemId: this.data.orderItemId,
        rating: this.data.rating,
        content: this.data.content.trim() || undefined,
        anonymous: this.data.anonymous,
      })
      toast('评价成功')
      // 返回上一页(订单详情),它会在 onShow 里刷新 —— 已经评过的那件不再显示入口
      wx.navigateBack()
    } catch (err) {
      // 后端的两种拒绝都带了可直接展示的文案:订单未完成、该明细已评价过
      toastError(err, '提交失败')
    } finally {
      this.setData({ submitting: false })
    }
  },
})

/** 五颗星的选中态在 JS 里算好 —— WXML 不能调函数,也没法在循环里做索引比较之外的计算 */
function buildStars(rating: number): Star[] {
  const stars: Star[] = []
  for (let value = 1; value <= 5; value++) {
    stars.push({ value, char: '★', on: value <= rating })
  }
  return stars
}
