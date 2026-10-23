import {
  addressList,
  createSeckillOrder,
  mockPaySuccess,
  orderPrepay,
  seckillDetail,
  seckillPreview,
} from '../../../api/client'
import type { AddressView, Id } from '../../../types/client'
import { ensureLogin } from '../../../utils/auth'
import { IS_RELEASE } from '../../../utils/env'
import { money } from '../../../utils/format'
import { takePendingCheckoutAddress } from '../../../utils/nav'
import { isPayCancelled, requestPayment } from '../../../utils/pay'
import { toast, toastError } from '../../../utils/ui'

/**
 * 秒杀结算(商城设计文档 3.12)。
 *
 * 与普通结算同一条原则:**页面上不出现任何金额运算**。实付、运费、商品金额全部来自
 * `POST /mall/api/seckills/{itemId}/preview`;端上自己乘一遍,漂移的方向永远是"用户看到的更便宜"。
 *
 * 秒杀没有优惠券与积分入口 —— 不是隐藏,而是后端接口本身就没有这两个字段。
 */
Page({
  data: {
    itemId: '',
    quantity: 1,
    maxQuantity: 1,
    address: null as AddressView | null,
    addressText: '',
    goodsName: '',
    image: '',
    skuName: '',
    priceText: '0.00',
    goodsAmountText: '0.00',
    freightText: '0.00',
    payAmountText: '0.00',
    loading: true,
    submitting: false,
  },

  /**
   * 幂等键:同一次结算意图复用同一个值。
   *
   * 放在页面实例上而不是每次现生成:用户连点两次"提交",两次请求带的是同一个键,
   * 服务端第二次直接回放第一次的响应,不会下出两笔单。
   */
  idempotencyKey: '',

  onLoad(query: Record<string, string | undefined>) {
    this.setData({ itemId: query.itemId || '' })
    this.idempotencyKey = `seckill-${query.itemId || ''}-${Date.now()}-${Math.floor(Math.random() * 100000)}`
  },

  onShow() {
    const picked = takePendingCheckoutAddress()
    if (picked) {
      this.setData({ address: picked, addressText: formatAddress(picked) })
    }
    void this.bootstrap()
  },

  async bootstrap() {
    try {
      await ensureLogin()
    } catch (err) {
      toastError(err, '请先登录')
      this.setData({ loading: false })
      return
    }
    if (!this.data.address) {
      await this.loadDefaultAddress()
    }
    await this.loadItem()
    await this.refresh()
  },

  async loadDefaultAddress() {
    try {
      const addresses = await addressList()
      const address = addresses.find((item) => item.isDefault === 1) ?? addresses[0] ?? null
      this.setData({ address, addressText: address ? formatAddress(address) : '' })
    } catch (err) {
      toastError(err, '地址加载失败')
    }
  },

  async loadItem() {
    try {
      const item = await seckillDetail(this.data.itemId)
      const maxQuantity = Math.max(1, Math.min(item.perCustomerLimit - item.bought, item.remaining))
      this.setData({
        goodsName: item.goodsName,
        image: item.goodsImage || '',
        skuName: item.skuName,
        priceText: money(item.seckillPrice),
        maxQuantity: item.remaining > 0 ? maxQuantity : 1,
        quantity: Math.min(this.data.quantity, item.remaining > 0 ? maxQuantity : 1),
      })
    } catch (err) {
      toastError(err, '秒杀商品加载失败')
    }
  },

  async refresh() {
    try {
      const preview = await seckillPreview(this.data.itemId, {
        addressId: this.data.address ? this.data.address.id : null,
        quantity: this.data.quantity,
      })
      this.setData({
        goodsName: preview.goodsName,
        image: preview.goodsImage,
        skuName: preview.skuName,
        priceText: money(preview.seckillPrice),
        goodsAmountText: money(preview.goodsAmount),
        freightText: money(preview.freightAmount),
        payAmountText: money(preview.payAmount),
      })
    } catch (err) {
      toastError(err, '金额计算失败')
    } finally {
      this.setData({ loading: false })
    }
  },

  onQuantityMinus() {
    if (this.data.quantity <= 1) {
      return
    }
    this.setData({ quantity: this.data.quantity - 1 })
    void this.refresh()
  },

  onQuantityPlus() {
    if (this.data.quantity >= this.data.maxQuantity) {
      toast('已达可购上限')
      return
    }
    this.setData({ quantity: this.data.quantity + 1 })
    void this.refresh()
  },

  onPickAddress() {
    wx.navigateTo({ url: '/pages/address/index?select=1' })
  },

  async onSubmit() {
    if (this.data.submitting) {
      return
    }
    const address = this.data.address
    if (!address) {
      toast('请先选择收货地址')
      return
    }
    this.setData({ submitting: true })
    try {
      const order = await createSeckillOrder(
        this.data.itemId,
        { addressId: address.id, quantity: this.data.quantity },
        this.idempotencyKey,
      )
      await this.payThenGoToOrder(order.orderId, order.orderNo, order.payAmount)
    } catch (err) {
      toastError(err, '抢购失败')
    } finally {
      this.setData({ submitting: false })
    }
  },

  /** 不管支付成不成都要离开本页:订单已经建出来了,留在结算页只会让人再点一次。 */
  async payThenGoToOrder(orderId: Id, orderNo: string, payAmount: number) {
    if (payAmount > 0) {
      try {
        const payParams = await orderPrepay(orderId)
        if (!IS_RELEASE) {
          await mockPaySuccess(orderNo, payAmount)
          toast('支付成功(本地为模拟)')
        } else {
          try {
            await requestPayment(payParams)
          } catch (err) {
            if (!isPayCancelled(err)) {
              toast('支付未完成,可在订单列表继续支付')
            }
          }
        }
      } catch (err) {
        toastError(err, '发起支付失败,可在订单列表继续支付')
      }
    } else {
      toast('0 元秒杀,订单已自动完成支付')
    }
    wx.redirectTo({ url: `/pages/order-detail/index?id=${orderId}` })
  },
})

function formatAddress(address: AddressView): string {
  return `${address.province}${address.city}${address.district}${address.detailAddress}`
}
