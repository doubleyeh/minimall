import { profile as fetchProfile, updateProfile } from '../../api/client'
import type { ClientProfileView } from '../../types/client'
import { setPendingOrdersTab } from '../../utils/nav'
import { toastError, toastOk } from '../../utils/ui'

Page({
  data: {
    nickname: '微信用户',
    avatarUrl: '',
    hasAvatar: false,
    /** 没有头像时显示昵称首字,比放一张通用头像图更像"这个人" */
    avatarChar: '客',
    points: 0,
    growthValue: 0,
    counts: [
      { label: '待付款', value: 0, status: 1 },
      { label: '待发货', value: 0, status: 2 },
      { label: '待收货', value: 0, status: 3 },
      { label: '已完成', value: 0, status: 4 },
    ],
    loadFailed: '',
  },

  onShow() {
    void this.load()
  },

  async load() {
    try {
      const data = await fetchProfile()
      this.apply(data)
    } catch (err) {
      this.setData({ loadFailed: err instanceof Error ? err.message : '资料加载失败' })
    }
  },

  apply(data: ClientProfileView) {
    const nickname = data.nickname || '微信用户'
    const counts = this.data.counts.map((item) => {
      if (item.status === 1) return { ...item, value: data.orderCounts.pendingPay }
      if (item.status === 2) return { ...item, value: data.orderCounts.pendingShip }
      if (item.status === 3) return { ...item, value: data.orderCounts.pendingReceive }
      return { ...item, value: data.orderCounts.finished }
    })
    this.setData({
      nickname,
      avatarUrl: data.avatarUrl || '',
      hasAvatar: !!data.avatarUrl,
      avatarChar: nickname.slice(0, 1),
      points: data.points,
      growthValue: data.growthValue,
      counts,
      loadFailed: '',
    })
  },

  /**
   * 修改昵称。
   *
   * 用 `wx.showModal` 的 editable 模式,而不是专门做一个表单页:
   * 只是改一个字符串,为它多一个页面和一次路由跳转不划算。
   *
   * 头像没做:后端**没有文件上传端点**(全仓库搜不到 MultipartFile),
   * 而 `chooseAvatar` 拿到的是本地临时路径,不传上去存不住。
   */
  onEditNickname() {
    wx.showModal({
      title: '修改昵称',
      editable: true,
      placeholderText: '请输入昵称',
      content: this.data.nickname === '微信用户' ? '' : this.data.nickname,
      success: (res) => {
        if (!res.confirm) {
          return
        }
        const nickname = (res.content || '').trim()
        if (!nickname) {
          toastOk('昵称不能为空')
          return
        }
        void this.saveNickname(nickname)
      },
    })
  },

  async saveNickname(nickname: string) {
    try {
      await updateProfile({ nickname })
      toastOk('已保存')
      await this.load()
    } catch (err) {
      toastError(err, '保存失败')
    }
  },

  /**
   * 跳到订单 tab 并切到对应状态。
   *
   * **不能用 navigateTo**:订单页在 tabBar 里,只能用 switchTab 打开,
   * 而 switchTab 不接受 query —— 所以状态通过 utils/nav.ts 的槽位传递。
   */
  onOrderEntry(e: WechatMiniprogram.TouchEvent) {
    setPendingOrdersTab(Number(e.currentTarget.dataset.status))
    wx.switchTab({ url: '/pages/orders/index' })
  },

  onAllOrders() {
    setPendingOrdersTab(0)
    wx.switchTab({ url: '/pages/orders/index' })
  },

  onCouponsTap() {
    wx.navigateTo({ url: '/pages/coupons/index' })
  },

  onAddressTap() {
    wx.navigateTo({ url: '/pages/address/index' })
  },

  onAfterSaleTap() {
    wx.navigateTo({ url: '/pages/after-sale/index' })
  },
})
