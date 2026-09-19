<template>
  <div class="page">
    <t-navbar title="我的" :fixed="false" />

    <div class="header" v-if="data">
      <img class="avatar" :src="data.avatarUrl || ''" alt="" />
      <div class="info">
        <div class="nickname">{{ data.nickname || '微信用户' }}</div>
        <div class="muted">积分 {{ data.points }} · 成长值 {{ data.growthValue }}</div>
      </div>
    </div>

    <div class="card order-counts" v-if="data">
      <div class="count-item" @click="router.push({ name: 'Orders', query: { status: 1 } })">
        <div class="count">{{ data.orderCounts.pendingPay }}</div>
        <div class="label">待支付</div>
      </div>
      <div class="count-item" @click="router.push({ name: 'Orders', query: { status: 2 } })">
        <div class="count">{{ data.orderCounts.pendingShip }}</div>
        <div class="label">待发货</div>
      </div>
      <div class="count-item" @click="router.push({ name: 'Orders', query: { status: 3 } })">
        <div class="count">{{ data.orderCounts.pendingReceive }}</div>
        <div class="label">待收货</div>
      </div>
      <div class="count-item" @click="router.push({ name: 'Orders', query: { status: 4 } })">
        <div class="count">{{ data.orderCounts.finished }}</div>
        <div class="label">已完成</div>
      </div>
    </div>

    <t-cell-group>
      <t-cell title="我的订单" arrow @click="router.push('/orders')" />
      <t-cell title="我的售后" arrow @click="router.push('/after-sales')" />
      <t-cell title="优惠券" arrow @click="router.push('/coupons')" />
      <t-cell title="收货地址" arrow @click="router.push('/addresses')" />
      <t-cell title="退出登录" arrow @click="onLogout" />
    </t-cell-group>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { profile } from '@/api/client'
import { clearAuth } from '@/utils/auth'
import type { ClientProfileView } from '@/types/client'

/**
 * 个人中心。
 *
 * 订单角标由服务端一次返回(四个计数),而不是让这里对订单接口发四次请求 ——
 * 个人中心是高频页面,首屏请求数应当尽量少。
 */
const router = useRouter()
const data = ref<ClientProfileView | null>(null)

async function load(): Promise<void> {
  data.value = await profile()
}

function onLogout(): void {
  // 客户端没有"服务端登出"(JWT 无状态,见后端 3.10 的开放项 4),本地清令牌即可
  clearAuth()
  void router.replace('/login')
}

onMounted(load)
</script>

<style scoped>
.header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 20px 16px;
  background: #fff;
}

.avatar {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  object-fit: cover;
  background: #f0f0f0;
}

.nickname {
  font-size: 16px;
  font-weight: 600;
}

.muted {
  color: #999;
  font-size: 12px;
  margin-top: 4px;
}

.order-counts {
  display: flex;
  text-align: center;
}

.count-item {
  flex: 1;
}

.count {
  font-size: 18px;
  font-weight: 600;
}

.label {
  margin-top: 4px;
  color: #999;
  font-size: 12px;
}
</style>
