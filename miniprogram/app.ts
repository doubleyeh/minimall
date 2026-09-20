import { ensureLogin } from './utils/auth'

/**
 * 小程序入口。
 *
 * 与 H5 版最直观的差别:**没有登录页**。
 * 客户登录用 `wx.login()` 静默取 code(见 mall_architecture.md 3.1),启动时就把令牌备好,
 * 各页面因此不需要处理「未登录」这一态,也不需要 `redirect` 参数。
 *
 * 这里不 await 登录结果:页面加载不该等它,真拿不到令牌时请求层会自己再登一次。
 */
App({
  onLaunch() {
    ensureLogin().catch((err: Error) => {
      // 不往上抛:后端没起来时,页面各自的请求会给出更具体的错误,这里只留一条线索
      console.warn('启动时静默登录失败,请求层会在需要时重试:', err.message)
    })
  },
})
