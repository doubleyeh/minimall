import { claimableCoupons, claimCoupon, myCoupons } from '../../api/client'
import type { ClientCouponView, Id } from '../../types/client'
import { couponValueText, dateTime } from '../../utils/format'
import { toast, toastError, toastOk } from '../../utils/ui'

/** 领取文案:后端用同一个 statusText 承载"可领取/已领取/已领完"(见接口说明)。 */
const CLAIMABLE_TEXT = '可领取'

interface CouponRow {
  key: string
  couponId: Id
  title: string
  valueText: string
  timeText: string
  statusText: string
  canClaim: boolean
}

Page({
  data: {
    tab: 'claimable' as 'claimable' | 'mine',
    list: [] as CouponRow[],
    loading: false,
  },

  onLoad() {
    void this.load()
  },

  onTabTap(e: WechatMiniprogram.TouchEvent) {
    const tab = String(e.currentTarget.dataset.tab || 'claimable') as 'claimable' | 'mine'
    if (tab === this.data.tab) {
      return
    }
    this.setData({ tab, list: [] })
    void this.load()
  },

  async load() {
    if (this.data.loading) {
      return
    }
    this.setData({ loading: true })
    try {
      const list = this.data.tab === 'claimable'
        ? (await claimableCoupons() || []).map(toClaimableRow)
        : (await myCoupons(null) || []).map(toMineRow)
      this.setData({ list })
    } catch (err) {
      toastError(err, '优惠券加载失败')
    } finally {
      this.setData({ loading: false })
    }
  },

  async onClaim(e: WechatMiniprogram.TouchEvent) {
    const couponId = String(e.currentTarget.dataset.id || '')
    const row = this.data.list.find((item) => item.couponId === couponId)
    if (!row) {
      return
    }
    if (!row.canClaim) {
      toast('当前不可领取')
      return
    }
    try {
      await claimCoupon(couponId)
      toastOk('领取成功')
      // 重新拉一次而不是本地改文案:总量是否领完、每人限领是否到顶都只有后端知道
      await this.load()
    } catch (err) {
      toastError(err, '领取失败')
    }
  },
})

function toClaimableRow(item: ClientCouponView): CouponRow {
  const statusText = item.statusText || CLAIMABLE_TEXT
  return {
    key: `c-${item.couponId}`,
    couponId: item.couponId,
    title: item.couponName,
    valueText: couponValueText(item),
    timeText: item.validEndTime ? `有效期至 ${dateTime(item.validEndTime)}` : '',
    statusText,
    canClaim: statusText === CLAIMABLE_TEXT,
  }
}

function toMineRow(item: ClientCouponView): CouponRow {
  return {
    key: `r-${item.recordId || item.couponId}`,
    couponId: item.couponId,
    title: item.couponName,
    valueText: couponValueText(item),
    timeText: item.validEndTime ? `有效期至 ${dateTime(item.validEndTime)}` : '',
    statusText: item.statusText || '',
    canClaim: false,
  }
}
