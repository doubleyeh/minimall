<template>
  <n-result status="403" title="没有访问权限" description="如果确认应该能访问,请重新登录后再试">
    <template #footer>
      <n-space justify="center">
        <n-button @click="router.push('/')">回首页</n-button>
        <n-button type="primary" @click="reload">重新加载权限</n-button>
      </n-space>
    </template>
  </n-result>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'

import { resetBusinessRoutes } from '@/router/routes'
import { message } from '@/utils/discrete'
import { usePermissionStore } from '@/stores/permission'

/**
 * 403 页(前端文档 5.4):先刷新一次权限快照并重建菜单,仍无权限就引导重新登录。
 * 后端返回 403 时不清理令牌、不跳登录页 —— 403 是"已登录但没权限",和 401 不是一回事。
 */
const router = useRouter()
const permission = usePermissionStore()

async function reload(): Promise<void> {
  try {
    await permission.reload()
    resetBusinessRoutes()
    message.success('权限快照已刷新,若仍无权限请联系管理员')
    await router.replace('/')
  } catch {
    message.error('刷新权限失败,请重新登录')
    await router.replace('/login')
  }
}
</script>
