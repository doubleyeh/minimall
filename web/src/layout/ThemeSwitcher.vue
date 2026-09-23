<template>
  <n-dropdown :options="options" trigger="click" placement="bottom-end" @select="onSelect">
    <n-button
      class="theme-switcher"
      quaternary
      :circle="compact"
      :title="`${current.label} — ${current.hint}`"
    >
      <template #icon>
        <n-icon :component="ColorPaletteOutline" />
      </template>
      <template v-if="!compact">{{ current.label }}</template>
    </n-button>
  </n-dropdown>
</template>

<script setup lang="ts">
import { ColorPaletteOutline, CheckmarkOutline } from '@vicons/ionicons5'
import { NIcon } from 'naive-ui'
import { computed, h } from 'vue'

import { THEMES, THEME_KEYS, currentTheme, setTheme, themeKey, type ThemeKey } from '@/theme'
import type { DropdownOption } from '@/types/naive'

/** 主题切换器。layout 与登录页都用它。下拉面板 teleport 到 body,样式写成内联避免掉样式。 */
// compact:登录页用,按钮不带文字
withDefaults(defineProps<{ compact?: boolean }>(), { compact: false })

const current = computed(() => currentTheme.value)

function optionLabel(key: ThemeKey) {
  const theme = THEMES[key]
  const active = themeKey.value === key
  return () =>
    h(
      'span',
      { style: { display: 'flex', alignItems: 'center', gap: '10px', minWidth: '116px' } },
      [
        h('span', {
          style: {
            flex: 'none',
            width: '13px',
            height: '13px',
            borderRadius: '3px',
            background: theme.cssVars['--mm-accent'],
            // 描边:浅色主题的近黑色块在弹层底色上才看得出边界
            boxShadow: 'inset 0 0 0 1px rgba(140, 140, 140, 0.4)',
          },
        }),
        h('span', { style: { flex: '1' } }, theme.label),
        active ? h(NIcon, { size: 15 }, { default: () => h(CheckmarkOutline) }) : null,
      ],
    )
}

const options = computed<DropdownOption[]>(() =>
  THEME_KEYS.map((key) => ({ key, label: optionLabel(key) })),
)

function onSelect(key: string | number): void {
  if (typeof key === 'string' && key in THEMES) {
    setTheme(key as ThemeKey)
  }
}
</script>
