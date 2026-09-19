<template>
  <n-space vertical :size="16">
    <n-card>
      <n-form inline :model="query" label-placement="left">
        <n-form-item label="订单号">
          <n-input v-model:value="query.orderNo" clearable placeholder="支持模糊搜索" style="width: 200px" />
        </n-form-item>
        <n-form-item label="状态">
          <n-select
            v-model:value="query.status"
            :options="statusOptions"
            clearable
            placeholder="全部"
            style="width: 160px"
          />
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
        :row-key="(row: AdminOrderView) => row.id"
        remote
        :pagination="pagination"
        @update:page="onPageChange"
      />
    </n-card>

    <!-- 发货 -->
    <n-modal v-model:show="shipVisible" preset="card" title="订单发货" style="width: 480px">
      <n-form :model="shipForm" label-placement="top">
        <n-form-item label="物流公司">
          <n-input v-model:value="shipForm.logisticsCompany" />
        </n-form-item>
        <n-form-item label="物流单号">
          <n-input v-model:value="shipForm.logisticsNo" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button @click="shipVisible = false">取消</n-button>
          <n-button type="primary" :loading="submitting" @click="onShip">确认发货</n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- 详情 -->
    <n-modal v-model:show="detailVisible" preset="card" title="订单详情" style="width: 760px">
      <n-descriptions v-if="detail" :column="2" bordered label-placement="left">
        <n-descriptions-item label="订单号">{{ detail.orderNo }}</n-descriptions-item>
        <n-descriptions-item label="状态">{{ statusText(detail.status) }}</n-descriptions-item>
        <n-descriptions-item label="收货人">
          {{ detail.receiverName }} {{ detail.receiverPhone }}
        </n-descriptions-item>
        <n-descriptions-item label="收货地址">{{ detail.receiverAddress }}</n-descriptions-item>
        <n-descriptions-item label="商品金额">¥{{ detail.goodsAmount }}</n-descriptions-item>
        <n-descriptions-item label="运费">¥{{ detail.freightAmount }}</n-descriptions-item>
        <n-descriptions-item label="满减优惠">-¥{{ detail.promotionDiscountAmount }}</n-descriptions-item>
        <n-descriptions-item label="优惠券">-¥{{ detail.couponDiscountAmount }}</n-descriptions-item>
        <n-descriptions-item label="实付金额">
          <n-text strong>¥{{ detail.payAmount }}</n-text>
        </n-descriptions-item>
        <n-descriptions-item label="买家留言">{{ detail.remark || '-' }}</n-descriptions-item>
        <n-descriptions-item label="物流">
          {{ detail.logisticsCompany ? `${detail.logisticsCompany} ${detail.logisticsNo}` : '-' }}
        </n-descriptions-item>
        <n-descriptions-item label="下单时间">{{ detail.createTime }}</n-descriptions-item>
      </n-descriptions>

      <n-table :data="detail?.items ?? []" :bordered="false" size="small" style="margin-top: 12px">
        <thead>
          <tr>
            <th>商品</th>
            <th>规格</th>
            <th>单价</th>
            <th>数量</th>
            <th>小计</th>
            <th>售后</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in detail?.items ?? []" :key="item.id">
            <td>{{ item.goodsName }}</td>
            <td>{{ item.skuName }}</td>
            <td>¥{{ item.price }}</td>
            <td>{{ item.quantity }}</td>
            <td>¥{{ item.totalAmount }}</td>
            <td>{{ afterSaleStatusText(item.afterSaleStatus) }}</td>
          </tr>
        </tbody>
      </n-table>
    </n-modal>
  </n-space>
</template>

<script setup lang="ts">
import { NButton, NTag, useDialog, useMessage } from 'naive-ui'
import { computed, h, onMounted, reactive, ref } from 'vue'

import { cancelOrder, getOrder, pageOrders, shipOrder } from '@/api/mall'
import { usePermissionStore } from '@/stores/permission'
import type { Id, PageResult } from '@/types/api'
import type { AdminOrderView } from '@/types/mall'
import type { DataTableColumns, SelectOption } from 'naive-ui'

