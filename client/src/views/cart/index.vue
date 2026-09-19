<template>
  <div class="page">
    <t-navbar title="购物车" :fixed="false" />

    <div v-if="items.length === 0" class="empty-tip">购物车还是空的,去逛逛吧</div>

    <div v-else>
      <t-cell-group v-for="item in items" :key="item.id" class="cart-item">
        <t-cell :title="item.goodsName || '商品已下架'" :description="item.skuName || ''">
          <template #image>
            <img class="thumb" :src="item.image || ''" alt="" />
          </template>
          <template #note>
            <div class="note">
              <div class="price">¥{{ item.price ?? 0 }}</div>
              <div class="ops">
                <t-stepper
                  :value="item.quantity"
                  :min="1"
                  :max="Math.max(1, item.availableStock)"
                  @change="(value: number) => onQuantityChange(item.id, value)"
                />
                <t-button size="extra-small" theme="danger" variant="text" @click="onRemove(item.id)">删除</t-button>
              </div>
              <div v-if="!item.valid" class="invalid-tip">已下架或售罄,结算时会自动跳过</div>
            </div>
          </template>
        </t-cell>
      </t-cell-group>
    </div>

    <div v-if="items.length > 0" class="footer">
      <div class="total">
        合计 <span class="price">¥{{ totalAmount.toFixed(2) }}</span>
      </div>
      <t-button theme="primary" :disabled="!hasValidSelected" @click="goCheckout">去结算</t-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { cartList, cartRemove, cartUpdate } from '@/api/client'
import type { CartItemView, Id } from '@/types/client'
import { ApiError } from '@/utils/request'

/**
 * 购物车。
 *
 * 两点与后端约定对应:
 * 1. 数量调整会走 `PUT /mall/api/cart/{id}` —— 后端会校验可售库存,超了会返回可读的错误;
 * 2. 不可结算的条目(下架/售罄)**不从列表里删掉**:用户记得自己加过它,
 *    直接消失会让人以为购物车丢东西,置灰并给出原因才正常。
 */
const router = useRouter()
const items = ref<CartItemView[]>([])

async function load(): Promise<void> {
  items.value = await cartList()
}

const totalAmount = computed(() =>
  items.value
    .filter((item) => item.valid && item.selected === 1)
    .reduce((sum, item) => sum + (item.price ?? 0) * item.quantity, 0),
)

const hasValidSelected = computed(() => items.value.some((item) => item.valid && item.selected === 1))

async function onQuantityChange(cartId: Id, quantity: number): Promise<void> {
  try {
    await cartUpdate(cartId, { quantity })
    await load()
  } catch (error) {
    window.alert(error instanceof ApiError ? error.message : '修改数量失败')
    await load()
  }
}

async function onRemove(cartId: Id): Promise<void> {
  await cartRemove([cartId])
  await load()
}

function goCheckout(): void {
  void router.push('/checkout')
}

onMounted(load)
</script>

<style scoped>
.cart-item {
  margin: 8px;
  border-radius: 8px;
  overflow: hidden;
}

.thumb {
  width: 64px;
  height: 64px;
  object-fit: cover;
  border-radius: 6px;
  background: #f5f5f5;
}

.note {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
}

.ops {
  display: flex;
  align-items: center;
  gap: 8px;
}

.invalid-tip {
  color: #999;
  font-size: 11px;
}

.footer {
  position: fixed;
  left: 0;
  right: 0;
  bottom: calc(56px + env(safe-area-inset-bottom));
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  background: #fff;
  border-top: 1px solid #f0f0f0;
}

.total {
  font-size: 13px;
}

.total .price {
  font-size: 17px;
}
</style>
