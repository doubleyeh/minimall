<template>
  <div class="page">
    <t-navbar title="申请售后" left-arrow :fixed="false" @go-back="router.back()" />

    <div class="card">
      <div class="section-title">售后类型</div>
      <t-radio-group v-model="afterSaleType">
        <t-radio :value="1">仅退款</t-radio>
        <t-radio :value="2">退货退款</t-radio>
        <t-radio :value="3">换货</t-radio>
      </t-radio-group>
      <div class="hint">
        仅退款只支持未发货的订单;已发货请选择退货退款。同一件商品在售后进行中时不能重复申请。
      </div>
    </div>

    <div class="card">
      <div class="section-title">退款金额</div>
      <t-input v-model="refundAmount" type="number" placeholder="不超过该商品的实付金额" />
      <div class="hint">商家处理时只能下调退款金额,不能上调。</div>
    </div>

    <div class="card">
      <div class="section-title">申请原因</div>
      <t-input v-model="applyReason" placeholder="如:商品与描述不符" />
      <div class="gap"></div>
      <t-textarea v-model="applyDesc" placeholder="补充说明(可空)" :maxlength="500" />
    </div>

    <div class="card">
      <div class="section-title">凭证图片(可选,每行一个地址)</div>
      <t-textarea v-model="imagesText" placeholder="https://..." />
    </div>

    <div class="page-gap"></div>
    <div class="footer">
      <t-button block theme="primary" :loading="submitting" @click="onSubmit">提交申请</t-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { afterSaleApply } from '@/api/client'
import { ApiError } from '@/utils/request'

/**
 * 售后申请。
 *
 * 退款金额默认由用户填写(端上不知道订单明细金额),但**服务端会校验不超过明细行的实付金额** ——
 * 端上限制只是体验优化,真正的约束在后端(3.9)。
 */
const route = useRoute()
const router = useRouter()

const afterSaleType = ref(1)
const refundAmount = ref('')
const applyReason = ref('')
const applyDesc = ref('')
const imagesText = ref('')
const submitting = ref(false)

async function onSubmit(): Promise<void> {
  const amount = Number(refundAmount.value)
  if (!applyReason.value.trim()) {
    window.alert('请填写申请原因')
    return
  }
  if (!Number.isFinite(amount) || amount <= 0) {
    window.alert('请填写正确的退款金额')
    return
  }
  submitting.value = true
  try {
    await afterSaleApply({
      orderItemId: String(route.params.orderItemId),
      afterSaleType: afterSaleType.value,
      applyReason: applyReason.value.trim(),
      applyDesc: applyDesc.value.trim() || undefined,
      refundAmount: amount,
      images: imagesText.value
        .split('\n')
        .map((line) => line.trim())
        .filter((line) => line.length > 0),
    })
    window.alert('申请已提交,请等待商家处理')
    await router.replace('/after-sales')
  } catch (error) {
    window.alert(error instanceof ApiError ? error.message : '提交失败')
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  // 从订单详情跳过来时带上金额,省得用户自己算
  const amount = route.query.amount
  if (typeof amount === 'string') {
    refundAmount.value = amount
  }
})
</script>

<style scoped>
.section-title {
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 8px;
}

.hint {
  margin-top: 8px;
  color: #999;
  font-size: 12px;
  line-height: 1.6;
}

.gap {
  height: 12px;
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
</style>
