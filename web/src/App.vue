<template>
  <!-- Provider 嵌套顺序见前端文档 1.2;configProviderProps 与 discrete.ts 共用 -->
  <n-config-provider v-bind="configProviderProps">
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
import { configProviderProps } from '@/theme'
</script>
