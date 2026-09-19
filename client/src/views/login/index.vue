<template>
  <div class="page login">
    <div class="hero">
      <div class="logo">商城</div>
      <div class="tip">微信一键登录,随时查看订单与优惠</div>
    </div>

    <div class="card">
      <!--
        真机上这里不需要输入框:code 由 wx.login() 静默取得。
        H5/本地联调时没有微信环境,所以提供手动输入,后端在 mall.auth.wx-mock=true 下
        会把 code 直接映射成模拟 openid(因此输入任意内容都能登录进同一个账号)。
      -->
      <t-cell-group>
        <t-cell title="租户编码" :note="tenantCode" />
      </t-cell-group>
      <div class="field">
        <t-input v-model="code" label="登录凭证" placeholder="真机由 wx.login 自动获取,本地可随意填写" />
      </div>
      <t-button block theme="primary" :loading="submitting" @click="onLogin">微信登录</t-button>
      <div class="hint">
        本地开发环境后端开启了微信登录模拟,输入任意凭证即可登录;生产环境必须关闭该开关。
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { wxLogin } from '@/api/client'
import { getTenantCode, setToken } from '@/utils/auth'
import { ApiError } from '@/utils/request'

const route = useRoute()
const router = useRouter()

const tenantCode = getTenantCode()
const code = ref('mock-code-' + Date.now())
const submitting = ref(false)

async function onLogin(): Promise<void> {
  if (!code.value.trim()) {
    return
  }
  submitting.value = true
  try {
    const result = await wxLogin(code.value.trim())
    setToken(result.token)
    // 回到用户原本想去的页面(守卫里带了 redirect);没有就进首页
    const redirect = route.query.redirect
    await router.replace(typeof redirect === 'string' && redirect ? redirect : '/')
  } catch (error) {
    // 错误文案直接来自后端(登录失败不会区分"租户不存在/账号不可用",见后端 3.1)
    const message = error instanceof ApiError ? error.message : '登录失败,请重试'
    // 这里用 alert 而不是 toast:登录失败是页面级结果,toast 容易一闪而过
    window.alert(message)
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.login {
  background: var(--mall-page-bg);
}

.hero {
  padding: 64px 24px 32px;
  text-align: center;
}

.logo {
  font-size: 28px;
  font-weight: 700;
  letter-spacing: 4px;
}

.tip {
  margin-top: 12px;
  color: #999;
  font-size: 13px;
}

.field {
  margin: 16px 0;
}

.hint {
  margin-top: 12px;
  color: #999;
  font-size: 12px;
  line-height: 1.6;
}
</style>
