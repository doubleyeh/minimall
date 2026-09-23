<template>
  <n-space vertical :size="16">
    <n-card>
      <n-form inline :model="query" label-placement="left">
        <n-form-item label="券名称">
          <n-input v-model:value="query.couponName" clearable style="width: 180px" />
        </n-form-item>
        <n-form-item label="状态">
          <n-select v-model:value="query.status" :options="statusOptions" clearable placeholder="全部" style="width: 130px" />
        </n-form-item>
        <n-form-item>
          <n-space>
            <n-button type="primary" @click="search">查询</n-button>
            <n-button @click="resetQuery">重置</n-button>
            <n-button v-perm="'mall:coupon:create'" type="primary" ghost @click="openCreate">新增优惠券</n-button>
          </n-space>
        </n-form-item>
      </n-form>

      <n-data-table
        max-height="var(--mm-table-max-h)"
        :columns="columns"
        :data="rows"
        :loading="loading"
        :row-key="(row: CouponView) => row.id"
        remote
        :pagination="pagination"
        @update:page="onPageChange"
      />
    </n-card>

    <n-modal v-model:show="formVisible" preset="card" :title="editingId ? '编辑优惠券' : '新增优惠券'" style="width: 620px">
      <n-form ref="formRef" :model="form" :rules="formRules" label-placement="top">
        <n-grid :cols="2" :x-gap="16">
          <n-form-item-gi label="券名称" path="couponName"><n-input v-model:value="form.couponName" /></n-form-item-gi>
          <n-form-item-gi label="类型" path="couponType">
            <n-select v-model:value="form.couponType" :options="typeOptions" />
          </n-form-item-gi>
          <n-form-item-gi v-if="form.couponType === 2" label="折扣率" path="discountRate">
            <n-input-number v-model:value="form.discountRate" :min="0.01" :max="0.99" :step="0.01" placeholder="如 0.9" />
          </n-form-item-gi>
          <n-form-item-gi v-else label="减免金额" path="discountAmount">
            <n-input-number v-model:value="form.discountAmount" :min="0.01" :precision="2" />
          </n-form-item-gi>
          <n-form-item-gi label="使用门槛(满 X 可用)" path="minOrderAmount">
            <n-input-number v-model:value="form.minOrderAmount" :min="0" :precision="2" />
          </n-form-item-gi>
          <n-form-item-gi label="发放总量" path="totalCount">
            <n-input-number v-model:value="form.totalCount" :min="1" />
          </n-form-item-gi>
          <n-form-item-gi label="每人限领" path="perCustomerLimit">
            <n-input-number v-model:value="form.perCustomerLimit" :min="1" />
          </n-form-item-gi>
          <n-form-item-gi label="状态">
            <n-radio-group v-model:value="form.status">
              <n-radio :value="1">启用</n-radio>
              <n-radio :value="0">停用</n-radio>
            </n-radio-group>
          </n-form-item-gi>
        </n-grid>
        <n-form-item label="有效期" path="validStartTime">
          <n-date-picker v-model:value="validRange" type="datetimerange" clearable style="width: 100%" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button @click="formVisible = false">取消</n-button>
          <n-button type="primary" :loading="submitting" @click="onSubmit">保存</n-button>
        </n-space>
      </template>
    </n-modal>
  </n-space>
</template>

<script setup lang="ts">
import { NButton, NTag, useMessage } from 'naive-ui'
import { computed, h, onMounted, reactive, ref } from 'vue'

import { changeCouponStatus, createCoupon, pageCoupons, updateCoupon } from '@/api/mall'
import { usePermissionStore } from '@/stores/permission'
import type { Id, PageResult } from '@/types/api'
import type { CouponSaveRequest, CouponView } from '@/types/mall'
import type { DataTableColumns, FormInst, FormRules, SelectOption } from 'naive-ui'

const message = useMessage()
const permission = usePermissionStore()

const loading = ref(false)
const submitting = ref(false)
const rows = ref<CouponView[]>([])
const total = ref(0)

const query = reactive<{ couponName?: string; status?: number | null; pageNo: number; pageSize: number }>({
  couponName: '',
  status: null,
  pageNo: 1,
  pageSize: 10,
})

const statusOptions: SelectOption[] = [
  { label: '进行中', value: 1 },
  { label: '已停用', value: 0 },
]

const typeOptions: SelectOption[] = [
  { label: '满减券', value: 1 },
  { label: '折扣券', value: 2 },
  { label: '无门槛现金券', value: 3 },
]

const pagination = computed(() => ({
  page: query.pageNo,
  pageSize: query.pageSize,
  itemCount: total.value,
  showSizePicker: false,
}))

