import { computed, ref, watchEffect } from 'vue'
import { darkTheme, dateZhCN, zhCN } from 'naive-ui'

import type { GlobalThemeOverrides } from '@/types/naive'

/** 三套主题的配置。`configProviderProps` 是唯一出口,页面与离散 API 都取它。 */
export type ThemeKey = 'blue' | 'light' | 'dark'

interface ThemeDefinition {
  key: ThemeKey
  label: string
  /** 下拉里的提示 */
  hint: string
  /** naive 的暗色基础主题;浅色传 null */
  naiveTheme: typeof darkTheme | null
  overrides: GlobalThemeOverrides
  /** 自定义 CSS 变量,写到 <html> 上供 global.css 用 */
  cssVars: Record<string, string>
}

/** 侧边栏配色:同时喂 Layout(容器底色)与 Menu(条目文字图标) */
function sidebarTokens(bg: string, text: string, activeBg: string, activeText: string) {
  return {
    Layout: {
      siderColor: bg,
      siderBorderColor: 'transparent',
    },
    Menu: {
      itemTextColor: text,
      itemIconColor: text,
      // 折叠态图标走独立的令牌,不配就会退回全局 textColor,在深色侧边栏上看不见
      itemIconColorCollapsed: text,
      itemTextColorHover: activeText,
      itemIconColorHover: activeText,
      itemTextColorActive: activeText,
      itemIconColorActive: activeText,
      itemTextColorChildActive: activeText,
      itemIconColorChildActive: activeText,
      itemTextColorActiveHover: activeText,
      itemIconColorActiveHover: activeText,
      itemColorActive: activeBg,
      itemColorActiveHover: activeBg,
      itemColorHover: activeBg,
      arrowColor: text,
      borderRadius: '6px',
    },
  } satisfies GlobalThemeOverrides
}

const blue: ThemeDefinition = {
  key: 'blue',
  label: '蓝色调',
  hint: '冷色主调,深靛蓝侧栏',
  naiveTheme: null,
  overrides: {
    common: {
      primaryColor: '#2A5BD7',
      primaryColorHover: '#4A78E8',
      primaryColorPressed: '#1E45AC',
      primaryColorSuppl: '#8FAEF5',
      infoColor: '#2A5BD7',
      borderRadius: '6px',
      borderRadiusSmall: '4px',
      textColor1: '#16202E',
      textColor2: '#45536A',
      textColor3: '#8A97AC',
      bodyColor: '#F5F7FC',
      cardColor: '#FFFFFF',
      modalColor: '#FFFFFF',
      popoverColor: '#FFFFFF',
      tableColor: '#FFFFFF',
      tableHeaderColor: '#F2F5FB',
      inputColor: '#FFFFFF',
      borderColor: '#E3E8F2',
      dividerColor: '#EDF1F8',
      hoverColor: '#EDF2FE',
    },
    ...sidebarTokens('#16224A', '#C7D2EA', '#243A78', '#FFFFFF'),
    Card: { borderRadius: '10px', borderColor: '#E8EDF6' },
    Button: { borderRadiusMedium: '6px' },
    DataTable: {
      borderColor: '#E8EDF6',
      thColor: '#F2F5FB',
      thTextColor: '#6B7A93',
      tdColorHover: '#F5F8FE',
    },
  },
  cssVars: {
    '--mm-page-bg': '#F5F7FC',
    '--mm-page-glow': 'rgba(42, 91, 215, 0.10)',
    '--mm-surface': '#FFFFFF',
    '--mm-border': '#E3E8F2',
    '--mm-text-1': '#16202E',
    '--mm-text-2': '#45536A',
    '--mm-text-3': '#8A97AC',
    '--mm-accent': '#2A5BD7',
    '--mm-accent-weak': 'rgba(42, 91, 215, 0.12)',
    '--mm-sidebar-bg': '#16224A',
    '--mm-sidebar-text': '#C7D2EA',
    '--mm-brand-mark': 'linear-gradient(135deg, #2A5BD7 0%, #6E8CF5 100%)',
    '--mm-brand-text': '#FFFFFF',
    '--mm-brand-line': 'rgba(255, 255, 255, 0.07)',
    '--mm-brand-weak': 'rgba(255, 255, 255, 0.16)',
    '--mm-shadow-1': '0 1px 2px rgba(22, 32, 46, 0.06)',
    '--mm-shadow-2': '0 8px 24px -12px rgba(22, 32, 46, 0.18)',
    '--mm-radius': '10px',
    // 卡片描边与阴影(naive 没有卡片阴影令牌)
    '--mm-card-border': '#E3E8F2',
    '--mm-card-shadow': '0 1px 2px rgba(22, 32, 46, 0.05)',
  },
}

