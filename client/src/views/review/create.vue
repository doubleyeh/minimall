<template>
  <div class="page">
    <t-navbar title="发表评价" left-arrow :fixed="false" @go-back="router.back()" />

    <div class="card">
      <div class="section-title">评分</div>
      <t-rate v-model="rating" :count="5" />
      <div class="tip">{{ ratingText }}</div>
    </div>

    <div class="card">
      <div class="section-title">评价内容</div>
      <t-textarea
        v-model="content"
        placeholder="说说这件商品怎么样(选填)"
        :maxlength="500"
        indicator
      />
    </div>

    <div class="card">
      <div class="line">
        <span>匿名评价</span>
        <t-switch v-model="anonymous" />
      </div>
      <div class="tip">开启后其他买家看不到你的昵称</div>
    </div>

    <div class="footer">
      <t-button theme="primary" block :loading="submitting" @click="onSubmit">提交评价</t-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { createReview } from '@/api/client'
import type { Id } from '@/types/client'
import { ApiError } from '@/utils/request'

/**
 * 发表评价。
 *
 * 后端两条硬规则(见 ReviewServiceImpl.create),端上不做重复实现,但要在文案上对得上:
 * ①订单必须"已完成"——所以入口只在已完成订单里出现;
 * ②同一订单明细只能评价一次 —— 重复提交会返回可读错误,原样展示即可。
 *
 * 图片上传这一期不做(任务里没有对象存储),所以 images 不传。
 */
const route = useRoute()
const router = useRouter()

const rating = ref(5)
const content = ref('')
const anonymous = ref(false)
const submitting = ref(false)

const ratingText = computed(() => ['', '很差', '较差', '一般', '满意', '非常满意'][rating.value] ?? '')

async function onSubmit(): Promise<void> {
  submitting.value = true
  try {
    await createReview({
      orderItemId: String(route.params.orderItemId) as Id,
      rating: rating.value,
      // 空串会被后端存成空评价内容,统一转成 null:让"没写"与"写了空"在库里是同一种
      content: content.value.trim() || null,
      anonymous: anonymous.value,
    })
    window.alert('评价已提交')
    router.back()
  } catch (error) {
    window.alert(error instanceof ApiError ? error.message : '提交失败,请稍后重试')
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.section-title {
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 8px;
}

.tip {
  margin-top: 6px;
  font-size: 12px;
  color: #999;
}

.line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 14px;
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
