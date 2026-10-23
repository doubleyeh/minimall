import { seckillDetail } from '../../../api/client'
import type { ClientSeckillItemView } from '../../../types/client'
import { money } from '../../../utils/format'
import { toastError } from '../../../utils/ui'

/**
 * 秒杀详情(商城设计文档 3.12)。
 *
 * 剩余量由服务端给出(优先读 Redis 闸门),端上不自己算 —— 端上算出来的数字与下单结果必然对不上。
 * 进行中每 5 秒刷新一次剩余量,不刷新的话用户会对着一个早就卖完的按钮点。
 */
const REFRESH_MS = 5000

type SeckillState = 'loading' | 'soon' | 'running' | 'soldout' | 'limit' | 'ended'

Page({
  data: {
    itemId: '',
    goodsId: '',
    goodsName: '',
    image: '',
    skuName: '',
    priceText: '0.00',
    originalText: '0.00',
    remainingText: '',
    progressText: '',
    percent: 0,
    limitText: '',
    countdownText: '',
    countdownLabel: '',
    startMs: 0,
    endMs: 0,
    state: 'loading' as SeckillState,
    buttonText: '加载中',
    disabled: true,
  },

  timer: 0,
  refreshTimer: 0,

  onLoad(query: Record<string, string | undefined>) {
    const itemId = query.itemId || ''
    this.setData({ itemId })
    void this.load()
    this.timer = setInterval(() => this.tick(), 1000) as unknown as number
    this.refreshTimer = setInterval(() => void this.load(), REFRESH_MS) as unknown as number
  },

  onUnload() {
    if (this.timer) {
      clearInterval(this.timer)
      this.timer = 0
    }
    if (this.refreshTimer) {
      clearInterval(this.refreshTimer)
      this.refreshTimer = 0
    }
  },

  async load() {
    if (!this.data.itemId) {
      return
    }
    try {
      const item = await seckillDetail(this.data.itemId)
      this.apply(item)
    } catch (err) {
      toastError(err, '秒杀信息加载失败')
    }
  },

  apply(item: ClientSeckillItemView) {
    const quota = item.totalQuota || 1
    const percent = Math.min(100, Math.round((item.soldCount / quota) * 100))
    const soldOut = item.remaining <= 0
    const limitReached = item.bought >= item.perCustomerLimit

    let state: SeckillState = 'running'
    if (item.status !== 2) {
      state = 'soon'
    } else if (soldOut) {
      state = 'soldout'
    } else if (limitReached) {
      state = 'limit'
    }
    this.setData({
      goodsId: item.goodsId,
      goodsName: item.goodsName,
      image: item.goodsImage || '',
      skuName: item.skuName,
      priceText: money(item.seckillPrice),
      originalText: money(item.originalPrice),
      remainingText: soldOut ? '已抢光' : `仅剩 ${item.remaining} 件`,
      progressText: `已抢 ${percent}%`,
      percent,
      limitText: `每人限购 ${item.perCustomerLimit} 件${item.bought > 0 ? `,你已购 ${item.bought} 件` : ''}`,
      startMs: new Date(item.startTime.replace(/-/g, '/')).getTime(),
      endMs: new Date(item.endTime.replace(/-/g, '/')).getTime(),
      state,
      buttonText: state === 'soon' ? '即将开抢' : state === 'soldout' ? '已抢光'
        : state === 'limit' ? '已达限购' : '立即抢购',
      disabled: state !== 'running',
    })
    this.tick()
  },

  tick() {
    const { state, startMs, endMs } = this.data
    if (state === 'soon') {
      this.setData({ countdownLabel: '距开始', countdownText: formatCountdown(startMs) })
      return
    }
    if (state !== 'running') {
      return
    }
    const text = formatCountdown(endMs)
    if (text === '00:00:00') {
      // 到点没被任务推进也要让用户看到结束:下一次 load 会把 status 刷新过来
      this.setData({ state: 'ended', buttonText: '已结束', disabled: true, countdownText: '' })
      return
    }
    this.setData({ countdownLabel: '距结束', countdownText: text })
  },

  onBuy() {
    if (this.data.disabled) {
      return
    }
    wx.navigateTo({ url: `/pages/seckill/checkout/index?itemId=${this.data.itemId}` })
  },

  onGoGoods() {
    if (this.data.goodsId) {
      wx.navigateTo({ url: `/pages/goods/detail/index?id=${this.data.goodsId}` })
    }
  },
})

function formatCountdown(targetMs: number): string {
  const diff = Math.max(0, targetMs - Date.now())
  const total = Math.floor(diff / 1000)
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const seconds = total % 60
  const pad = (value: number) => (value < 10 ? `0${value}` : `${value}`)
  return `${pad(hours)}:${pad(minutes)}:${pad(seconds)}`
}
