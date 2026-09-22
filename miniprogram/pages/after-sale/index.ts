import {
  afterSaleArbitration,
  afterSaleCancel,
  afterSaleMine,
  afterSaleReturnLogistics,
} from '../../api/client'
import type { AfterSaleView, Id } from '../../types/client'
import { afterSaleTypeText, dateTime, money } from '../../utils/format'
import { confirmModal, toast, toastError, toastOk } from '../../utils/ui'

interface AfterSaleRow {
  id: Id
  afterSaleNo: string
  status: number
  statusText: string
  typeText: string
  refundAmountText: string
  applyReason: string
  rejectReason: string
  createTimeText: string
  goodsName: string
  skuName: string
  image: string
  quantity: number
}

Page({
  data: {
    list: [] as AfterSaleRow[],
    loading: false,
  },

  onShow() {
    void this.load()
  },

  async load() {
    if (this.data.loading) {
      return
    }
    this.setData({ loading: true })
    try {
      const list = await afterSaleMine(null)
      this.setData({ list: (list || []).map(toRow) })
    } catch (err) {
      toastError(err, '售后加载失败')
    } finally {
      this.setData({ loading: false })
    }
  },

  async onCancel(e: WechatMiniprogram.TouchEvent) {
    const id = String(e.currentTarget.dataset.id || '')
    if (!(await confirmModal('撤销后需要重新申请,确定吗?'))) {
      return
    }
    try {
      await afterSaleCancel(id)
      toastOk('已撤销')
      await this.load()
    } catch (err) {
      toastError(err)
    }
  },

  /**
   * 填写退货物流。
   *
   * 用两次可编辑弹窗收集快递公司与单号,而不是做一个表单页:
   * 只有两个短字段,为它多一个页面和一次跳转不划算。
   */
  async onReturnLogistics(e: WechatMiniprogram.TouchEvent) {
    const id = String(e.currentTarget.dataset.id || '')
    wx.showModal({
      title: '快递公司',
      editable: true,
      placeholderText: '例如:顺丰',
      success: (companyRes) => {
        if (!companyRes.confirm) {
          return
        }
        const company = (companyRes.content || '').trim()
        if (!company) {
          toast('请填写快递公司')
          return
        }
        wx.showModal({
          title: '快递单号',
          editable: true,
          placeholderText: '请填写运单号',
          success: (noRes) => {
            if (!noRes.confirm) {
              return
            }
            const no = (noRes.content || '').trim()
            if (!no) {
              toast('请填写快递单号')
              return
            }
            void this.submitReturnLogistics(id, company, no)
          },
        })
      },
    })
  },

  async submitReturnLogistics(id: Id, company: string, no: string) {
    try {
      await afterSaleReturnLogistics(id, company, no)
      toastOk('已提交')
      await this.load()
    } catch (err) {
      toastError(err)
    }
  },

  async onArbitration(e: WechatMiniprogram.TouchEvent) {
    const id = String(e.currentTarget.dataset.id || '')
    if (!(await confirmModal('申请平台介入后由平台裁决,确定提交吗?'))) {
      return
    }
    try {
      await afterSaleArbitration(id)
      toastOk('已申请平台介入')
      await this.load()
    } catch (err) {
      toastError(err)
    }
  },
})

function toRow(item: AfterSaleView): AfterSaleRow {
  return {
    id: item.id,
    afterSaleNo: item.afterSaleNo,
    status: item.status,
    statusText: item.statusText || '',
    typeText: afterSaleTypeText(item.afterSaleType),
    refundAmountText: money(item.refundAmount),
    applyReason: item.applyReason || '',
    rejectReason: item.rejectReason || '',
    createTimeText: dateTime(item.createTime),
    goodsName: item.item ? item.item.goodsName : '商品',
    skuName: item.item ? item.item.skuName : '',
    image: item.item ? item.item.goodsImage : '',
    quantity: item.item ? item.item.quantity : 0,
  }
}
