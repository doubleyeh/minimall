<template>
  <div class="page">
    <t-navbar title="确认订单" left-arrow :fixed="false" @go-back="router.back()" />

    <div class="card" @click="showAddressPicker = true">
      <div v-if="selectedAddress" class="address">
        <div class="line">
          <span>{{ selectedAddress.receiverName }} {{ selectedAddress.receiverPhone }}</span>
          <span class="muted">切换</span>
        </div>
        <div class="line muted">
          {{ selectedAddress.province }}{{ selectedAddress.city }}{{ selectedAddress.district }}{{ selectedAddress.detailAddress }}
        </div>
      </div>
      <div v-else class="address empty">请选择收货地址</div>
    </div>

    <div class="card">
      <div class="section-title">商品</div>
      <div v-for="line in lines" :key="String(line.skuId)" class="line">
        <span class="ellipsis">{{ line.title }}</span>
        <span>× {{ line.quantity }}</span>
      </div>
    </div>

    <div class="card">
      <div class="section-title">优惠券</div>
      <div class="line" @click="showCouponPicker = true">
        <span>选择优惠券</span>
        <span class="muted">{{ selectedCouponText }}</span>
      </div>
    </div>

    <div class="card">
      <t-textarea v-model="remark" placeholder="买家留言(可空)" :maxlength="200" />
    </div>

    <div class="page-gap"></div>
    <div class="footer">
      <t-button block theme="primary" :loading="submitting" @click="onSubmit">提交订单</t-button>
    </div>

    <!-- 地址选择 -->
    <t-popup v-model="showAddressPicker" placement="bottom">
      <div class="popup">
        <div class="popup-title">选择收货地址</div>
        <div v-for="address in addresses" :key="address.id" class="popup-item" @click="chooseAddress(address)">
          <div>{{ address.receiverName }} {{ address.receiverPhone }}</div>
          <div class="muted">
            {{ address.province }}{{ address.city }}{{ address.district }}{{ address.detailAddress }}
          </div>
        </div>
        <div class="popup-item" @click="showAddressPicker = false">取消</div>
      </div>
    </t-popup>

    <!-- 优惠券选择 -->
    <t-popup v-model="showCouponPicker" placement="bottom">
      <div class="popup">
        <div class="popup-title">选择优惠券</div>
        <div class="popup-item" @click="chooseCoupon(null)">
          <div>不使用优惠券</div>
        </div>
        <div v-for="coupon in coupons" :key="String(coupon.recordId)" class="popup-item" @click="chooseCoupon(coupon)">
          <div>{{ coupon.couponName }}</div>
          <div class="muted">满 ¥{{ coupon.minOrderAmount }} 可用,有效期至 {{ coupon.validEndTime }}</div>
        </div>
        <div class="popup-item" @click="showCouponPicker = false">取消</div>
      </div>
    </t-popup>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { addressList, cartList, createOrder, goodsDetail, myCoupons } from '@/api/client'
import type { AddressView, ClientCouponView, Id } from '@/types/client'
import { ApiError } from '@/utils/request'

/**
 * 确认订单页。
 *
 * 支持两条来源:
 * 1. 购物车结算(不带 query)—— 后端会用购物车里已勾选的条目;
 * 2. 立即购买(query 带 skuId/quantity)—— 只买这一件,不动购物车。
 */
const route = useRoute()
const router = useRouter()

const addresses = ref<AddressView[]>([])
const selectedAddress = ref<AddressView | null>(null)
const coupons = ref<ClientCouponView[]>([])
const selectedCoupon = ref<ClientCouponView | null>(null)
const remark = ref('')
const submitting = ref(false)

const showAddressPicker = ref(false)
const showCouponPicker = ref(false)

/** 展示用的行数据:可能来自购物车(需要另取商品名),也可能是直接购买 */
const lines = ref<Array<{ skuId: Id; quantity: number; title: string }>>([])

const selectedCouponText = computed(() =>
  selectedCoupon.value ? selectedCoupon.value.couponName : '不使用',
)

function chooseAddress(address: AddressView): void {
  selectedAddress.value = address
  showAddressPicker.value = false
}

function chooseCoupon(coupon: ClientCouponView | null): void {
  selectedCoupon.value = coupon
  showCouponPicker.value = false
}

async function loadLines(): Promise<void> {
  const skuId = route.query.skuId
  if (typeof skuId === 'string' && skuId) {
    const quantity = Number(route.query.quantity ?? 1)
    lines.value = [{ skuId, quantity, title: '立即购买' }]
    return
  }
  // 购物车结算:只展示已勾选且可结算的条目(与后端取的数据源保持一致)
  const items = await cartList()
  lines.value = items
    .filter((item) => item.selected === 1 && item.valid)
    .map((item) => ({
      skuId: item.skuId,
      quantity: item.quantity,
      title: `${item.goodsName ?? ''} ${item.skuName ?? ''}`.trim(),
    }))
}

async function onSubmit(): Promise<void> {
  if (!selectedAddress.value) {
    window.alert('请先选择收货地址')
    return
  }
  if (lines.value.length === 0) {
    window.alert('没有可结算的商品')
    return
  }
  submitting.value = true
  try {
    const fromCart = typeof route.query.skuId !== 'string'
    const result = await createOrder({
      // 从购物车结算时不传 items,由后端取已勾选条目 —— 两边算出来的商品集合必须一致,
      // 前端传一份只会带来"前端算的和后端算的不一样"的可能
      items: fromCart ? undefined : lines.value.map((line) => ({ skuId: line.skuId, quantity: line.quantity })),
      addressId: selectedAddress.value.id,
      couponRecordId: selectedCoupon.value?.recordId ?? null,
      remark: remark.value.trim() || undefined,
    })
    await router.replace(`/orders/${result.orderId}`)
  } catch (error) {
    // 库存不足、券被用掉这类错误后端给了可直接展示的文案
    window.alert(error instanceof ApiError ? error.message : '下单失败')
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  const [addressData, couponData] = await Promise.all([addressList(), myCoupons(1)])
  addresses.value = addressData
  coupons.value = couponData
  selectedAddress.value = addressData.find((item) => item.isDefault === 1) ?? addressData[0] ?? null
  await loadLines()
})
</script>

<style scoped>
.address.empty {
  color: #999;
  font-size: 13px;
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 8px;
}

.line {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  padding: 4px 0;
  gap: 8px;
}

.muted {
  color: #999;
}

.page-gap {
  height: 72px;
}

.footer {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  padding: 8px 12px;
  padding-bottom: calc(8px + env(safe-area-inset-bottom));
  background: #fff;
  border-top: 1px solid #f0f0f0;
}

.popup {
  max-height: 60vh;
  overflow-y: auto;
  background: #fff;
  padding: 12px;
}

.popup-title {
  font-size: 15px;
  font-weight: 600;
  padding-bottom: 8px;
}

.popup-item {
  padding: 12px 0;
  border-top: 1px solid #f5f5f5;
  font-size: 14px;
}
</style>
