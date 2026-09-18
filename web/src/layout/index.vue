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
      <div class="layout__logo">{{ app.sidebarCollapsed ? 'MM' : '多租户管理端' }}</div>
      <SideMenu />
    </n-layout-sider>

    <n-layout>
      <n-layout-header bordered class="layout__header">
        <n-space align="center" :size="12">
          <n-button quaternary circle @click="app.toggleSidebar()">
            <template #icon><n-icon :component="MenuOutline" /></template>
          </n-button>
          <n-text strong>{{ currentTitle }}</n-text>
        </n-space>

        <n-space align="center" :size="12">
          <n-tag v-if="user.isSuperUser" size="small" type="warning" :bordered="false">
            平台超管
          </n-tag>
          <n-text depth="3">{{ user.profile?.nickname || '-' }}</n-text>
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
import { computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { resetBusinessRoutes } from '@/router/routes'
import { useAppStore } from '@/stores/app'
import { useUserStore } from '@/stores/user'
import type { DropdownOption } from 'naive-ui'
import SideMenu from './SideMenu.vue'
import TabsBar from './TabsBar.vue'

const route = useRoute()
const router = useRouter()
const app = useAppStore()
const user = useUserStore()
const dialog = useDialog()

const currentTitle = computed(() => String(route.meta.title ?? ''))

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
.layout {
  height: 100vh;
}

.layout__logo {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 56px;
  font-weight: 600;
  letter-spacing: 1px;
}

.layout__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 56px;
  padding: 0 16px;
}

.layout__content {
  height: calc(100vh - 56px - 41px);
  padding: 16px;
  overflow: auto;
}
</style>
