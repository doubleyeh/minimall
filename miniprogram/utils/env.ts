/**
 * 环境配置。
 *
 * 与 H5 版的关键差别:H5 用相对路径请求,靠 Vite 的 dev proxy 同源转发;
 * 小程序里 `wx.request` **必须绝对 URL**,而且线上要求 HTTPS 且域名已在微信公众平台配置。
 * 所以后端地址必须显式配置,不能像 H5 那样留空。
 */

/**
 * 用微信自己的环境标识切环境,不引 `process.env` —— 小程序运行时没有 Node 的环境变量。
 *
 * 取值:`develop`(开发者工具/真机调试)、`trial`(体验版)、`release`(正式版)。
 */
function resolveEnvVersion(): string {
  try {
    return wx.getAccountInfoSync().miniProgram.envVersion
  } catch {
    // 极少数上下文(例如纯 Node 下跑类型检查)拿不到账号信息,按开发环境处理
    return 'develop'
  }
}

const ENV_VERSION = resolveEnvVersion()

export const IS_RELEASE = ENV_VERSION === 'release' || ENV_VERSION === 'trial'

/**
 * 后端地址。
 *
 * - develop:**真机调试时 `127.0.0.1` 指的是手机自己**,必须换成电脑的局域网地址,
 *   例如 `http://192.168.1.10:8080`;开发者工具里则用 `127.0.0.1` 即可。
 * - trial / release:必须 HTTPS,且域名要加进公众平台的「开发设置 → 服务器域名」。
 *   这里的占位地址在发布前必须替换。
 */
export const API_BASE_URL = IS_RELEASE ? 'https://api.example.com' : 'http://127.0.0.1:8080'

/**
 * 租户编码。
 *
 * 微信 openid 是按小程序发放的,所以**每个商家的版本对应一个租户**,这个值由构建/发布环节确定。
 * 不放在运行时让用户输入 —— H5 联调版那份用手动输入,是因为浏览器里没有微信环境,而这里有。
 */
export const TENANT_CODE = 'platform'

/** 请求超时(毫秒)。与后端单次处理时长匹配,不要调太大:超时后用户看到的是白屏,不是错误。 */
export const REQUEST_TIMEOUT = 15000
