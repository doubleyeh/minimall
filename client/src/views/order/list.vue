<template>
  <div class="page">
    <t-navbar title="我的订单" :fixed="false" />

    <div class="tabs">
      <div
        v-for="tab in tabs"
        :key="String(tab.status)"
        class="tab"
        :class="{ active: String(activeStatus) === String(tab.status) }"
        @click="onTabChange(tab.status)"
      >
        {{ tab.label }}
      </div>
    </div>

    <div v-if="orders.length === 0" class="empty-tip">暂无订单</div>

    <div v-for="order in orders" :key="order.id" class="card" @click="openDetail(order.id)">
      <div class="order-head">
        <span class="order-no">{{ order.orderNo }}</span>
        <span class="order-status">{{ statusText(order.status) }}</span>
      </div>
      <div v-for="item in order.items" :key="item.id" class="order-item">
        <img class="thumb" :src="item.goodsImage" alt="" />
        <div class="info">
          <div class="name ellipsis-2">{{ item.goodsName }}</div>
          <div class="sku">{{ item.skuName }} × {{ item.quantity }}</div>
        </div>
        <div class="amount">¥{{ item.totalAmount }}</div>
      </div>
      <div class="order-foot">
        <span>实付 <span class="price">¥{{ order.payAmount }}</span></span>
      </div>
      <div class="order-actions" @click.stop>
        <t-button v-if="order.status === 1" size="extra-small" @click="onCancel(order.id)">取消订单</t-button>
        <t-button v-if="order.status === 1" size="extra-small" theme="primary" @click="onPay(order)">去支付</t-button>
        <t-button v-if="order.status === 3" size="extra-small" theme="primary" @click="onReceive(order.id)">确认收货</t-button>
        <t-button
          v-if="order.status === 2 || order.status === 3 || order.status === 4"
          size="extra-small"
          @click="openAfterSale(order)"
        >
          申请售后
        </t-button>
      </div>
    </div>

    <div v-if="hasMore" class="load-more" @click="loadMore">加载更多</div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { mockPaySuccess, orderCancel, orderList, orderPrepay, orderReceive } from '@/api/client'
import type { ClientOrderView, Id } from '@/types/client'
import { ApiError } from '@/utils/request'

/** 订单列表:状态筛选 + 按状态给出可用操作(与后端状态机 3.4 对应) */
const router = useRouter()

const tabs = [
  { label: '全部', status: null as number | null },
  { label: '待支付', status: 1 },
  { label: '待发货', status: 2 },
  { label: '待收货', status: 3 },
  { label: '已完成', status: 4 },
]

const activeStatus = ref<number | null>(null)
const orders = ref<ClientOrderView[]>([])
const pageNo = ref(1)
const hasMore = ref(false)

const statusTextMap: Record<number, string> = {
  1: '待支付',
  2: '待发货',
  3: '待收货',
  4: '已完成',
  5: '已取消',
  6: '售后中',
}

function statusText(status: number): string {
  return statusTextMap[status] ?? '未知'
}

async function load(reset = true): Promise<void> {
  const result = await orderList(activeStatus.value, pageNo.value, 10)
  orders.value = reset ? result.list : [...orders.value, ...result.list]
  hasMore.value = orders.value.length < result.total
}

function onTabChange(status: number | null): void {
  activeStatus.value = status
  pageNo.value = 1
  void load()
}

function loadMore(): void {
  pageNo.value += 1
  void load(false)
}

function openDetail(orderId: Id): void {
  void router.push(`/orders/${orderId}`)
}

async function onCancel(orderId: Id): Promise<void> {
  await orderCancel(orderId)
  await load()
}

async function onReceive(orderId: Id): Promise<void> {
  await orderReceive(orderId)
  await load()
}

async function onPay(order: ClientOrderView): Promise<void> {
  try {
    await orderPrepay(order.id)
    // 真实小程序:拿到 payParams 后调用 wx.requestPayment。
    // H5 里没有微信支付环境,所以这里直接触发一次模拟回调把订单推进到"待发货",
    // 否则支付之后的主流程(发货/收货/售后)在本地走不到。
    await mockPaySuccess(order.orderNo, order.payAmount)
    window.alert('支付成功(本地为模拟回调)')
    await load()
  } catch (error) {
    window.alert(error instanceof ApiError ? error.message : '支付失败')
  }
}

function openAfterSale(order: ClientOrderView): void {
  // 默认拿第一件商品去申请:多件商品的逐件售后在详情页里做
  const item = order.items[0]
  if (!item) return
  void router.push(`/after-sales/apply/${item.id}`)
}

onMounted(() => load())
</script>

<style scoped>
.tabs {
  display: flex;
  background: #fff;
  overflow-x: auto;
}

.tab {
  flex: 1;
  padding: 12px 0;
  text-align: center;
  font-size: 13px;
  color: #666;
  white-space: nowrap;
}

.tab.active {
  color: var(--mall-price-color);
  font-weight: 600;
  border-bottom: 2px solid var(--mall-price-color);
}

.order-head {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  margin-bottom: 8px;
}

.order-no {
  color: #666;
}

.order-status {
  color: var(--mall-price-color);
}

.order-item {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}

.thumb {
  width: 60px;
  height: 60px;
  flex: 0 0 60px;
  object-fit: cover;
  border-radius: 6px;
  background: #f5f5f5;
}

.info {
  flex: 1;
  min-width: 0;
}

.name {
  font-size: 13px;
}

.sku {
  color: #999;
  font-size: 12px;
  margin-top: 4px;
}

.amount {
  font-size: 13px;
}

.order-foot {
  text-align: right;
  font-size: 13px;
  padding-top: 8px;
  border-top: 1px solid #f5f5f5;
}

.order-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}

.load-more {
  padding: 12px;
  text-align: center;
  color: #666;
  font-size: 13px;
}
</style>
