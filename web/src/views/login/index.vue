<template>
  <div class="login">
    <n-card class="login__card" title="多租户管理端">
      <n-space vertical :size="16">
        <n-alert v-if="errorMessage" type="error" :show-icon="true">{{ errorMessage }}</n-alert>

        <n-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-placement="top"
          @keyup.enter="onSubmit"
        >
          <n-form-item label="租户编码" path="tenantCode">
            <n-input v-model:value="form.tenantCode" placeholder="如 platform" />
          </n-form-item>
          <n-form-item label="用户名" path="username">
            <n-input v-model:value="form.username" placeholder="请输入用户名" />
          </n-form-item>
          <n-form-item label="密码" path="password">
            <n-input
              v-model:value="form.password"
              type="password"
              show-password-on="click"
              placeholder="请输入密码"
            />
          </n-form-item>
        </n-form>

        <n-button type="primary" block :loading="loading" @click="onSubmit">登录</n-button>

        <n-text depth="3" class="login__tip">
          登录失败的原因由服务端统一返回,前端不做区分;密码明文提交,不做任何前端加密。
        </n-text>
      </n-space>
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useUserStore } from '@/stores/user'
import type { FormInst, FormRules } from '@/types/naive'
import { getRememberedTenantCode, rememberTenantCode } from '@/utils/auth'

const router = useRouter()
const route = useRoute()
const user = useUserStore()

const formRef = ref<FormInst | null>(null)
const loading = ref(false)
const errorMessage = ref('')

const form = reactive({
  // 只记住租户编码(4.4):用户名与密码不落任何持久化
  tenantCode: getRememberedTenantCode() || import.meta.env.VITE_TENANT_CODE || '',
  username: '',
  password: '',
})

const rules: FormRules = {
  tenantCode: [{ required: true, message: '请输入租户编码', trigger: ['input', 'blur'] }],
  username: [{ required: true, message: '请输入用户名', trigger: ['input', 'blur'] }],
  password: [{ required: true, message: '请输入密码', trigger: ['input', 'blur'] }],
}

async function onSubmit(): Promise<void> {
  errorMessage.value = ''
  try {
    await formRef.value?.validate()
  } catch {
    return
  }

  loading.value = true
  try {
    await user.login({ ...form })
    rememberTenantCode(form.tenantCode)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : ''
    // 强制改密的跳转由守卫处理(4.5),这里只管走"登录成功后该去哪"
    await router.replace(redirect || '/')
  } catch (error) {
    // 文案由后端统一返回,前端原样展示,不解读"租户不存在/用户不存在/密码错误"(4.4)
    errorMessage.value = error instanceof Error ? error.message : '登录失败,请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100vh;
}

.login__card {
  width: 400px;
}

.login__tip {
  font-size: 12px;
  line-height: 1.6;
}
</style>
