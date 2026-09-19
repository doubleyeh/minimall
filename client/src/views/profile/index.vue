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
      <t-cell title="收货地址" arrow @click="openAddressEditor" />
      <t-cell title="退出登录" arrow @click="onLogout" />
    </t-cell-group>

    <t-popup v-model="addressVisible" placement="bottom">
      <div class="popup">
        <div class="popup-title">新增收货地址</div>
        <t-input v-model="addressForm.receiverName" label="收货人" />
        <div class="gap"></div>
        <t-input v-model="addressForm.receiverPhone" label="手机号" />
        <div class="gap"></div>
        <t-input v-model="addressForm.province" label="省份" />
        <div class="gap"></div>
        <t-input v-model="addressForm.city" label="城市" />
        <div class="gap"></div>
        <t-input v-model="addressForm.district" label="区县" />
        <div class="gap"></div>
        <t-input v-model="addressForm.detailAddress" label="详细地址" />
        <div class="gap"></div>
        <t-button block theme="primary" @click="saveAddress">保存</t-button>
        <div class="gap"></div>
        <div v-for="address in addresses" :key="address.id" class="address-item">
          <span class="ellipsis">{{ address.receiverName }} {{ address.receiverPhone }} {{ address.detailAddress }}</span>
          <t-button size="extra-small" variant="text" @click="onDeleteAddress(address.id)">删除</t-button>
        </div>
      </div>
    </t-popup>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import { addressCreate, addressDelete, addressList, profile } from '@/api/client'
import { clearAuth } from '@/utils/auth'
import type { AddressView, ClientProfileView } from '@/types/client'
import { ApiError } from '@/utils/request'

/**
 * 个人中心。
 *
 * 只有一个"新增地址"的简化入口:**地址的完整管理(改默认、编辑)还没做页面**,
 * 下单时会用到列表里的默认地址。这里先保证"没有地址时能建一个"这条路径通。
 */
const router = useRouter()
const data = ref<ClientProfileView | null>(null)

const addressVisible = ref(false)
const addresses = ref<AddressView[]>([])
const addressForm = reactive({
  receiverName: '',
  receiverPhone: '',
  province: '',
  city: '',
  district: '',
  detailAddress: '',
})

async function load(): Promise<void> {
  data.value = await profile()
}

async function openAddressEditor(): Promise<void> {
  addresses.value = await addressList()
  addressVisible.value = true
}

async function saveAddress(): Promise<void> {
  if (!addressForm.receiverName.trim() || !addressForm.detailAddress.trim()) {
    window.alert('请填写收货人与详细地址')
    return
  }
  try {
    await addressCreate({ ...addressForm, defaultAddress: addresses.value.length === 0 })
    addresses.value = await addressList()
    window.alert('已保存')
  } catch (error) {
    window.alert(error instanceof ApiError ? error.message : '保存失败')
  }
}

async function onDeleteAddress(addressId: string): Promise<void> {
  await addressDelete(addressId)
  addresses.value = await addressList()
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

.popup {
  padding: 16px;
  background: #fff;
  max-height: 70vh;
  overflow-y: auto;
}

.popup-title {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 12px;
}

.gap {
  height: 12px;
}

.address-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 0;
  border-top: 1px solid #f5f5f5;
  font-size: 13px;
}
</style>
