import { addressList, createOrder, mockPaySuccess, myCoupons, orderPrepay, orderPreview } from '../../api/client'
import type { AddressView, ClientCouponView, Id, OrderPreviewView } from '../../types/client'
import { ensureLogin } from '../../utils/auth'
import { IS_RELEASE } from '../../utils/env'
import { couponValueText, money } from '../../utils/format'
import { takePendingCheckoutAddress } from '../../utils/nav'
import { isPayCancelled, requestPayment } from '../../utils/pay'
import { toast, toastError } from '../../utils/ui'

/**
 * 结算页(商城设计文档 3.5、3.11)。
 *
 * 两条进入路径共用这一个页面:购物车「去结算」(不带参数,后端取已勾选条目)
 * 与商品详情「立即购买」(带 skuId/quantity)。
 *
 * **页面上不出现任何金额运算**:五种金额、积分能抵多少、运费多少,全部取自
 * `POST /mall/api/orders/preview`。端上自己算一遍的后果不是"显示得不好看",
 * 而是"用户看到的价与实际实付对不上"—— 漂移的方向通常是少收钱。
 */

interface BuyItem {
  skuId: Id
  quantity: number
}

/** 金额明细的一行。WXML 里不能算数,所以显示什么、正负号都在这边定好。 */
interface AmountLine {
  label: string
  value: string
  /** true 表示这一行是减项,展示成 -¥x */
  minus: boolean
}

/** 商品行。价格同样要先格式化:WXML 里调不了函数。 */
interface ItemRow {
  skuId: Id
  goodsImage: string
  goodsName: string
  skuName: string
  priceText: string
  quantity: number
}

const COUPON_NONE_LABEL = '不使用优惠券'

