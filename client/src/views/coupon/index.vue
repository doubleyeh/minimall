<template>
  <div class="page">
    <t-navbar title="优惠券" left-arrow :fixed="false" @go-back="router.back()" />

    <div class="tabs">
      <div class="tab" :class="{ active: mode === 'claimable' }" @click="switchMode('claimable')">领券中心</div>
      <div class="tab" :class="{ active: mode === 'mine' }" @click="switchMode('mine')">我的券</div>
    </div>

    <div v-if="list.length === 0" class="empty-tip">暂无优惠券</div>

    <div v-for="coupon in list" :key="String(coupon.recordId ?? coupon.couponId)" class="coupon">
      <div class="coupon-left">
        <div class="amount">
          {{ coupon.couponType === 2 ? `${Number(coupon.discountRate) * 10} 折` : `¥${coupon.discountAmount ?? 0}` }}
        </div>
        <div class="threshold">满 ¥{{ coupon.minOrderAmount }} 可用</div>
      </div>
      <div class="coupon-right">
        <div class="name ellipsis">{{ coupon.couponName }}</div>
        <div class="valid">有效期至 {{ coupon.validEndTime }}</div>
        <div class="action">
          <span v-if="mode === 'mine'" class="status">{{ coupon.statusText }}</span>
          <t-button v-else size="extra-small" theme="primary" @click="onClaim(coupon)">领取</t-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { claimCoupon, claimableCoupons, myCoupons } from '@/api/client'
import { isLoggedIn } from '@/utils/auth'
import type { ClientCouponView } from '@/types/client'
import { ApiError } from '@/utils/request'

/**
 * 优惠券:领券中心(公开)与我的券(需要登录)。
 *
 * 领券走"条件更新占名额"的服务端实现(3.10),并发领取不会超发;
 * 端上拿到的状态文案也来自服务端,保证三个页面显示一致。
 */
const router = useRouter()
const mode = ref<'claimable' | 'mine'>('claimable')
const list = ref<ClientCouponView[]>([])

async function load(): Promise<void> {
  if (mode.value === 'claimable') {
    list.value = await claimableCoupons()
    return
  }
  if (!isLoggedIn()) {
    await router.push({ name: 'Login', query: { redirect: '/coupons' } })
    return
  }
  list.value = await myCoupons()
}

async function switchMode(next: 'claimable' | 'mine'): Promise<void> {
  mode.value = next
  await load()
}

async function onClaim(coupon: ClientCouponView): Promise<void> {
  if (!isLoggedIn()) {
    await router.push({ name: 'Login', query: { redirect: '/coupons' } })
    return
  }
  try {
    await claimCoupon(coupon.couponId)
    window.alert('领取成功')
    await load()
  } catch (error) {
    window.alert(error instanceof ApiError ? error.message : '领取失败')
  }
}

onMounted(load)
</script>

<style scoped>
.tabs {
  display: flex;
  background: #fff;
}

.tab {
  flex: 1;
  padding: 12px 0;
  text-align: center;
  font-size: 14px;
  color: #666;
}

.tab.active {
  color: var(--mall-price-color);
  font-weight: 600;
  border-bottom: 2px solid var(--mall-price-color);
}

.coupon {
  display: flex;
  margin: 8px;
  background: #fff;
  border-radius: 8px;
  overflow: hidden;
}

.coupon-left {
  flex: 0 0 110px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #ff7a86, #e34d59);
  color: #fff;
  padding: 12px 0;
}

.amount {
  font-size: 20px;
  font-weight: 700;
}

.threshold {
  font-size: 11px;
  opacity: 0.9;
  margin-top: 4px;
}

.coupon-right {
  flex: 1;
  min-width: 0;
  padding: 12px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}

.name {
  font-size: 14px;
  font-weight: 600;
}

.valid {
  color: #999;
  font-size: 12px;
}

.action {
  text-align: right;
}

.status {
  color: #999;
  font-size: 12px;
}
</style>
