<template>
  <n-layout has-sider class="layout">
    <n-layout-sider
      bordered
      collapse-mode="width"
      :collapsed-width="64"
      :width="220"
      :collapsed="app.sidebarCollapsed"
      show-trigger
      @collapse="app.sidebarCollapsed = true"
      @expand="app.sidebarCollapsed = false"
    >
      <div class="layout__logo" :class="{ 'layout__logo--collapsed': app.sidebarCollapsed }">
        <span class="layout__mark" aria-hidden="true">MM</span>
        <span v-if="!app.sidebarCollapsed" class="layout__brand">多租户管理端</span>
      </div>
      <SideMenu />
    </n-layout-sider>

    <n-layout>
      <n-layout-header bordered class="layout__header">
        <n-space align="center" :size="12">
          <n-button quaternary circle @click="app.toggleSidebar()">
            <template #icon><n-icon :component="MenuOutline" /></template>
          </n-button>
          <n-text strong class="layout__title">{{ currentTitle }}</n-text>
        </n-space>

        <n-space align="center" :size="12">
          <ThemeSwitcher />
          <n-tag v-if="user.isSuperUser" size="small" type="warning" :bordered="false">
            平台超管
          </n-tag>
          <n-text depth="3" class="layout__user">{{ user.profile?.nickname || '-' }}</n-text>
          <n-dropdown :options="userOptions" @select="onUserAction">
            <n-button quaternary>
              <template #icon><n-icon :component="PersonCircleOutline" /></template>
              账号
            </n-button>
          </n-dropdown>
        </n-space>
      </n-layout-header>

      <TabsBar />

      <n-layout-content class="layout__content">
        <router-view />
      </n-layout-content>
    </n-layout>
  </n-layout>
</template>

<script setup lang="ts">
import { MenuOutline, PersonCircleOutline } from '@vicons/ionicons5'
import { useDialog } from 'naive-ui'
import { computed, onBeforeUnmount, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { resetBusinessRoutes } from '@/router/routes'
import { useAppStore } from '@/stores/app'
import { useUserStore } from '@/stores/user'
import type { DropdownOption } from 'naive-ui'
import SideMenu from './SideMenu.vue'
import TabsBar from './TabsBar.vue'
import ThemeSwitcher from './ThemeSwitcher.vue'

const route = useRoute()
const router = useRouter()
const app = useAppStore()
const user = useUserStore()
const dialog = useDialog()

const currentTitle = computed(() => String(route.meta.title ?? ''))

let contentEl: HTMLElement | null = null

const CONTENT_SELECTOR = '.layout__content'
/** 列表页表格的最大高度:按内容区实际可用高算。 */
const TABLE_HEIGHT_VAR = '--mm-table-max-h'

function syncTableHeight(): void {
  const content = contentEl
  if (!content) {
    return
  }
  const tables = content.querySelectorAll<HTMLElement>('.n-data-table')
  if (tables.length === 0) {
    document.documentElement.style.removeProperty(TABLE_HEIGHT_VAR)
    return
  }
  const limit = content.getBoundingClientRect().bottom - (parseFloat(getComputedStyle(content).paddingBottom) || 0)
  let available = Number.POSITIVE_INFINITY
  for (const table of tables) {
    const scroller = table.querySelector<HTMLElement>('.n-scrollbar-container')
    const card = table.closest<HTMLElement>('.n-card')
    const tableRect = table.getBoundingClientRect()
    // 表内除表体以外的部分(表头、内边距、与分页的间距)与表下的卡片留白,都实测出来
    const outsideBody = tableRect.height - (scroller?.clientHeight ?? 0)
    const belowTable = card ? card.getBoundingClientRect().bottom - tableRect.bottom : 0
    available = Math.min(available, limit - tableRect.top - outsideBody - belowTable)
  }
  document.documentElement.style.setProperty(TABLE_HEIGHT_VAR, `${Math.max(160, Math.round(available))}px`)
}

let syncFrame = 0
let contentObserver: MutationObserver | null = null

function scheduleSync(): void {
  if (syncFrame) {
    return
  }
  syncFrame = requestAnimationFrame(() => {
    syncFrame = 0
    syncTableHeight()
  })
}

onMounted(() => {
  contentEl = document.querySelector<HTMLElement>(CONTENT_SELECTOR)
  scheduleSync()
  window.addEventListener('resize', scheduleSync)
  if (contentEl) {
    contentObserver = new MutationObserver(scheduleSync)
    contentObserver.observe(contentEl, { childList: true, subtree: true })
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', scheduleSync)
  contentObserver?.disconnect()
  if (syncFrame) {
    cancelAnimationFrame(syncFrame)
  }
})

const userOptions: DropdownOption[] = [
  { label: '修改密码', key: 'password' },
  { label: '退出登录', key: 'logout' },
]

watch(
  () => route.path,
  () => {
    const title = String(route.meta.title ?? '')
    if (title) {
      app.openTab({ path: route.path, title })
    }
  },
  { immediate: true },
)

function onUserAction(key: string): void {
  if (key === 'password') {
    void router.push('/change-password')
    return
  }
  if (key === 'logout') {
    dialog.warning({
      title: '退出登录',
      content: '确认退出当前账号?',
      positiveText: '退出',
      negativeText: '取消',
      onPositiveClick: async () => {
        await user.logout()
        // 动态路由必须移除:否则换账号后仍能访问上一个账号的页面(前端文档 5.2 第 3 条)
        resetBusinessRoutes()
        app.resetTabs()
        await router.replace('/login')
      },
    })
  }
}
</script>

<style scoped>
/* 颜色一律走 --mm-*,并为切换加过渡 */
.layout {
  height: 100vh;
}

.layout__logo {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 56px;
  padding: 0 16px;
  color: var(--mm-sidebar-text);
}

/* 折叠时只剩标记,居中才不显得偏 */
.layout__logo--collapsed {
  justify-content: center;
  padding: 0;
}

/* 品牌标记 */
.layout__mark {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.02em;
  color: var(--mm-brand-text);
  background: var(--mm-brand-mark);
  border-radius: 8px;
}

.layout__brand {
  overflow: hidden;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.02em;
  white-space: nowrap;
}

.layout__logo,
.layout__header,
.layout__content {
  transition:
    background-color 0.28s ease,
    border-color 0.28s ease,
    color 0.28s ease;
}

.layout__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 56px;
  padding: 0 16px;
}

/* 页面标题 */
.layout__title {
  letter-spacing: -0.01em;
}

.layout__user {
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.layout__content {
  height: calc(100vh - 56px - 41px);
  padding: 18px;
  overflow: auto;
}
</style>
