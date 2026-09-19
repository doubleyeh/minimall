<template>
  <div class="page-with-tabbar">
    <router-view />
    <t-tab-bar v-model="active" :split="false" @change="onChange">
      <t-tab-bar-item value="home">首页</t-tab-bar-item>
      <t-tab-bar-item value="cart">购物车</t-tab-bar-item>
      <t-tab-bar-item value="orders">订单</t-tab-bar-item>
      <t-tab-bar-item value="profile">我的</t-tab-bar-item>
    </t-tab-bar>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

/**
 * 底部标签栏布局。
 *
 * 只包裹"四个主页面"(首页/购物车/订单/我的):商品详情、结算、售后申请这些页面
 * 都需要整屏空间,不套标签栏 —— 它们放在 router 的顶层路由里。
 */
const route = useRoute()
const router = useRouter()

/** 当前路由名 → 标签栏的值 */
const active = ref('home')

const routeNameToTab: Record<string, string> = {
  Home: 'home',
  Cart: 'cart',
  Orders: 'orders',
  Profile: 'profile',
}

watch(
  () => route.name,
  (name) => {
    active.value = routeNameToTab[String(name)] ?? 'home'
  },
  { immediate: true },
)

const tabToRoute: Record<string, string> = {
  home: '/',
  cart: '/cart',
  orders: '/orders',
  profile: '/profile',
}

function onChange(value: string | number): void {
  const path = tabToRoute[String(value)]
  if (path && path !== route.path) {
    void router.push(path)
  }
}

const currentPath = computed(() => route.path)
</script>

<style scoped>
/* tabbar 固定底部;内容区的底部留白由 .page-with-tabbar 提供 */
:deep(.t-tab-bar) {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 100;
  padding-bottom: env(safe-area-inset-bottom);
  background: #fff;
}
</style>
