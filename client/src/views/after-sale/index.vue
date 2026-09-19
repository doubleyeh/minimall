<template>
  <div class="page">
    <t-navbar title="我的售后" :fixed="false" />

    <div v-if="list.length === 0" class="empty-tip">暂无售后记录</div>

    <div v-for="item in list" :key="item.id" class="card">
      <div class="head">
        <span class="muted">{{ item.afterSaleNo }}</span>
        <span class="status">{{ item.statusText }}</span>
      </div>
      <div class="line">
        <span class="ellipsis">{{ item.item?.goodsName }} {{ item.item?.skuName }}</span>
        <span>¥{{ item.refundAmount }}</span>
      </div>
      <div class="line muted">
        <span>{{ typeText(item.afterSaleType) }} · {{ item.applyReason }}</span>
        <span>{{ item.createTime }}</span>
      </div>
      <div v-if="item.rejectReason" class="line muted">拒绝原因:{{ item.rejectReason }}</div>

      <div class="actions">
        <!-- 被拒绝后可以撤销重来,或申请客服介入(状态值见后端 3.9 的状态机) -->
        <t-button v-if="item.status === 5 || item.status === 6" size="extra-small" @click="onCancel(item.id)">
          撤销申请
        </t-button>
        <t-button
          v-if="item.status === 5 || item.status === 6"
          size="extra-small"
          theme="primary"
          @click="onArbitration(item.id)"
        >
          申请客服介入
        </t-button>
        <t-button v-if="item.status === 2" size="extra-small" theme="primary" @click="openReturn(item)">填写退货物流</t-button>
      </div>
    </div>

    <t-popup v-model="returnVisible" placement="bottom">
      <div class="popup">
        <div class="popup-title">填写退货物流</div>
        <t-input v-model="returnCompany" label="物流公司" placeholder="如 顺丰" />
        <div class="gap"></div>
        <t-input v-model="returnNo" label="物流单号" placeholder="如 SF123456" />
        <div class="gap"></div>
        <t-button block theme="primary" @click="submitReturn">提交</t-button>
      </div>
    </t-popup>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { afterSaleArbitration, afterSaleCancel, afterSaleMine, afterSaleReturnLogistics } from '@/api/client'
import type { AfterSaleView, Id } from '@/types/client'

const list = ref<AfterSaleView[]>([])
const returnVisible = ref(false)
const returnCompany = ref('')
const returnNo = ref('')
const returningId = ref<Id | null>(null)

function typeText(type: number): string {
  return type === 1 ? '仅退款' : type === 2 ? '退货退款' : '换货'
}

async function load(): Promise<void> {
  list.value = await afterSaleMine()
}

async function onCancel(afterSaleId: Id): Promise<void> {
  await afterSaleCancel(afterSaleId)
  await load()
}

async function onArbitration(afterSaleId: Id): Promise<void> {
  // 本期没有独立客服角色,申请后由商家/超管在管理端仲裁(后端已预留 operator_type)
  await afterSaleArbitration(afterSaleId)
  await load()
}

function openReturn(item: AfterSaleView): void {
  returningId.value = item.id
  returnCompany.value = ''
  returnNo.value = ''
  returnVisible.value = true
}

async function submitReturn(): Promise<void> {
  if (!returningId.value) return
  if (!returnCompany.value.trim() || !returnNo.value.trim()) {
    window.alert('请填写物流公司与单号')
    return
  }
  await afterSaleReturnLogistics(returningId.value, returnCompany.value.trim(), returnNo.value.trim())
  returnVisible.value = false
  await load()
}

onMounted(load)
</script>

<style scoped>
.head {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  margin-bottom: 6px;
}

.status {
  color: var(--mall-price-color);
}

.line {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 13px;
  padding: 3px 0;
}

.muted {
  color: #999;
  font-size: 12px;
}

.actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}

.popup {
  padding: 16px;
  background: #fff;
}

.popup-title {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 12px;
}

.gap {
  height: 12px;
}
</style>
