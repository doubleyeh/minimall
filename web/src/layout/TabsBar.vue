<template>
  <div class="tabs-bar">
    <n-tag
      v-for="tab in app.tabs"
      :key="tab.path"
      :type="tab.path === app.activeTab ? 'primary' : 'default'"
      :bordered="false"
      closable
      size="small"
      class="tabs-bar__item"
      @click="go(tab.path)"
      @close="close(tab.path)"
    >
      {{ tab.title }}
    </n-tag>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'

import { useAppStore } from '@/stores/app'

/** 已打开页面的快捷切换(前端文档 2 的 layout 组成之一)。 */
const app = useAppStore()
const router = useRouter()

function go(path: string): void {
  if (path !== app.activeTab) {
    void router.push(path)
  }
}

function close(path: string): void {
  const next = app.closeTab(path)
  if (next) {
    void router.push(next)
  }
}
</script>

<style scoped>
.tabs-bar {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 8px 16px;
  overflow-x: auto;
  border-bottom: 1px solid var(--n-border-color);
}

.tabs-bar__item {
  cursor: pointer;
  user-select: none;
}
</style>
