import type { WxPayParams } from '../api/client'

/**
 * 拉起微信支付。
 *
 * **字段名不一样,不能直接透传**:后端返回的是 `packageValue`,而 `wx.requestPayment`
 * 要求 `package`(后端的 record 组件名避开了这个保留字,所以两边叫法不同)。
 * 直接把后端的对象传进 `wx.requestPayment` 会报参数错误,而报错信息里不会提到字段名对不上 ——
 * 这个映射放在这里做一次,免得每个调用点各写一遍、各错一遍。
 *
 * 用户主动取消支付时 `fail` 也会触发(errMsg 含 `cancel`),调用方需要按此区分
 * "取消"与"真的失败" —— 取消不该弹错误提示。
 */
export function requestPayment(params: WxPayParams): Promise<void> {
  return new Promise<void>((resolve, reject) => {
    wx.requestPayment({
      timeStamp: params.timeStamp,
      nonceStr: params.nonceStr,
      package: params.packageValue,
      signType: params.signType as 'MD5' | 'HMAC-SHA256' | 'RSA',
      paySign: params.paySign,
      success: () => resolve(),
      fail: (err) => reject(new Error(err.errMsg)),
    })
  })
}

/** 用户主动取消支付(而不是支付失败)。 */
export function isPayCancelled(err: unknown): boolean {
  return err instanceof Error && err.message.indexOf('cancel') >= 0
}
