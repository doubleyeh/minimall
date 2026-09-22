/**
 * 小程序冒烟:连上开发者工具的自动化端口,把**每个页面**真跑一遍。
 *
 * 为什么必须有它:这个工程没有单元测试可跑。小程序代码的正确性几乎全在运行时 ——
 * WXML 里绑的字段名是否存在、wx.request 的请求头是否合后端要求、静默登录有没有真的拿到令牌、
 * 页面 data 里的展示字段是否算全、页面注册与路由是否对得上 —— 这些 `tsc --noEmit` 一律查不出来
 * (类型对、结构对,运行时才炸)。
 *
 * 前置两步:
 *   1) 开发者工具开启自动化端口(会自己启动 IDE 并编译):
 *      "D:\soft\微信web开发者工具\cli.bat" auto --project <本目录> --trust-project --auto-port 9420
 *      **要等它编译完**再连:紧接着连会报 Failed connecting 或 getPageMetaByWebviewId is null
 *   2) 后端已启动(默认 http://127.0.0.1:8080),否则所有请求都会失败
 *
 * 用法:npm run smoke
 */
const path = require('path')
const automator = require('miniprogram-automator')

const WS_ENDPOINT = process.env.WX_AUTO_WS || 'ws://127.0.0.1:9420'

/** 等页面渲染与请求收尾;小程序没有 networkidle,只能给一个够用的静默期 */
const SETTLE_MS = 2500

/**
 * 要走的页面。
 *
 * tab 页只能用 switchTab 且**不能带参数**,所以它们单独标出来;
 * 其余页面用 navigateTo + navigateBack,避免页面栈越走越深(上限 10 层)。
 *
 * 带参数的页面用"看起来合法但大概率不存在"的 id:目的是验证**页面本身能渲染**,
 * 拿不到数据时应当优雅降级(显示错误文案),而不是白屏或抛未捕获异常 ——
 * 这恰好是只有真跑才能验证的一类行为。
 */
const PAGES = [
  { label: '首页', url: '/pages/home/index', tab: true },
  { label: '购物车', url: '/pages/cart/index', tab: true },
  { label: '订单列表', url: '/pages/orders/index', tab: true },
  { label: '我的', url: '/pages/profile/index', tab: true },
  { label: '商品详情', url: '/pages/goods/detail/index?id=1' },
  { label: '结算', url: '/pages/checkout/index' },
  { label: '订单详情', url: '/pages/order-detail/index?id=1' },
  { label: '优惠券', url: '/pages/coupons/index' },
  { label: '收货地址', url: '/pages/address/index' },
  { label: '我的售后', url: '/pages/after-sale/index' },
  { label: '售后申请', url: '/pages/after-sale/apply/index?orderItemId=1&amount=10.00' },
  { label: '发表评价', url: '/pages/review/create/index?orderItemId=1' },
]

/** 从页面 data 里挑出"能说明这个页面干了什么"的字段 */
function summarize(data) {
  if (!data) {
    return '(无 data)'
  }
  const parts = []
  if (data.loadFailed) parts.push(`降级:${data.loadFailed}`)
  const listKeys = ['cards', 'rows', 'lines', 'orders', 'list', 'addresses', 'coupons', 'reviews', 'types']
  listKeys.forEach((key) => {
    const value = data[key]
    if (Array.isArray(value)) parts.push(`${key}=${value.length}`)
  })
  if (typeof data.totalCount === 'number' && data.totalCount > 0) parts.push(`件数=${data.totalCount}`)
  if (data.statusText) parts.push(`状态=${data.statusText}`)
  if (data.nickname) parts.push(`昵称=${data.nickname}`)
  if (typeof data.rating === 'number' && Array.isArray(data.starList)) parts.push(`评分=${data.rating}`)
  return parts.length > 0 ? parts.join(' ') : '(无可识别字段)'
}