const light: ThemeDefinition = {
  key: 'light',
  label: '白色',
  hint: '近白底 + 墨黑主色,靠留白撑层次',
  naiveTheme: null,
  overrides: {
    common: {
      primaryColor: '#17181C',
      primaryColorHover: '#33353B',
      primaryColorPressed: '#000000',
      primaryColorSuppl: '#9A9CA3',
      infoColor: '#3F4249',
      borderRadius: '4px',
      borderRadiusSmall: '3px',
      textColor1: '#14161A',
      textColor2: '#4A4D55',
      textColor3: '#8C8F97',
      bodyColor: '#F7F7F6',
      cardColor: '#FFFFFF',
      modalColor: '#FFFFFF',
      popoverColor: '#FFFFFF',
      tableColor: '#FFFFFF',
      tableHeaderColor: '#FAFAF9',
      inputColor: '#FFFFFF',
      borderColor: '#E6E6E3',
      dividerColor: '#EFEFEC',
      hoverColor: '#F1F1EF',
    },
    ...sidebarTokens('#FFFFFF', '#5A5D65', '#EFEFEC', '#14161A'),
    Card: { borderRadius: '6px', borderColor: '#E6E6E3' },
    Button: { borderRadiusMedium: '4px' },
    DataTable: {
      borderColor: '#E6E6E3',
      thColor: 'transparent',
      thTextColor: '#8C8F97',
      tdColorHover: '#FAFAF9',
    },
  },
  cssVars: {
    '--mm-page-bg': '#F7F7F6',
    '--mm-page-glow': 'rgba(20, 22, 26, 0.05)',
    '--mm-surface': '#FFFFFF',
    '--mm-border': '#E6E6E3',
    '--mm-text-1': '#14161A',
    '--mm-text-2': '#4A4D55',
    '--mm-text-3': '#8C8F97',
    '--mm-accent': '#17181C',
    '--mm-accent-weak': 'rgba(23, 24, 28, 0.08)',
    '--mm-sidebar-bg': '#FFFFFF',
    '--mm-sidebar-text': '#5A5D65',
    // 品牌区也随主题变浅:这套叫"白色",不该有一块近黑的装饰
    '--mm-brand-mark': 'linear-gradient(135deg, #F4F4F1 0%, #E2E2DE 100%)',
    '--mm-brand-text': '#14161A',
    '--mm-brand-line': 'rgba(20, 22, 26, 0.05)',
    '--mm-brand-weak': 'rgba(20, 22, 26, 0.06)',
    '--mm-shadow-1': '0 1px 2px rgba(20, 22, 26, 0.05)',
    '--mm-shadow-2': '0 8px 24px -14px rgba(20, 22, 26, 0.16)',
    '--mm-radius': '6px',
    // 这套不用阴影与色块,只留一条发丝线分层
    '--mm-card-border': '#E6E6E3',
    '--mm-card-shadow': 'none',
  },
}

