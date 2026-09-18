/// <reference types="vite/client" />

/**
 * 环境变量声明(见前端文档 7)。新增变量必须在这里补类型,
 * 否则 strict 模式下读到的都是隐式 any,拼错变量名也不会报错。
 */
interface ImportMetaEnv {
  /** 后端地址:dev 留空(走 Vite proxy),prod 按站点填 */
  readonly VITE_API_BASE_URL: string
  /** 仅用于白名单外的公开接口(见 3.3),禁止作为全局默认头 */
  readonly VITE_TENANT_CODE: string
  /** 仅 dev:proxy 转发的后端地址 */
  readonly VITE_PROXY_TARGET?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
