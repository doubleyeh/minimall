import { createReview } from '../../../api/client'
import { toast, toastError } from '../../../utils/ui'
import { chooseAndUploadImages } from '../../../utils/upload'

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
    images: [] as string[],
    imageMax: 3,
    uploading: false,
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

  async onAddImage() {
    if (this.data.uploading) {
      return
    }
    const remaining = this.data.imageMax - this.data.images.length
    if (remaining <= 0) {
      toast(`最多 ${this.data.imageMax} 张`)
      return
    }
    this.setData({ uploading: true })
    try {
      const urls = await chooseAndUploadImages(remaining)
      if (urls.length > 0) {
        this.setData({ images: this.data.images.concat(urls) })
      }
    } catch (err) {
      toastError(err, '图片上传失败')
    } finally {
      this.setData({ uploading: false })
    }
  },

  onRemoveImage(e: WechatMiniprogram.TouchEvent) {
    const index = Number(e.currentTarget.dataset.index)
    const images = this.data.images.slice()
    images.splice(index, 1)
    this.setData({ images })
  },

  onPreviewImage(e: WechatMiniprogram.TouchEvent) {
    const index = Number(e.currentTarget.dataset.index)
    wx.previewImage({ current: this.data.images[index], urls: this.data.images })
  },

  async onSubmit() {
    if (this.data.submitting || this.data.uploading) {
      return
    }
    this.setData({ submitting: true })
    try {
      await createReview({
        orderItemId: this.data.orderItemId,
        rating: this.data.rating,
        content: this.data.content.trim() || undefined,
        images: this.data.images.length > 0 ? this.data.images : undefined,
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
