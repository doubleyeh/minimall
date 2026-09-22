/**
 * 提示与确认。
 *
 * 统一在这里做三件事,避免每个页面各写一遍、各漏一处:
 * 1. 业务错误的文案直接用后端的 `message` —— 后端给的就是可展示的中文
 *    (例如「库存不足,仅剩 2 件」),前端再包一层只会丢掉信息;
 * 2. 非业务错误(网络失败、类型错误)给一句兜底,而不是把 `err.message` 直接甩给用户;
 * 3. 确认弹窗返回 Promise<boolean>,写成 `if (await confirmModal(...))` 比
 *    `wx.showModal({ success })` 嵌套更不容易漏掉取消分支。
 */

export function toastError(err: unknown, fallback = '操作失败'): void {
  const message = err instanceof Error && err.message ? err.message : fallback
  wx.showToast({ title: message, icon: 'none' })
}

export function toastOk(title: string): void {
  wx.showToast({ title, icon: 'success' })
}

export function toast(title: string): void {
  wx.showToast({ title, icon: 'none' })
}

export function confirmModal(content: string, title = '提示'): Promise<boolean> {
  return new Promise((resolve) => {
    wx.showModal({
      title,
      content,
      success: (res) => resolve(!!res.confirm),
      // 弹窗都可能失败(例如页面已经销毁),失败按"取消"处理,不要让它变成未捕获异常
      fail: () => resolve(false),
    })
  })
}

/** 短震动反馈。用于"点加号但库存不够"这类需要即时反馈但不值得弹窗的场景。 */
export function buzz(): void {
  wx.vibrateShort({ type: 'light', fail: () => undefined })
}
