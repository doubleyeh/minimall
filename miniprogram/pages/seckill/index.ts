import { seckillHome } from '../../api/client'
import type { ClientSeckillItemView } from '../../types/client'
import { money } from '../../utils/format'
import { toastError } from '../../utils/ui'

/** 一行展示所需的一切:WXML 里不能算数,能算的全在 TS 侧算好。 */
interface SeckillRow {
  itemId: string
  goodsName: string
  image: string
  priceText: string
  originalText: string
  progressText: string
  percent: number
  countdownText: string
  soldOut: boolean
  startMs: number
  endMs: number
}

function toRow(item: ClientSeckillItemView): SeckillRow {
  const quota = item.totalQuota || 1
  const percent = Math.min(100, Math.round((item.soldCount / quota) * 100))
  return {
    itemId: item.itemId,
    goodsName: item.goodsName,
    image: item.goodsImage || '',
    priceText: money(item.seckillPrice),
    originalText: money(item.originalPrice),
    progressText: `已抢 ${percent}%`,
    percent,
    countdownText: '',
    soldOut: item.remaining <= 0,
    startMs: new Date(item.startTime.replace(/-/g, '/')).getTime(),
    endMs: new Date(item.endTime.replace(/-/g, '/')).getTime(),
  }
}

function countdown(targetMs: number): string {
  const diff = Math.max(0, targetMs - Date.now())
  const total = Math.floor(diff / 1000)
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const seconds = total % 60
  const pad = (value: number) => (value < 10 ? `0${value}` : `${value}`)
  return `${pad(hours)}:${pad(minutes)}:${pad(seconds)}`
}

Page({
  data: {
    running: [] as SeckillRow[],
    upcoming: [] as SeckillRow[],
    loading: false,
  },

  timer: 0,

  onShow() {
    void this.load()
    this.startTick()
  },

  onHide() {
    this.stopTick()
  },

  onUnload() {
    this.stopTick()
  },

  async load() {
    if (this.data.loading) {
      return
    }
    this.setData({ loading: true })
    try {
      const home = await seckillHome()
      this.setData({
        running: (home.running || []).map(toRow),
        upcoming: (home.upcoming || []).map(toRow),
      })
      this.tick()
    } catch (err) {
      toastError(err, '秒杀加载失败')
    } finally {
      this.setData({ loading: false })
    }
  },

  startTick() {
    this.stopTick()
    this.timer = setInterval(() => this.tick(), 1000) as unknown as number
  },

  stopTick() {
    if (this.timer) {
      clearInterval(this.timer)
      this.timer = 0
    }
  },

  /** 每秒只刷新倒计时文案,不重新拉数据 —— 拉数据会把刷新压力打到数据库上。 */
  tick() {
    const running = this.data.running.map((row) => ({
      ...row,
      countdownText: row.soldOut ? '已抢光' : `距结束 ${countdown(row.endMs)}`,
    }))
    const upcoming = this.data.upcoming.map((row) => ({
      ...row,
      countdownText: `距开始 ${countdown(row.startMs)}`,
    }))
    this.setData({ running, upcoming })
  },

  onItemTap(e: WechatMiniprogram.TouchEvent) {
    const itemId = String(e.currentTarget.dataset.id || '')
    if (!itemId) {
      return
    }
    wx.navigateTo({ url: `/pages/seckill/detail/index?itemId=${itemId}` })
  },
})
