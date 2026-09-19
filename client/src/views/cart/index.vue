<template>
  <div class="page">
    <t-navbar title="购物车" :fixed="false" />

    <div v-if="items.length === 0" class="empty-tip">购物车还是空的,去逛逛吧</div>

    <div v-else>
      <!--
        勾选与清空。两个后端事实:
        ①`PUT /cart/{id}` 接受 selected,勾选状态是存在服务端的(换设备登录也保留),
          所以这里每次改完都重新拉列表,而不是本地改一下就算了;
        ②清空是独立的 `DELETE /cart/all`,不要自己拼一个"全部 id"的列表去删 ——
          本地列表可能已经过期(别的端删过),那样会漏删。
        另外后端没有批量勾选接口,全选只能逐条更新;购物车条目通常个位数,可以接受。
      -->
      <div class="toolbar">
        <t-checkbox :checked="allSelected" @change="onToggleAll">全选</t-checkbox>
        <t-button size="extra-small" theme="danger" variant="text" @click="onClear">清空购物车</t-button>
      </div>

      <t-cell-group v-for="item in items" :key="item.id" class="cart-item">
        <t-cell :title="item.goodsName || '商品已下架'" :description="item.skuName || ''">
          <template #image>
            <div class="thumb-wrap">
              <t-checkbox
                :checked="item.selected === 1"
                @change="(checked: boolean) => onSelectChange(item.id, checked)"
              />
              <img class="thumb" :src="item.image || ''" alt="" />
            </div>
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

import { cartClear, cartList, cartRemove, cartUpdate } from '@/api/client'
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

const allSelected = computed(() => items.value.length > 0 && items.value.every((item) => item.selected === 1))

async function onSelectChange(cartId: Id, selected: boolean): Promise<void> {
  await cartUpdate(cartId, { selected: selected ? 1 : 0 })
  await load()
}

async function onToggleAll(selected: boolean): Promise<void> {
  await Promise.all(items.value.map((item) => cartUpdate(item.id, { selected: selected ? 1 : 0 })))
  await load()
}

function onClear(): void {
  // 清空不可撤销,先确认。这里用系统 confirm 而不是 TDesign 的 Dialog:
  // 本页其余提示都是 window.alert,保持一致;等整体换成 toast/dialog 时一起改。
  if (!window.confirm('确认清空购物车?已下架或售罄的条目也会一起删除,该操作不可撤销。')) {
    return
  }
  void (async () => {
    await cartClear()
    await load()
  })()
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

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
}

.thumb-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
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