const message = useMessage()
const dialog = useDialog()
const permission = usePermissionStore()

const loading = ref(false)
const submitting = ref(false)
const rows = ref<AdminOrderView[]>([])
const total = ref(0)
const detail = ref<AdminOrderView | null>(null)

const query = reactive<{ orderNo?: string; status?: number | null; pageNo: number; pageSize: number }>({
  orderNo: '',
  status: null,
  pageNo: 1,
  pageSize: 10,
})

/** 与后端 MallOrder 的常量保持一致(3.4 的状态机) */
const statusOptions: SelectOption[] = [
  { label: '待支付', value: 1 },
  { label: '待发货', value: 2 },
  { label: '待收货', value: 3 },
  { label: '已完成', value: 4 },
  { label: '已取消', value: 5 },
  { label: '售后中', value: 6 },
]

function statusText(status: number): string {
  return statusOptions.find((option) => option.value === status)?.label?.toString() ?? '未知'
}

function afterSaleStatusText(status: number): string {
  if (status === 1) return '售后中'
  if (status === 2) return '售后完成'
  return '-'
}

const pagination = computed(() => ({
  page: query.pageNo,
  pageSize: query.pageSize,
  itemCount: total.value,
  showSizePicker: false,
}))

const columns: DataTableColumns<AdminOrderView> = [
  { title: '订单号', key: 'orderNo', width: 180 },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render: (row) =>
      h(
        NTag,
        { size: 'small', type: row.status === 1 ? 'warning' : row.status === 6 ? 'error' : 'info', bordered: false },
        { default: () => statusText(row.status) },
      ),
  },
  { title: '实付金额', key: 'payAmount', width: 110, render: (row) => `¥${row.payAmount}` },
  { title: '收货人', key: 'receiverName', width: 120 },
  { title: '下单时间', key: 'createTime', width: 180 },
  {
    title: '操作',
    key: 'actions',
    width: 230,
    render: (row) =>
      h('div', { style: 'display:flex;gap:8px' }, [
        h(NButton, { size: 'tiny', onClick: () => openDetail(row.id) }, { default: () => '详情' }),
        permission.hasPerm('mall:order:ship') && row.status === 2
          ? h(NButton, { size: 'tiny', type: 'primary', onClick: () => openShip(row.id) }, { default: () => '发货' })
          : null,
        permission.hasPerm('mall:order:cancel') && row.status === 2
          ? h(
              NButton,
              { size: 'tiny', type: 'error', onClick: () => confirmCancel(row) },
              { default: () => '取消' },
            )
          : null,
      ]),
  },
]

async function load(): Promise<void> {
  loading.value = true
  try {
    const result: PageResult<AdminOrderView> = await pageOrders({ ...query })
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
  query.orderNo = ''
  query.status = null
  search()
}

function onPageChange(page: number): void {
  query.pageNo = page
  void load()
}

const shipVisible = ref(false)
const shipOrderId = ref<Id | null>(null)
const shipForm = reactive({ logisticsCompany: '', logisticsNo: '' })

function openShip(orderId: Id): void {
  shipOrderId.value = orderId
  shipForm.logisticsCompany = ''
  shipForm.logisticsNo = ''
  shipVisible.value = true
}

async function onShip(): Promise<void> {
  if (!shipOrderId.value) return
  submitting.value = true
  try {
    await shipOrder(shipOrderId.value, { ...shipForm })
    message.success('已发货')
    shipVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

const detailVisible = ref(false)

async function openDetail(orderId: Id): Promise<void> {
  detail.value = await getOrder(orderId)
  detailVisible.value = true
}

function confirmCancel(row: AdminOrderView): void {
  dialog.warning({
    title: '确认取消订单',
    // 明确告知后果:后端会回补库存并创建退款记录,这不是"只改个状态"
    content: `取消订单 ${row.orderNo} 会同时回补库存并发起全额退款,确定继续吗?`,
    positiveText: '确认取消',
    negativeText: '再想想',
    onPositiveClick: async () => {
      await cancelOrder(row.id, '商家取消')
      message.success('订单已取消,退款已发起')
      await load()
    },
  })
}

onMounted(load)
</script>
