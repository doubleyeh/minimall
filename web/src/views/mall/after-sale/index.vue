<template>
  <n-space vertical :size="16">
    <n-card>
      <n-form inline :model="query" label-placement="left">
        <n-form-item label="售后单号">
          <n-input v-model:value="query.afterSaleNo" clearable style="width: 200px" />
        </n-form-item>
        <n-form-item label="状态">
          <n-select v-model:value="query.status" :options="statusOptions" clearable placeholder="全部" style="width: 170px" />
        </n-form-item>
        <n-form-item>
          <n-space>
            <n-button type="primary" @click="search">查询</n-button>
            <n-button @click="resetQuery">重置</n-button>
          </n-space>
        </n-form-item>
      </n-form>

      <n-data-table
        :columns="columns"
        :data="rows"
        :loading="loading"
        :row-key="(row: AfterSaleView) => row.id"
        remote
        :pagination="pagination"
        @update:page="onPageChange"
      />
    </n-card>

    <n-drawer v-model:show="detailVisible" :width="620">
      <n-drawer-content title="售后处理" closable>
        <template v-if="detail">
          <n-descriptions :column="1" bordered label-placement="left" size="small">
            <n-descriptions-item label="售后单号">{{ detail.afterSaleNo }}</n-descriptions-item>
            <n-descriptions-item label="订单号">{{ detail.orderNo }}</n-descriptions-item>
            <n-descriptions-item label="类型">{{ typeText(detail.afterSaleType) }}</n-descriptions-item>
            <n-descriptions-item label="状态">{{ detail.statusText }}</n-descriptions-item>
            <n-descriptions-item label="商品">
              {{ detail.item?.goodsName }} / {{ detail.item?.skuName }} × {{ detail.item?.quantity }}
            </n-descriptions-item>
            <n-descriptions-item label="申请原因">{{ detail.applyReason }}</n-descriptions-item>
            <n-descriptions-item label="问题描述">{{ detail.applyDesc || '-' }}</n-descriptions-item>
            <n-descriptions-item label="退款金额">¥{{ detail.refundAmount }}</n-descriptions-item>
            <n-descriptions-item label="退货物流">
              {{ detail.returnLogisticsCompany ? `${detail.returnLogisticsCompany} ${detail.returnLogisticsNo}` : '-' }}
            </n-descriptions-item>
            <n-descriptions-item label="拒绝原因">{{ detail.rejectReason || '-' }}</n-descriptions-item>
            <n-descriptions-item label="仲裁说明">{{ detail.arbitrationRemark || '-' }}</n-descriptions-item>
          </n-descriptions>

          <n-divider>流转记录</n-divider>
          <n-timeline>
            <n-timeline-item
              v-for="(log, index) in detail.logs ?? []"
              :key="index"
              :title="log.remark || '-'"
              :time="log.createTime"
            />
          </n-timeline>

          <n-divider>操作</n-divider>
          <n-space vertical :size="10">
            <n-input-group v-if="detail.status === 1">
              <n-input-number v-model:value="refundAmount" :min="0.01" :precision="2" placeholder="退款金额(只能下调)" />
              <n-button type="primary" @click="onApprove">同意</n-button>
              <n-button type="error" @click="onReject">拒绝</n-button>
            </n-input-group>
            <n-space v-if="detail.status === 3">
              <n-input v-model:value="reshipCompany" placeholder="重新发货物流公司(换货时填)" style="width: 220px" />
              <n-input v-model:value="reshipNo" placeholder="重新发货单号" style="width: 200px" />
              <n-button type="primary" @click="onConfirmReceive">确认收到退货</n-button>
              <n-button type="error" @click="onRejectReceive">拒绝收货</n-button>
            </n-space>
            <n-button v-if="detail.status === 7" type="primary" @click="onArbitrate(true)">仲裁通过</n-button>
            <n-button v-if="detail.status === 7" type="error" @click="onArbitrate(false)">仲裁驳回</n-button>
          </n-space>
        </template>
      </n-drawer-content>
    </n-drawer>
  </n-space>
</template>

<script setup lang="ts">
import { NButton, NTag, useDialog, useMessage } from 'naive-ui'
import { computed, h, onMounted, reactive, ref } from 'vue'

import {
  approveAfterSale,
  arbitrateAfterSale,
  confirmReceiveAfterSale,
  getAfterSale,
  pageAfterSales,
  rejectAfterSale,
  rejectReceiveAfterSale,
} from '@/api/mall'
import { usePermissionStore } from '@/stores/permission'
import type { Id, PageResult } from '@/types/api'
import type { AfterSaleView } from '@/types/mall'
import type { DataTableColumns, SelectOption } from 'naive-ui'

const message = useMessage()
const dialog = useDialog()
const permission = usePermissionStore()

const loading = ref(false)
const rows = ref<AfterSaleView[]>([])
const total = ref(0)
const detail = ref<AfterSaleView | null>(null)
const detailVisible = ref(false)

const query = reactive<{ afterSaleNo?: string; status?: number | null; pageNo: number; pageSize: number }>({
  afterSaleNo: '',
  status: null,
  pageNo: 1,
  pageSize: 10,
})

/** 与后端 MallAfterSale 的状态常量一致(3.9 的 10 态) */
const statusOptions: SelectOption[] = [
  { label: '待商家处理', value: 1 },
  { label: '待买家退货', value: 2 },
  { label: '待商家收货', value: 3 },
  { label: '售后完成', value: 4 },
  { label: '商家已拒绝', value: 5 },
  { label: '商家拒绝收货', value: 6 },
  { label: '客服介入中', value: 7 },
  { label: '仲裁通过', value: 8 },
  { label: '仲裁驳回', value: 9 },
  { label: '已关闭', value: 10 },
]