async function main() {
  console.log(`连接自动化端口 ${WS_ENDPOINT} …`)
  const miniProgram = await automator.connect({ wsEndpoint: WS_ENDPOINT })

  const issues = []
  const exceptions = []
  miniProgram.on('console', (msg) => {
    if (msg.type === 'error') {
      issues.push(`[error] ${msg.args.join(' ')}`)
    }
  })
  miniProgram.on('exception', (err) => {
    exceptions.push(err.message)
  })

  const tokenText = await miniProgram.evaluate(() => {
    const token = wx.getStorageSync('mall_token')
    return typeof token === 'string' && token.length > 0 ? `长度 ${token.length}` : '(空)'
  })
  console.log(`静默登录写入的令牌: ${tokenText}`)

  console.log(`\n${'页面'.padEnd(12)}${'结果'.padEnd(6)}页面 data`)
  console.log('-'.repeat(100))
  let failures = 0

  for (const item of PAGES) {
    const before = exceptions.length
    try {
      // tab 页只能用 switchTab(且不能带参数),其余用 reLaunch:
      // automator 0.12 的 navigateTo 在这套 IDE(基础库 3.17.3)上会抛 "Uncaught [object Object]",
      // 而 reLaunch 既能打开任意页面,又不会让页面栈越走越深
      const handle = item.tab ? await miniProgram.switchTab(item.url) : await miniProgram.reLaunch(item.url)
      await handle.waitFor(SETTLE_MS)

      const current = await miniProgram.currentPage()
      // currentPage().path 不带前导斜杠(形如 pages/xx/index),比较前先归一,
      // 否则会全部误判成"不符"
      const expected = item.url.split('?')[0].replace(/^\//, '')
      const onExpectedPage = !!current && current.path === expected
      // data() 只能在 currentPage 上取:reLaunch/switchTab 返回的句柄不一定是栈顶那个页面
      const data = await current.data()

      if (!onExpectedPage) {
        failures++
      }
      console.log(
        `${item.label.padEnd(14)}${(onExpectedPage ? 'OK' : `不符(${current ? current.path : '空'})`).padEnd(6)}` +
          `${summarize(data)}   异常+${exceptions.length - before}`,
      )

    } catch (err) {
      failures++
      // automator 抛的常常是包装过的对象而不是 Error,直接取 message 只会得到 "undefined"
      const detail = err && err.message ? err.message : JSON.stringify(err)
      console.log(`${item.label.padEnd(14)}${'失败'.padEnd(6)}${detail}`)
    }
  }

  console.log('\n================ 汇总 ================')
  console.log(`页面失败: ${failures} / ${PAGES.length}`)
  console.log(`控制台 error: ${issues.length} 条`)
  issues.forEach((line) => console.log(`  ${line.slice(0, 300)}`))
  console.log(`未捕获异常: ${exceptions.length} 条`)
  exceptions.forEach((line) => console.log(`  ${line.slice(0, 400)}`))

  // 截首页与商品详情:这两页承载了绝大部分视觉设计,改配色与布局后要靠它们判断效果
  const home = await miniProgram.switchTab('/pages/home/index')
  await home.waitFor(SETTLE_MS)
  const homeShot = path.join(__dirname, '..', 'smoke-home.png')
  await miniProgram.screenshot({ path: homeShot })
  console.log(`\n截图·首页: ${homeShot}`)

  const homeData = await home.data()
  const firstGoodsId = Array.isArray(homeData.cards) && homeData.cards.length > 0 ? homeData.cards[0].id : null
  if (firstGoodsId) {
    const detail = await miniProgram.reLaunch(`/pages/goods/detail/index?id=${firstGoodsId}`)
    await detail.waitFor(SETTLE_MS + 1500)
    const detailShot = path.join(__dirname, '..', 'smoke-detail.png')
    await miniProgram.screenshot({ path: detailShot })
    console.log(`截图·详情: ${detailShot}`)
  } else {
    console.log('首页没有商品,跳过详情页截图')
  }

  await miniProgram.disconnect()
  if (failures > 0 || exceptions.length > 0 || issues.length > 0) {
    process.exit(1)
  }
}

main().catch((err) => {
  console.error('冒烟失败:', err && err.message ? err.message : err)
  process.exit(1)
})
