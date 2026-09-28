import { pointsLogs } from '../../api/client'
import type { PointsLogView } from '../../types/client'
import { dateTime } from '../../utils/format'
import { toastError } from '../../utils/ui'

/**
 * 积分明细。
 *
 * 分页写法照订单列表(见 pages/orders/index.ts):`onReachBottom` 追加下一页,
 * `load(pageNo, replace)` 一个方法管首次与追加,避免两处写两套。
 *
 * 变动的**原因文案来自服务端**(`bizTypeText`):同一条流水在小程序与管理端都要展示,
 * 两边各写一份映射必然会有一边忘了加新类型。
 */

/** 列表行:正负号与展示文案都在这里定好,WXML 里不算数 */
interface PointsRow {
  id: string
  reason: string
  remark: string
  time: string
  /** 形如 "+100" / "-30",带符号 */
  changeText: string
  isGain: boolean
  balanceText: string
}

const PAGE_SIZE = 20

Page({
  data: {
    rows: [] as PointsRow[],
    pageNo: 1,
    hasMore: false,
    loading: true,
  },

  onLoad() {
    void this.load(1, true)
  },

  onReachBottom() {
    void this.loadMore()
  },

  async loadMore() {
    if (!this.data.hasMore || this.data.loading) {
      return
    }
    await this.load(this.data.pageNo + 1, false)
  },

  async load(pageNo: number, replace: boolean) {
    if (this.data.loading && !replace) {
      return
    }
    this.setData({ loading: true })
    try {
      const result = await pointsLogs(pageNo, PAGE_SIZE)
      const rows = (result.list || []).map(toRow)
      const merged = replace ? rows : this.data.rows.concat(rows)
      this.setData({ rows: merged, pageNo, hasMore: merged.length < result.total })
    } catch (err) {
      toastError(err, '积分明细加载失败')
    } finally {
      this.setData({ loading: false })
    }
  },
})

function toRow(item: PointsLogView): PointsRow {
  const isGain = item.changePoints >= 0
  return {
    id: item.id,
    reason: item.bizTypeText,
    remark: item.remark || '',
    time: dateTime(item.createTime),
    changeText: `${isGain ? '+' : ''}${item.changePoints}`,
    isGain,
    balanceText: `余额 ${item.balancePoints}`,
  }
}
