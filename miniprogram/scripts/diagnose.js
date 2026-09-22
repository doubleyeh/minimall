/**
 * 诊断:只打开首页,把控制台**全部**输出、未捕获异常、页面 data 与截图一次性抓出来。
 *
 * 与 smoke.js 的区别:冒烟是"跑一遍看有没有坏",这个是"坏了之后看坏在哪",
 * 所以它不过滤日志级别、只走一个页面、并保留截图 —— 白屏这类问题的信息
 * 往往在 IDE 的编译输出里,而不是在页面 data 里。
 *
 * 用法:node scripts/diagnose.js
 */
const path = require('path')
const automator = require('miniprogram-automator')

const WS_ENDPOINT = process.env.WX_AUTO_WS || 'ws://127.0.0.1:9420'

async function main() {
  const miniProgram = await automator.connect({ wsEndpoint: WS_ENDPOINT })

  const logs = []
  const exceptions = []
  miniProgram.on('console', (msg) => {
    logs.push(`[${msg.type}] ${msg.args.join(' ')}`)
  })
  miniProgram.on('exception', (err) => {
    exceptions.push(err.message)
  })

  console.log('当前页面:', JSON.stringify(await miniProgram.currentPage().then((p) => (p ? p.path : null))))

  const page = await miniProgram.reLaunch('/pages/home/index')
  await page.waitFor(3000)

  const current = await miniProgram.currentPage()
  console.log('reLaunch 后页面:', current ? current.path : '(空)')
  console.log('页面 data:', JSON.stringify(await page.data(), null, 2).slice(0, 1500))

  const token = await miniProgram.evaluate(() => {
    const value = wx.getStorageSync('mall_token')
    return typeof value === 'string' && value.length > 0 ? `长度 ${value.length}` : '(空)'
  })
  console.log('本地令牌:', token)

  console.log('\n================ 控制台全部输出 ================')
  if (logs.length === 0) {
    console.log('(没有任何输出)')
  }
  logs.forEach((line) => console.log(line.slice(0, 300)))

  console.log('\n================ 未捕获异常 ================')
  if (exceptions.length === 0) {
    console.log('(没有)')
  }
  exceptions.forEach((line) => console.log(line.slice(0, 400)))

  const shot = path.join(__dirname, '..', 'diagnose-home.png')
  await miniProgram.screenshot({ path: shot })
  console.log(`\n截图: ${shot}`)

  await miniProgram.disconnect()
}

main().catch((err) => {
  console.error('诊断失败:', err && err.message ? err.message : err)
  process.exit(1)
})