const columns: DataTableColumns<CouponView> = [
  { title: '券名称', key: 'couponName' },
  { title: '类型', key: 'couponType', width: 120, render: (row) => typeText(row.couponType) },
  {
    title: '优惠',
    key: 'discount',
    width: 130,
    render: (row) =>
      row.couponType === 2 ? `打 ${Number(row.discountRate) * 10} 折` : `减 ¥${row.discountAmount ?? 0}`,
  },
  { title: '门槛', key: 'minOrderAmount', width: 110, render: (row) => `满 ¥${row.minOrderAmount}` },
  {
    title: '领取进度',
    key: 'receivedCount',
    width: 120,
    render: (row) => `${row.receivedCount} / ${row.totalCount}`,
  },
  { title: '每人限领', key: 'perCustomerLimit', width: 100 },
  { title: '有效期至', key: 'validEndTime', width: 170 },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render: (row) =>
      h(
        NTag,
        { size: 'small', type: row.status === 1 ? 'success' : 'default', bordered: false },
        { default: () => (row.status === 1 ? '进行中' : '已停用') },
      ),
  },
  {
    title: '操作',
    key: 'actions',
    width: 160,
    render: (row) =>
      h('div', { style: 'display:flex;gap:8px' }, [
        permission.hasPerm('mall:coupon:update')
          ? h(NButton, { size: 'tiny', onClick: () => openEdit(row) }, { default: () => '编辑' })
          : null,
        permission.hasPerm('mall:coupon:update')
          ? h(
              NButton,
              { size: 'tiny', type: row.status === 1 ? 'error' : 'primary', onClick: () => toggleStatus(row) },
              { default: () => (row.status === 1 ? '停用' : '启用') },
            )
          : null,
      ]),
  },
]

function typeText(type: number): string {
  return type === 1 ? '满减券' : type === 2 ? '折扣券' : '无门槛现金券'
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const result: PageResult<CouponView> = await pageCoupons({ ...query })
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
  query.couponName = ''
  query.status = null
  search()
}

function onPageChange(page: number): void {
  query.pageNo = page
  void load()
}

const formVisible = ref(false)
const editingId = ref<Id | null>(null)
const formRef = ref<FormInst | null>(null)
const validRange = ref<[number, number] | null>(null)
const form = reactive<CouponSaveRequest>({
  couponName: '',
  couponType: 1,
  discountAmount: null,
  discountRate: null,
  minOrderAmount: 0,
  totalCount: 100,
  perCustomerLimit: 1,
  validStartTime: '',
  validEndTime: '',
  status: 1,
})

const formRules: FormRules = {
  couponName: { required: true, message: '请输入券名称', trigger: ['blur', 'input'] },
}

/** 后端要 LocalDateTime 字符串,这里把时间戳转成 yyyy-MM-ddTHH:mm:ss */
function toLocalDateTime(timestamp: number): string {
  const date = new Date(timestamp)
  const pad = (value: number): string => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

function openCreate(): void {
  editingId.value = null
  form.couponName = ''
  form.couponType = 1
  form.discountAmount = null
  form.discountRate = null
  form.minOrderAmount = 0
  form.totalCount = 100
  form.perCustomerLimit = 1
  form.status = 1
  validRange.value = null
  formVisible.value = true
}

function openEdit(row: CouponView): void {
  editingId.value = row.id
  form.couponName = row.couponName
  form.couponType = row.couponType
  form.discountAmount = row.discountAmount == null ? null : Number(row.discountAmount)
  form.discountRate = row.discountRate == null ? null : Number(row.discountRate)
  form.minOrderAmount = Number(row.minOrderAmount)
  form.totalCount = row.totalCount
  form.perCustomerLimit = row.perCustomerLimit
  form.status = row.status
  validRange.value = [new Date(row.validStartTime).getTime(), new Date(row.validEndTime).getTime()]
  formVisible.value = true
}

async function onSubmit(): Promise<void> {
  await formRef.value?.validate()
  if (!validRange.value) {
    message.warning('请选择有效期')
    return
  }
  // 折扣券与面额券用不同字段:提交时把不用的那个清空,避免库里留下两套值导致以哪个为准的歧义
  const payload: CouponSaveRequest = {
    ...form,
    discountAmount: form.couponType === 2 ? null : form.discountAmount,
    discountRate: form.couponType === 2 ? form.discountRate : null,
    validStartTime: toLocalDateTime(validRange.value[0]),
    validEndTime: toLocalDateTime(validRange.value[1]),
  }
  submitting.value = true
  try {
    if (editingId.value == null) {
      await createCoupon(payload)
    } else {
      await updateCoupon(editingId.value, payload)
    }
    message.success('保存成功')
    formVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

async function toggleStatus(row: CouponView): Promise<void> {
  await changeCouponStatus(row.id, row.status === 1 ? 0 : 1)
  message.success(row.status === 1 ? '已停用' : '已启用')
  await load()
}

onMounted(load)
</script>
