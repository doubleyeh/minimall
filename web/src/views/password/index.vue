<template>
  <div class="password">
    <n-card class="password__card">
      <template #header>
        <n-space align="center" :size="8">
          <n-icon :component="KeyOutline" />
          <span>{{ forced ? '请先修改初始密码' : '修改密码' }}</span>
        </n-space>
      </template>

      <n-alert v-if="forced" type="warning" class="password__alert">
        当前账号使用的是初始密码,修改成功前无法访问其他页面。
      </n-alert>

      <n-form ref="formRef" :model="form" :rules="rules" label-placement="top">
        <n-form-item label="原密码" path="oldPassword">
          <n-input v-model:value="form.oldPassword" type="password" show-password-on="click" />
        </n-form-item>
        <n-form-item label="新密码" path="newPassword">
          <n-input v-model:value="form.newPassword" type="password" show-password-on="click" />
        </n-form-item>
        <n-form-item label="确认新密码" path="confirmPassword">
          <n-input v-model:value="form.confirmPassword" type="password" show-password-on="click" />
        </n-form-item>
      </n-form>

      <n-space justify="end">
        <n-button v-if="!forced" @click="router.back()">取消</n-button>
        <n-button type="primary" :loading="loading" @click="onSubmit">确认修改</n-button>
      </n-space>
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { KeyOutline } from '@vicons/ionicons5'
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import { changePassword } from '@/api/auth'
import { useUserStore } from '@/stores/user'
import type { FormInst, FormRules } from '@/types/naive'
import { message } from '@/utils/discrete'

/**
 * 修改密码(前端文档 4.5)。
 *
 * 两条硬规则:
 * 1. 强制改密期间只有 `/auth/password`、`/auth/logout`、`/auth/permissions` 可用,所以这一页必须是静态路由;
 * 2. 改密成功后后端会清掉该用户全部会话,前端必须清空令牌并回登录页 —— 继续用旧令牌只会到处 401。
 */
const router = useRouter()
const user = useUserStore()

const forced = computed(() => user.profile?.mustChangePassword ?? false)
const formRef = ref<FormInst | null>(null)
const loading = ref(false)

const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

const rules: FormRules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: ['input', 'blur'] }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: ['input', 'blur'] },
    { min: 8, max: 32, message: '密码长度需为 8-32 位', trigger: ['input', 'blur'] },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: ['input', 'blur'] },
    {
      validator: (_rule, value: string) => value === form.newPassword,
      message: '两次输入的密码不一致',
      trigger: ['input', 'blur'],
    },
  ],
}

async function onSubmit(): Promise<void> {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }

  loading.value = true
  try {
    await changePassword({ oldPassword: form.oldPassword, newPassword: form.newPassword })
    // 会话已全部失效:清本地状态 + 移除动态路由,再回登录页(4.5)
    user.reset()
    message.success('密码已修改,请使用新密码重新登录')
    await router.replace('/login')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.password {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100vh;
}

.password__card {
  width: 440px;
}

.password__alert {
  margin-bottom: 16px;
}
</style>
