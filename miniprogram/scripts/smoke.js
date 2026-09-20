/**
 * 小程序冒烟:连上开发者工具的自动化端口,真跑一遍首页。
 *
 * 为什么必须有它:这个工程没有单元测试可跑。小程序代码的正确性几乎全在运行时 ——
 * WXML 里绑的字段名是否存在、wx.request 的请求头是否合后端要求、静默登录有没有真的拿到令牌、
 * 页面 data 里的展示字段是否算全了 —— 这些 `tsc --noEmit` 一律查不出来
 * (类型对、结构对,运行时才炸)。
 *
 * 前置两步:
 *   1) 开发者工具开启自动化端口(会自己启动 IDE):
 *      "D:\soft\微信web开发者工具\cli.bat" auto --project <本目录> --trust-project --auto-port 9420
 *   2) 后端已启动(默认 http://127.0.0.1:8080),否则所有请求都会失败
 *
 * 用法:npm run smoke
 */
const automator = require('miniprogram-automator')

const WS_ENDPOINT = process.env.WX_AUTO_WS || 'ws://127.0.0.1:9420'
const HOME_PAGE = '/pages/home/index'

/** 等页面渲染与请求收尾;小程序没有 networkidle,只能给一个够用的静默期 */
const SETTLE_MS = 3000

async function main() {
  console.log(`连接自动化端口 ${WS_ENDPOINT} …`)
  const miniProgram = await automator.connect({ wsEndpoint: WS_ENDPOINT })

  const consoleIssues = []
  const exceptions = []
  miniProgram.on('console', (msg) => {
    if (msg.type === 'error' || msg.type === 'warn') {
      consoleIssues.push(`[${msg.type}] ${msg.args.join(' ')}`)
    }
  })
  miniProgram.on('exception', (err) => {
    exceptions.push(err.message)
  })

  console.log(`打开 ${HOME_PAGE} …`)
  const page = await miniProgram.reLaunch(HOME_PAGE)
  await page.waitFor(SETTLE_MS)

  const data = await page.data()
  const cards = Array.isArray(data.cards) ? data.cards : []
  const tabs = Array.isArray(data.tabs) ? data.tabs : []

  const tokenText = await miniProgram.evaluate(() => {
    const token = wx.getStorageSync('mall_token')
    return typeof token === 'string' && token.length > 0 ? `长度 ${token.length}` : '(空)'
  })

  console.log('\n================ 结果 ================')
  console.log(`静默登录写入的令牌: ${tokenText}`)
  console.log(`分类数量: ${tabs.length}`)
  console.log(`商品卡片数: ${cards.length}`)
  console.log(`列表状态: loading=${data.loading} finished=${data.finished} pageNo=${data.pageNo}`)
  if (cards.length > 0) {
    console.log(`第一张卡片: ${JSON.stringify(cards[0])}`)
  }

  console.log(`\n控制台 error/warn: ${consoleIssues.length} 条`)
  consoleIssues.forEach((line) => console.log(`  ${line}`))
  console.log(`未捕获异常: ${exceptions.length} 条`)
  exceptions.forEach((line) => console.log(`  ${line}`))

  const shot = require('path').join(__dirname, '..', 'smoke-home.png')
  await miniProgram.screenshot({ path: shot })
  console.log(`\n截图: ${shot}`)

  await miniProgram.disconnect()
}

main().catch((err) => {
  console.error('冒烟失败:', err && err.message ? err.message : err)
  process.exit(1)
})
