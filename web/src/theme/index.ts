import type { GlobalThemeOverrides } from '@/types/naive'

/**
 * 主题配置(前端文档 3.2)。
 *
 * <p>**必须只有这一份**:`n-config-provider` 与拦截器用的 `createDiscreteApi(configProviderProps)`
 * 都要用它。两处各写一份的话,弹出来的错误提示会和页面主题不一致 —— 这种问题肉眼能看出来,
 * 但没人会想到是"配置写了两份"。
 */
export const themeOverrides: GlobalThemeOverrides = {
  common: {
    primaryColor: '#2080f0',
    primaryColorHover: '#4098fc',
    primaryColorPressed: '#1060c9',
    borderRadius: '4px',
  },
}