Page({
  data: {
    /** 立即购买的商品;为 null 表示"结算购物车已勾选项"(与后端的约定一致) */
    items: null as BuyItem[] | null,
    address: null as AddressView | null,
    addressText: '',

    coupons: [] as ClientCouponView[],
    couponLabels: [COUPON_NONE_LABEL] as string[],
    couponIndex: 0,

    usePoints: false,
    /** 积分开关旁边那行说明:"可用 1200 积分,本单最多抵 12.00 元" */
    pointsHint: '暂无可用积分',

    remark: '',
    preview: null as OrderPreviewView | null,
    itemRows: [] as ItemRow[],
    amountLines: [] as AmountLine[],
    payAmountText: '0.00',
    totalQuantity: 0,

    loading: true,
    submitting: false,
  },

  /** 首次进入才拉地址与券:从地址页返回时 onShow 会再触发一次,不该重复拉 */
  initialized: false,

  onLoad(query: Record<string, string | undefined>) {
    const skuId = query.skuId
    if (skuId) {
      const quantity = Number(query.quantity || 1)
      this.setData({ items: [{ skuId, quantity: Number.isFinite(quantity) && quantity > 0 ? quantity : 1 }] })
    }
  },

  onShow() {
    // 从地址页选完回来:槽位里带着选中的那条地址
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
    if (!this.initialized) {
      await this.loadAddressAndCoupons()
      this.initialized = true
    }
    await this.refresh()
  },

  async loadAddressAndCoupons() {
    try {
      const [addresses, coupons] = await Promise.all([addressList(), myCoupons(1)])
      // 没选过就默认用默认地址;一条地址都没有时保持为空,由页面提示去新增
      const address = addresses.find((item) => item.isDefault === 1) ?? addresses[0] ?? null
      this.setData({
        address,
        addressText: address ? formatAddress(address) : '',
        coupons: coupons || [],
        couponLabels: [COUPON_NONE_LABEL].concat((coupons || []).map(couponValueText)),
      })
    } catch (err) {
      toastError(err, '结算信息加载失败')
    }
  },

  /**
   * 重新试算。
   *
   * 为什么可能请求两次:`pointsToUse` 要传"用多少分",而"最多能用多少分"只有服务端算得出来
   * (它取决于商品金额、满减、券与客户可用积分)。所以先不带积分算一次拿到 `maxRedeemPoints`,
   * 开了积分开关再带着它算第二次。
   *
   * **不在这边自己算上限** —— 那等于把规则抄一份到端上,两边迟早对不上。
   */
  async refresh() {
    this.setData({ loading: true })
    try {
      let preview: OrderPreviewView
      try {
        preview = await this.previewAt(this.currentCouponRecordId(), 0)
      } catch (err) {
        if (!this.currentCouponRecordId()) {
          throw err
        }
        // 券可能因为改过数量而不够门槛了:去掉券重算,并明确告诉用户(而不是丢一个报错)
        this.setData({ couponIndex: 0 })
        toast('所选优惠券已不可用,已取消使用')
        preview = await this.previewAt(null, 0)
      }

      if (this.data.usePoints && preview.maxRedeemPoints > 0) {
        preview = await this.previewAt(this.currentCouponRecordId(), preview.maxRedeemPoints)
      } else if (this.data.usePoints) {
        // 可用积分为 0:把开关拨回去,免得用户以为已经用上了
        this.setData({ usePoints: false })
      }
      this.applyPreview(preview)
    } catch (err) {
      toastError(err, '金额计算失败')
    } finally {
      this.setData({ loading: false })
    }
  },

  async previewAt(couponRecordId: Id | null, pointsToUse: number): Promise<OrderPreviewView> {
    return orderPreview({
      items: this.data.items ?? undefined,
      addressId: this.data.address ? this.data.address.id : null,
      couponRecordId,
      pointsToUse,
    })
  },

  currentCouponRecordId(): Id | null {
    if (this.data.couponIndex <= 0) {
      return null
    }
    const coupon = this.data.coupons[this.data.couponIndex - 1]
    return coupon && coupon.recordId ? coupon.recordId : null
  },

  applyPreview(preview: OrderPreviewView) {
    const lines: AmountLine[] = [{ label: '商品金额', value: money(preview.goodsAmount), minus: false }]
    if (preview.promotionDiscountAmount > 0) {
      lines.push({ label: '满减优惠', value: money(preview.promotionDiscountAmount), minus: true })
    }
    if (preview.couponDiscountAmount > 0) {
      lines.push({ label: '优惠券', value: money(preview.couponDiscountAmount), minus: true })
    }
    if (preview.pointsDiscountAmount > 0) {
      lines.push({ label: '积分抵扣', value: money(preview.pointsDiscountAmount), minus: true })
    }
    lines.push({ label: '运费', value: money(preview.freightAmount), minus: false })

    const totalQuantity = preview.items.reduce((sum, item) => sum + item.quantity, 0)
    const itemRows: ItemRow[] = preview.items.map((item) => ({
      skuId: item.skuId,
      goodsImage: item.goodsImage,
      goodsName: item.goodsName,
      skuName: item.skuName,
      priceText: money(item.price),
      quantity: item.quantity,
    }))
    this.setData({
      preview,
      itemRows,
      amountLines: lines,
      payAmountText: money(preview.payAmount),
      totalQuantity,
      // 用服务端给的 maxRedeemAmount,不在端上按 100:1 换算 —— 比例是结算规则的一部分
      pointsHint: preview.customerPoints > 0
        ? `可用 ${preview.customerPoints} 积分,本单最多抵 ¥${money(preview.maxRedeemAmount)}`
        : '暂无可用积分',
    })
  },

  onPickAddress() {
    wx.navigateTo({ url: '/pages/address/index?select=1' })
  },

  onCouponChange(e: WechatMiniprogram.PickerChange) {
    this.setData({ couponIndex: Number(e.detail.value) || 0 })
    void this.refresh()
  },

  onUsePointsChange(e: WechatMiniprogram.SwitchChange) {
    this.setData({ usePoints: e.detail.value })
    void this.refresh()
  },

  onRemarkInput(e: WechatMiniprogram.Input) {
    this.setData({ remark: e.detail.value })
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
    const preview = this.data.preview
    if (!preview) {
      toast('金额还没算好,请稍候')
      return
    }
    // 还没选地址时运费按 0 计,那个金额不是最终值,不能拿它下单
    if (preview.needAddress) {
      toast('请先选择收货地址')
      return
    }

    this.setData({ submitting: true })
    try {
      const order = await createOrder({
        items: this.data.items ?? undefined,
        addressId: address.id,
        couponRecordId: this.currentCouponRecordId(),
        // 传试算出来的实际用量(与页面上显示的一致),而不是端上另算一个
        pointsToUse: preview.pointsUsed,
        remark: this.data.remark.trim() || undefined,
      })
      await this.payThenGoToOrder(order.orderId, order.orderNo, order.payAmount)
    } catch (err) {
      toastError(err, '下单失败')
    } finally {
      this.setData({ submitting: false })
    }
  },

  /**
   * 下单后的支付与跳转。
   *
   * **不管支付成不成,最后都要离开结算页**:订单已经建出来了,把人留在结算页
   * 意味着他会再点一次"提交订单" —— 那才是真的重复下单。
   *
   * 三种情况分别处理:
   * ① 0 元订单 —— 后端 `prepay` 会拒绝("订单无需支付"),而"自动置为已支付"这条路径
   *    后端还没实现(既有缺口),所以只提示并把人送到订单详情;
   * ② 开发环境 —— 后端返回的是假支付参数,`wx.requestPayment` 必然失败,直接打模拟回调;
   * ③ 用户取消支付 —— 不是失败,不该弹错误提示,订单留在待支付即可。
   */
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
      // 后端在下单事务里就把 0 元订单置为待发货了(见 PayService#settleFreeOrder),
      // 所以这里不是"先不管",而是"它已经付完了"
      toast('全额优惠,订单已自动完成支付')
    }
    // 用 redirectTo:结算页不该留在栈里,否则返回键会回到一个已经下过单的页面
    wx.redirectTo({ url: `/pages/order-detail/index?id=${orderId}` })
  },
})

function formatAddress(address: AddressView): string {
  return `${address.province}${address.city}${address.district}${address.detailAddress}`
}