const dark: ThemeDefinition = {
  key: 'dark',
  label: '暗色',
  hint: '石墨底 + 亮蓝主色',
  naiveTheme: darkTheme,
  overrides: {
    common: {
      primaryColor: '#4D8DFF',
      primaryColorHover: '#6BA1FF',
      primaryColorPressed: '#3A72D8',
      primaryColorSuppl: '#2A4C8F',
      infoColor: '#4D8DFF',
      borderRadius: '6px',
      borderRadiusSmall: '4px',
      textColor1: '#E8EBF1',
      // 暗色下次级/三级文字要比浅色主题更亮,否则在深底上看不清
      textColor2: '#B9C2CF',
      textColor3: '#8E97A6',
      bodyColor: '#12141A',
      cardColor: '#1A1D24',
      modalColor: '#1A1D24',
      popoverColor: '#1F232B',
      tableColor: '#1A1D24',
      tableHeaderColor: '#1F232B',
      inputColor: '#1F232B',
      borderColor: '#2A2F3A',
      dividerColor: '#232833',
      hoverColor: '#222833',
    },
    ...sidebarTokens('#171A21', '#B9C2CF', '#243044', '#FFFFFF'),
    Card: { borderRadius: '10px', borderColor: 'transparent' },
    // naive 暗色下算出的表单标签色偏暗,显式指定
    Form: { labelTextColor: '#C6CEDA' },
    Button: { borderRadiusMedium: '6px' },
    DataTable: {
      borderColor: '#262B35',
      thColor: '#1F232B',
      thTextColor: '#8E97A6',
      tdColorHover: '#222833',
    },
  },
  cssVars: {
    '--mm-page-bg': '#12141A',
    '--mm-page-glow': 'rgba(77, 141, 255, 0.14)',
    '--mm-surface': '#1A1D24',
    '--mm-border': '#2A2F3A',
    '--mm-text-1': '#E8EBF1',
    '--mm-text-2': '#B9C2CF',
    '--mm-text-3': '#8E97A6',
    '--mm-accent': '#4D8DFF',
    '--mm-accent-weak': 'rgba(77, 141, 255, 0.16)',
    '--mm-sidebar-bg': '#171A21',
    '--mm-sidebar-text': '#B9C2CF',
    '--mm-brand-mark': 'linear-gradient(135deg, #4D8DFF 0%, #7B6CF6 100%)',
    '--mm-brand-text': '#FFFFFF',
    '--mm-brand-line': 'rgba(255, 255, 255, 0.09)',
    '--mm-brand-weak': 'rgba(255, 255, 255, 0.16)',
    '--mm-shadow-1': '0 1px 2px rgba(0, 0, 0, 0.4)',
    '--mm-shadow-2': '0 10px 28px -14px rgba(0, 0, 0, 0.7)',
    '--mm-radius': '10px',
    // 暗色不用描边,靠更高的表面与阴影分层
    '--mm-card-border': 'transparent',
    '--mm-card-shadow': '0 1px 2px rgba(0, 0, 0, 0.45)',
  },
}

export const THEMES: Record<ThemeKey, ThemeDefinition> = { blue, light, dark }

/** 下拉里的顺序,以及给界面用的元数据 */
export const THEME_KEYS: ThemeKey[] = ['blue', 'light', 'dark']

const STORAGE_KEY = 'minimall.admin.theme'
const FALLBACK: ThemeKey = 'blue'

function isThemeKey(value: unknown): value is ThemeKey {
  return typeof value === 'string' && value in THEMES
}

function readStored(): ThemeKey {
  try {
    const stored = window.localStorage.getItem(STORAGE_KEY)
    return isThemeKey(stored) ? stored : FALLBACK
  } catch {
    // 隐私模式/禁用存储时 localStorage 会抛,不能让它把整个应用带崩
    return FALLBACK
  }
}

/** 当前主题。用模块级 ref 而非 pinia:discrete.ts 在 setup 之外求值时 pinia 还没装。 */
export const themeKey = ref<ThemeKey>(readStored())

export function setTheme(key: ThemeKey): void {
  themeKey.value = key
}

export const currentTheme = computed(() => THEMES[themeKey.value])

/** 主题配置的唯一出口:n-config-provider 与 createDiscreteApi 共用 */
export const configProviderProps = computed(() => ({
  theme: currentTheme.value.naiveTheme,
  themeOverrides: currentTheme.value.overrides,
  locale: zhCN,
  dateLocale: dateZhCN,
}))

/** 把主题写到 <html>:自定义变量、data-theme、原生控件的明暗 */
watchEffect(() => {
  const theme = currentTheme.value
  const root = document.documentElement
  root.dataset.theme = theme.key
  root.style.colorScheme = theme.naiveTheme ? 'dark' : 'light'
  for (const [name, value] of Object.entries(theme.cssVars)) {
    root.style.setProperty(name, value)
  }
  try {
    window.localStorage.setItem(STORAGE_KEY, theme.key)
  } catch {
    // 存不了就退化成刷新后回默认主题
  }
})
