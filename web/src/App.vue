<template>
  <!--
    Provider 嵌套顺序见前端文档 1.2:缺层会让对应的 useXxx() 抛错、页面白屏。
    locale / dateLocale 必须传,否则日期选择器、分页器、表格空状态都会显示英文(不能靠"文案是中文"代替)。
  -->
  <n-config-provider :locale="zhCN" :date-locale="dateZhCN" :theme-overrides="themeOverrides">
    <n-loading-bar-provider>
      <n-dialog-provider>
        <n-message-provider>
          <n-notification-provider>
            <n-global-style />
            <router-view />
          </n-notification-provider>
        </n-message-provider>
      </n-dialog-provider>
    </n-loading-bar-provider>
  </n-config-provider>
</template>

<script setup lang="ts">
import { dateZhCN, zhCN } from 'naive-ui'

import { themeOverrides } from '@/theme'
</script>
