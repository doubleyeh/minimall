<template>
  <n-result status="404" title="页面不存在" description="地址可能已失效,或者你没有该页面的权限">
    <template #footer>
      <n-button type="primary" @click="router.push('/')">回首页</n-button>
    </template>
  </n-result>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>
