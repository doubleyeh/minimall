import type { Id } from '@/types/api'

/** 微信支付模式:direct-普通商户 partner-服务商 */
export type WxPayMode = 'direct' | 'partner'

/** 登录 code2Session 用哪套小程序凭据:app-服务商自己的 sub-特约商户自己的 */
export type WxPayLoginAppSource = 'app' | 'sub'

/** 租户微信支付配置视图。密钥类字段只回是否已配置,后端不回显任何密钥。 */
export interface WxPayConfigView {
  tenantId: Id
  tenantCode: string
  tenantName: string
  configured: boolean
  payMode: WxPayMode | null
  mchId: string | null
  subMchId: string | null
  appId: string | null
  subAppId: string | null
  loginAppSource: WxPayLoginAppSource | null
  merchantSerialNo: string | null
  platformSerialNo: string | null
  appSecretConfigured: boolean
  subAppSecretConfigured: boolean
  apiV3KeyConfigured: boolean
  merchantPrivateKeyConfigured: boolean
  platformPublicKeyConfigured: boolean
  status: number | null
  remark: string | null
  updateTime: string | null
}

/**
 * 保存租户微信支付配置。
 *
 * 密钥类字段留空表示"保持原值",所以编辑时不用重填;创建时这些字段必填(由后端校验)。
 */
export interface WxPayConfigSaveRequest {
  tenantId?: Id
  payMode: WxPayMode
  mchId: string
  subMchId?: string | null
  appId: string
  appSecret?: string
  subAppId?: string | null
  subAppSecret?: string
  loginAppSource?: WxPayLoginAppSource | null
  apiV3Key?: string
  merchantSerialNo: string
  merchantPrivateKey?: string
  platformSerialNo?: string | null
  platformPublicKey?: string
  status?: number
  remark?: string | null
}
