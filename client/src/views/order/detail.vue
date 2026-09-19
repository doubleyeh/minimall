<template>
  <div class="page" v-if="order">
    <t-navbar title="订单详情" left-arrow :fixed="false" @go-back="router.back()" />

    <div class="card status-card">
      <div class="status">{{ statusText(order.status) }}</div>
      <div v-if="order.status === 1" class="status-tip">请在 15 分钟内完成支付,超时订单会自动关闭</div>
    </div>

    <div class="card">
      <div class="section-title">收货信息</div>
      <div class="line">{{ order.receiverName }} {{ order.receiverPhone }}</div>
      <div class="line muted">{{ order.receiverAddress }}</div>
      <div v-if="order.logisticsNo" class="line muted">
        物流:{{ order.logisticsCompany }} {{ order.logisticsNo }}
      </div>
    </div>

    <div class="card">
      <div class="section-title">商品</div>
      <div v-for="item in order.items" :key="item.id" class="order-item">
        <img class="thumb" :src="item.goodsImage" alt="" />
        <div class="info">
          <div class="name ellipsis-2">{{ item.goodsName }}</div>
          <div class="sku">{{ item.skuName }} × {{ item.quantity }}</div>
        </div>
        <div class="amount">¥{{ item.totalAmount }}</div>
      </div>
    </div>

    <div class="card">
      <div class="line">
        <span>商品金额</span><span>¥{{ order.goodsAmount }}</span>
      </div>
      <div class="line">
        <span>运费</span><span>¥{{ order.freightAmount }}</span>
      </div>
      <div class="line">
        <span>满减优惠</span><span>-¥{{ order.promotionDiscountAmount }}</span>
      </div>
      <div class="line">
        <span>优惠券</span><span>-¥{{ order.couponDiscountAmount }}</span>
      </div>
      <div class="line total">
        <span>实付</span><span class="price">¥{{ order.payAmount }}</span>
      </div>
      <div class="line muted">下单时间:{{ order.createTime }}</div>
      <div v-if="order.remark" class="line muted">买家留言:{{ order.remark }}</div>
    </div>

    <div class="card">
      <div class="section-title">售后</div>
      <div v-if="(order.items ?? []).length === 0" class="empty-tip">-</div>
      <div v-for="item in order.items" :key="item.id" class="after-sale-row">
        <span class="ellipsis">{{ item.goodsName }} {{ item.skuName }}</span>
        <span class="muted">{{ afterSaleStatusText(item.afterSaleStatus) }}</span>
      </div>
    </div>

    <div class="page-gap"></div>
    <div class="footer">
      <t-button v-if="order.status === 1" @click="onCancel">取消订单</t-button>
      <t-button v-if="order.status === 1" theme="primary" @click="onPay">去支付</t-button>
      <t-button v-if="order.status === 3" theme="primary" @click="onReceive">确认收货</t-button>
      <t-button
        v-if="order.status === 2 || order.status === 3 || order.status === 4"
        theme="light"
        @click="openAfterSale"
      >
        申请售后
      </t-button>
    </div>
  </div>
  <div v-else class="empty-tip">加载中…</div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { mockPaySuccess, orderCancel, orderDetail, orderPrepay, orderReceive } from '@/api/client'
import type { ClientOrderView, Id } from '@/types/client'
import { ApiError } from '@/utils/request'

const route = useRoute()
const router = useRouter()

const order = ref<ClientOrderView | null>(null)

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

function afterSaleStatusText(status: number): string {
  if (status === 1) return '售后处理中'
  if (status === 2) return '售后已完成'
  return '未申请'
}

async function load(): Promise<void> {
  order.value = await orderDetail(String(route.params.orderId) as Id)
}

async function onCancel(): Promise<void> {
  if (!order.value) return
  await orderCancel(order.value.id)
  await load()
}

async function onReceive(): Promise<void> {
  if (!order.value) return
  await orderReceive(order.value.id)
  await load()
}

async function onPay(): Promise<void> {
  if (!order.value) return
  try {
    await orderPrepay(order.value.id)
    await mockPaySuccess(order.value.orderNo, order.value.payAmount)
    window.alert('支付成功(本地为模拟回调)')
    await load()
  } catch (error) {
    window.alert(error instanceof ApiError ? error.message : '支付失败')
  }
}

function openAfterSale(): void {
  const item = order.value?.items[0]
  if (!item) return
  void router.push(`/after-sales/apply/${item.id}`)
}

onMounted(load)
</script>

<style scoped>
.status-card {
  background: linear-gradient(135deg, #ff7a86, #e34d59);
  color: #fff;
}

.status {
  font-size: 18px;
  font-weight: 600;
}

.status-tip {
  margin-top: 6px;
  font-size: 12px;
  opacity: 0.9;
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
}

.line.total {
  border-top: 1px solid #f5f5f5;
  margin-top: 4px;
  padding-top: 8px;
  font-weight: 600;
}

.muted {
  color: #999;
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

.after-sale-row {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  padding: 4px 0;
}

.page-gap {
  height: 72px;
}

.footer {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 8px 12px;
  padding-bottom: calc(8px + env(safe-area-inset-bottom));
  background: #fff;
  border-top: 1px solid #f0f0f0;
}
</style>