function typeText(type: number): string {
  return type === 1 ? '仅退款' : type === 2 ? '退货退款' : '换货'
}

const pagination = computed(() => ({
  page: query.pageNo,
  pageSize: query.pageSize,
  itemCount: total.value,
  showSizePicker: false,
}))

const columns: DataTableColumns<AfterSaleView> = [
  { title: '售后单号', key: 'afterSaleNo', width: 190 },
  { title: '订单号', key: 'orderNo', width: 180, render: (row) => row.orderNo ?? '-' },
  { title: '类型', key: 'afterSaleType', width: 100, render: (row) => typeText(row.afterSaleType) },
  {
    title: '状态',
    key: 'statusText',
    width: 120,
    render: (row) =>
      h(
        NTag,
        { size: 'small', type: row.status === 4 || row.status === 8 ? 'success' : 'warning', bordered: false },
        { default: () => row.statusText },
      ),
  },
  { title: '退款金额', key: 'refundAmount', width: 110, render: (row) => `¥${row.refundAmount}` },
  { title: '申请原因', key: 'applyReason', width: 140 },
  { title: '申请时间', key: 'createTime', width: 180 },
  {
    title: '操作',
    key: 'actions',
    width: 110,
    render: (row) =>
      h(
        NButton,
        {
          size: 'tiny',
          disabled: !permission.hasPerm('mall:after-sale:handle'),
          onClick: () => openDetail(row.id),
        },
        { default: () => '处理' },
      ),
  },
]

async function load(): Promise<void> {
  loading.value = true
  try {
    const result: PageResult<AfterSaleView> = await pageAfterSales({ ...query })
    rows.value = result.list
    total.value = result.total
  } finally {
    loading.value = false
  }
}

function search(): void {
  query.pageNo = 1
  void load()
}

function resetQuery(): void {
  query.afterSaleNo = ''
  query.status = null
  search()
}

function onPageChange(page: number): void {
  query.pageNo = page
  void load()
}

const refundAmount = ref<number | null>(null)
const reshipCompany = ref('')
const reshipNo = ref('')

async function openDetail(afterSaleId: Id): Promise<void> {
  detail.value = await getAfterSale(afterSaleId)
  refundAmount.value = detail.value.refundAmount
  reshipCompany.value = ''
  reshipNo.value = ''
  detailVisible.value = true
}

async function refresh(): Promise<void> {
  if (detail.value) {
    detail.value = await getAfterSale(detail.value.id)
  }
  await load()
}

async function onApprove(): Promise<void> {
  if (!detail.value) return
  // 传空表示按申请金额;小于申请金额表示下调(后端会校验不能上调并写入日志)
  const amount = refundAmount.value === detail.value.refundAmount ? null : refundAmount.value
  await approveAfterSale(detail.value.id, amount)
  message.success('已同意')
  await refresh()
}

function onReject(): void {
  if (!detail.value) return
  const target = detail.value
  dialog.warning({
    title: '拒绝售后申请',
    content: () => h('div', { style: 'padding-top:8px' }, [h(RejectInput)]),
    positiveText: '确认拒绝',
    negativeText: '取消',
    onPositiveClick: async () => {
      await rejectAfterSale(target.id, rejectReason.value)
      message.success('已拒绝')
      await refresh()
    },
  })
}

const rejectReason = ref('')
const RejectInput = {
  setup() {
    return () =>
      h('input', {
        class: 'n-input__input-el',
        style: 'width:100%;height:34px;border:1px solid #e0e0e6;border-radius:3px;padding:0 10px',
        placeholder: '请填写拒绝原因',
        onInput: (event: Event) => {
          rejectReason.value = (event.target as HTMLInputElement).value
        },
      })
  },
}

async function onConfirmReceive(): Promise<void> {
  if (!detail.value) return
  const target = detail.value
  dialog.warning({
    title: '确认收到退货',
    // 换货场景必须选新 SKU(后端要求),这里明确提示,避免用户以为"确认"就够了
    content:
      target.afterSaleType === 3
        ? '换货场景请填写重新发货的物流信息,并在输入框中确认规格已备好(新规格库存不足时后端会拒绝)。'
        : '确认后将立即执行退款,且不可撤销。',
    positiveText: '确认',
    negativeText: '取消',
    onPositiveClick: async () => {
      await confirmReceiveAfterSale(target.id, {
        logisticsCompany: reshipCompany.value || null,
        logisticsNo: reshipNo.value || null,
      })
      message.success('已确认收货')
      await refresh()
    },
  })
}

function onRejectReceive(): void {
  if (!detail.value) return
  const target = detail.value
  dialog.warning({
    title: '拒绝收货',
    content: () => h('div', { style: 'padding-top:8px' }, [h(RejectInput)]),
    positiveText: '确认',
    negativeText: '取消',
    onPositiveClick: async () => {
      await rejectReceiveAfterSale(target.id, rejectReason.value)
      message.success('已拒绝收货')
      await refresh()
    },
  })
}

async function onArbitrate(pass: boolean): Promise<void> {
  if (!detail.value) return
  await arbitrateAfterSale(detail.value.id, pass, pass ? '支持买家诉求' : '商家举证充分')
  message.success(pass ? '仲裁通过,已执行退款' : '仲裁驳回')
  await refresh()
}

onMounted(load)
</script>
