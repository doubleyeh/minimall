<template>
  <div class="login">
    <aside class="login__brand">
      <div class="login__brand-inner">
        <span class="login__mark">MM</span>
        <h1 class="login__headline">多租户商城<br />管理后台</h1>
        <p class="login__sub">一个后台管理多个租户的商品、订单与营销</p>
        <ul class="login__points">
          <li>租户、套餐与权限隔离</li>
          <li>商品 / 订单 / 售后 / 营销全链路</li>
          <li>小程序端与后台共用同一套接口</li>
        </ul>
      </div>
    </aside>

    <main class="login__panel">
      <!-- 登录页也要能切主题 -->
      <div class="login__theme">
        <ThemeSwitcher />
      </div>

      <n-card class="login__card" :bordered="false">
        <h2 class="login__title">登录</h2>
        <p class="login__hint">租户编码 + 用户名</p>

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

          <n-button type="primary" block size="large" :loading="loading" @click="onSubmit">
            登录
          </n-button>

          <n-text depth="3" class="login__tip">
            登录失败的原因由服务端统一返回,前端不做区分;密码明文提交,不做任何前端加密。
          </n-text>
        </n-space>
      </n-card>
    </main>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import ThemeSwitcher from '@/layout/ThemeSwitcher.vue'
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
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(0, 1fr);
  height: 100vh;
}

/* ---------------- 左侧品牌区 ---------------- */

.login__brand {
  position: relative;
  display: flex;
  align-items: center;
  padding: 0 clamp(32px, 6vw, 88px);
  overflow: hidden;
  color: var(--mm-brand-text);
  background: var(--mm-brand-mark);
}

/* 斜向细纹 */
.login__brand::after {
  position: absolute;
  inset: 0;
  content: '';
  background-image: repeating-linear-gradient(
    -52deg,
    var(--mm-brand-line) 0 1px,
    transparent 1px 22px
  );
}

.login__brand-inner {
  position: relative;
  z-index: 1;
  max-width: 26em;
}

.login__mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  font-size: 16px;
  font-weight: 700;
  letter-spacing: 0.04em;
  background: var(--mm-brand-weak);
  border: 1px solid var(--mm-brand-line);
  border-radius: 12px;
}

.login__headline {
  margin: 24px 0 0;
  font-size: clamp(28px, 3.1vw, 40px);
  font-weight: 700;
  line-height: 1.24;
  letter-spacing: -0.02em;
}

.login__sub {
  margin: 14px 0 0;
  font-size: 15px;
  line-height: 1.7;
  /* 用 opacity,文字色随主题 */
  opacity: 0.78;
}

.login__points {
  padding: 0;
  margin: 32px 0 0;
  font-size: 14px;
  opacity: 0.88;
  list-style: none;
}

.login__points li {
  position: relative;
  padding-left: 18px;
  margin-bottom: 10px;
}

.login__points li::before {
  position: absolute;
  top: 0.62em;
  left: 0;
  width: 6px;
  height: 6px;
  content: '';
  background: currentColor;
  border-radius: 2px;
  opacity: 0.6;
}

/* ---------------- 右侧表单区 ---------------- */

.login__panel {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px;
}

.login__theme {
  position: absolute;
  top: 20px;
  right: 24px;
}

.login__card {
  width: 100%;
  max-width: 384px;
  box-shadow: var(--mm-shadow-2);
}

.login__title {
  margin: 4px 0 0;
  font-size: 22px;
  font-weight: 650;
  letter-spacing: -0.01em;
}

.login__hint {
  margin: 6px 0 22px;
  font-size: 13px;
  color: var(--mm-text-3);
}

.login__tip {
  font-size: 12px;
  line-height: 1.6;
}

/* 窄屏收起品牌区 */
@media (max-width: 900px) {
  .login {
    grid-template-columns: minmax(0, 1fr);
  }

  .login__brand {
    display: none;
  }
}
</style>
