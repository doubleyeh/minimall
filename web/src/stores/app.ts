import { defineStore } from 'pinia'
import { ref } from 'vue'

/** 框架层状态:侧边栏折叠、标签页(前端文档 2 的 layout 组成)。 */

export interface TabItem {
  path: string
  title: string
}

export const useAppStore = defineStore('app', () => {
  const sidebarCollapsed = ref(false)
  const tabs = ref<TabItem[]>([])
  const activeTab = ref('')

  function toggleSidebar(): void {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }

  function openTab(tab: TabItem): void {
    activeTab.value = tab.path
    if (!tabs.value.some((item) => item.path === tab.path)) {
      tabs.value.push(tab)
    }
  }

  /** 关闭标签页,返回"关闭后应该跳到哪个路径"(关的是当前页时用相邻标签) */
  function closeTab(path: string): string | null {
    const index = tabs.value.findIndex((item) => item.path === path)
    if (index < 0) {
      return null
    }
    tabs.value.splice(index, 1)
    if (activeTab.value !== path) {
      return null
    }
    const neighbour = tabs.value[index] ?? tabs.value[index - 1]
    return neighbour ? neighbour.path : null
  }

  function resetTabs(): void {
    tabs.value = []
    activeTab.value = ''
  }

  return { sidebarCollapsed, tabs, activeTab, toggleSidebar, openTab, closeTab, resetTabs }
})
