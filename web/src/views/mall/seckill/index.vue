<template>
  <n-space vertical :size="16">
    <n-card>
      <n-form inline :model="query" label-placement="left">
        <n-form-item label="活动名称">
          <n-input v-model:value="query.keyword" clearable style="width: 180px" />
        </n-form-item>
        <n-form-item label="状态">
          <n-select v-model:value="query.status" :options="statusOptions" clearable placeholder="全部" style="width: 130px" />
        </n-form-item>
        <n-form-item>
          <n-space>
            <n-button type="primary" @click="search">查询</n-button>
            <n-button @click="resetQuery">重置</n-button>
            <n-button v-perm="'mall:seckill:create'" type="primary" ghost @click="openCreate">新建活动</n-button>
          </n-space>
        </n-form-item>
      </n-form>

      <n-data-table
        max-height="var(--mm-table-max-h)"
        :columns="columns"
        :data="rows"
        :loading="loading"
        :row-key="(row: SeckillActivityView) => row.id"
        remote
        :pagination="pagination"
        @update:page="onPageChange"
      />
    </n-card>

    <n-drawer v-model:show="drawerVisible" :width="860" placement="right">
      <n-drawer-content :title="editingId ? '编辑秒杀活动' : '新建秒杀活动'" closable>
        <n-form ref="formRef" :model="form" :rules="formRules" label-placement="top">
          <n-grid :cols="2" :x-gap="16">
            <n-form-item-gi label="活动名称" path="activityName">
              <n-input v-model:value="form.activityName" placeholder="如 周末限时秒杀" />
            </n-form-item-gi>
            <n-form-item-gi label="排序">
              <n-input-number v-model:value="form.sortOrder" :min="0" style="width: 100%" />
            </n-form-item-gi>
          </n-grid>
          <n-form-item label="活动时间" path="timeRange">
            <n-date-picker v-model:value="timeRange" type="datetimerange" clearable style="width: 100%" />
          </n-form-item>
          <n-form-item label="备注">
            <n-input v-model:value="form.remark" type="textarea" :rows="2" />
          </n-form-item>
        </n-form>

        <n-divider title-placement="left">秒杀商品</n-divider>
        <n-space vertical :size="12">
          <n-empty v-if="itemDrafts.length === 0" description="还没有商品,先添加一个" />
          <n-card v-for="(item, index) in itemDrafts" :key="index" size="small" :bordered="true">
            <template #header>
              <n-space align="center">
                <n-image v-if="item.mainImage" :src="item.mainImage" width="32" height="32" object-fit="cover" />
                <span>{{ item.goodsName }}</span>
              </n-space>
            </template>
            <template #header-extra>
              <n-button size="tiny" type="error" ghost @click="removeItem(index)">移除</n-button>
            </template>
            <n-grid :cols="4" :x-gap="12">
              <n-form-item-gi label="规格">
                <n-select
                  v-model:value="item.skuId"
                  :options="skuOptions(item)"
                  placeholder="选择规格"
                  @update:value="(value: string) => onSkuChange(item, value)"
                />
              </n-form-item-gi>
              <n-form-item-gi label="秒杀价">
                <n-input-number v-model:value="item.seckillPrice" :min="0" :precision="2" style="width: 100%" />
              </n-form-item-gi>
              <n-form-item-gi label="配额(件)">
                <n-input-number v-model:value="item.seckillStock" :min="1" style="width: 100%" />
              </n-form-item-gi>
              <n-form-item-gi label="每人限购">
                <n-input-number v-model:value="item.perCustomerLimit" :min="1" style="width: 100%" />
              </n-form-item-gi>
            </n-grid>
            <n-text depth="3" style="font-size: 12px">{{ stockHint(item) }}</n-text>
          </n-card>
          <n-button dashed block @click="openGoodsPicker">+ 添加商品</n-button>
        </n-space>

        <template #footer>
          <n-space justify="end">
            <n-button @click="drawerVisible = false">取消</n-button>
            <n-button type="primary" :loading="submitting" @click="onSubmit">保存草稿</n-button>
          </n-space>
        </template>
      </n-drawer-content>
    </n-drawer>

    <n-modal v-model:show="goodsPickerVisible" preset="card" title="选择商品" style="width: 760px">
      <n-space vertical :size="12">
        <n-input v-model:value="goodsKeyword" placeholder="按商品名搜索" clearable @keyup.enter="loadGoods" />
        <n-data-table
          :columns="goodsColumns"
          :data="goodsRows"
          :loading="goodsLoading"
          :row-key="(row: GoodsView) => row.id"
          :max-height="380"
        />
      </n-space>
    </n-modal>
  </n-space>
</template>

<script setup lang="ts">
import { NButton, NTag, useMessage } from 'naive-ui'
import { computed, h, onMounted, reactive, ref } from 'vue'

import {
  cancelSeckill,
  createSeckill,
  deleteSeckill,
  getGoods,
  getSeckill,
  pageGoods,
  pageSeckills,
  publishSeckill,
  updateSeckill,
} from '@/api/mall'
import { usePermissionStore } from '@/stores/permission'
import type { Id, PageResult } from '@/types/api'
import type {
  GoodsView,
  SeckillActivityView,
  SeckillSaveRequest,
  SkuView,
} from '@/types/mall'
import type { DataTableColumns, FormInst, FormRules, SelectOption } from 'naive-ui'
import { toLocalDateTime } from '@/utils/datetime'

interface ItemDraft {
  goodsId: Id
  goodsName: string
  mainImage?: string | null
  skus: SkuView[]
  skuId: Id | null
  seckillPrice: number | null
  seckillStock: number
  perCustomerLimit: number
}

const message = useMessage()
const permission = usePermissionStore()

const loading = ref(false)
const submitting = ref(false)
const rows = ref<SeckillActivityView[]>([])
const total = ref(0)

const query = reactive<{ keyword?: string; status?: number | null; pageNo: number; pageSize: number }>({
  keyword: '',
  status: null,
  pageNo: 1,
  pageSize: 10,
})

const statusOptions: SelectOption[] = [
  { label: '草稿', value: 0 },
  { label: '待开始', value: 1 },
  { label: '进行中', value: 2 },
  { label: '已结束', value: 3 },
  { label: '已取消', value: 4 },
]

const pagination = computed(() => ({
  page: query.pageNo,
  pageSize: query.pageSize,
  itemCount: total.value,
  showSizePicker: false,
}))

function statusTag(status: number) {
  const map: Record<number, { text: string; type: 'default' | 'info' | 'success' | 'warning' | 'error' }> = {
    0: { text: '草稿', type: 'default' },
    1: { text: '待开始', type: 'info' },
    2: { text: '进行中', type: 'success' },
    3: { text: '已结束', type: 'default' },
    4: { text: '已取消', type: 'warning' },
  }
  const item = map[status] ?? { text: '未知', type: 'default' as const }
  return h(NTag, { size: 'small', type: item.type, bordered: false }, { default: () => item.text })
}

const columns: DataTableColumns<SeckillActivityView> = [
  { title: '活动名称', key: 'activityName', minWidth: 160 },
  {
    title: '活动时间',
    key: 'time',
    width: 300,
    render: (row) => `${row.startTime.replace('T', ' ')} ~ ${row.endTime.replace('T', ' ')}`,
  },
  { title: '状态', key: 'status', width: 100, render: (row) => statusTag(row.status) },
  { title: '商品数', key: 'items', width: 90, render: (row) => row.items.length },
  {
    title: '已售 / 配额',
    key: 'progress',
    width: 130,
    render: (row) => `${row.totalSold} / ${row.totalQuota}`,
  },
  {
    title: '已冻结',
    key: 'frozenStock',
    width: 100,
    render: (row) => (row.status === 1 || row.status === 2 ? row.frozenStock : '—'),
  },
  {
    title: '操作',
    key: 'actions',
    width: 240,
    render: (row) =>
      h('div', { style: 'display:flex;gap:8px;flex-wrap:wrap' }, [
        row.status === 0 && permission.hasPerm('mall:seckill:update')
          ? h(NButton, { size: 'tiny', onClick: () => openEdit(row) }, { default: () => '编辑' })
          : null,
        row.status === 0 && permission.hasPerm('mall:seckill:publish')
          ? h(NButton, { size: 'tiny', type: 'primary', onClick: () => onPublish(row) }, { default: () => '发布' })
          : null,
        (row.status === 1 || row.status === 2) && permission.hasPerm('mall:seckill:cancel')
          ? h(NButton, { size: 'tiny', type: 'warning', onClick: () => onCancel(row) }, { default: () => '取消' })
          : null,
        (row.status === 0 || row.status === 4) && permission.hasPerm('mall:seckill:update')
          ? h(NButton, { size: 'tiny', type: 'error', ghost: true, onClick: () => onDelete(row) }, { default: () => '删除' })
          : null,
      ]),
  },
]

async function load(): Promise<void> {
  loading.value = true
  try {
    const result: PageResult<SeckillActivityView> = await pageSeckills({ ...query })
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
  query.keyword = ''
  query.status = null
  search()
}

function onPageChange(page: number): void {
  query.pageNo = page
  void load()
}

// ---------------------------------------------------------------- 表单

const drawerVisible = ref(false)
const editingId = ref<Id | null>(null)
const formRef = ref<FormInst | null>(null)
const timeRange = ref<[number, number] | null>(null)
const itemDrafts = ref<ItemDraft[]>([])

const form = reactive<{ activityName: string; remark?: string | null; sortOrder: number }>({
  activityName: '',
  remark: '',
  sortOrder: 0,
})

const formRules: FormRules = {
  activityName: { required: true, message: '请输入活动名称', trigger: ['blur', 'input'] },
}

function openCreate(): void {
  editingId.value = null
  form.activityName = ''
  form.remark = ''
  form.sortOrder = 0
  timeRange.value = null
  itemDrafts.value = []
  drawerVisible.value = true
}

async function openEdit(row: SeckillActivityView): Promise<void> {
  editingId.value = row.id
  form.activityName = row.activityName
  form.remark = row.remark ?? ''
  form.sortOrder = row.sortOrder
  timeRange.value = [new Date(row.startTime).getTime(), new Date(row.endTime).getTime()]
  itemDrafts.value = []
  drawerVisible.value = true

  const detail = await getSeckill(row.id)
  const goodsCache = new Map<Id, SkuView[]>()
  for (const item of detail.items) {
    let skus = goodsCache.get(item.goodsId)
    if (!skus) {
      const goods = await getGoods(item.goodsId)
      skus = goods.skus
      goodsCache.set(item.goodsId, skus)
    }
    itemDrafts.value.push({
      goodsId: item.goodsId,
      goodsName: item.goodsName ?? '',
      mainImage: item.goodsImage,
      skus,
      skuId: item.skuId,
      seckillPrice: Number(item.seckillPrice),
      seckillStock: item.seckillStock,
      perCustomerLimit: item.perCustomerLimit,
    })
  }
}

function skuOptions(item: ItemDraft): SelectOption[] {
  return item.skus.map((sku) => ({
    label: `${sku.skuName} · ¥${sku.price} · 可售 ${sku.availableStock}`,
    value: sku.id,
  }))
}

function onSkuChange(item: ItemDraft, skuId: string): void {
  const sku = item.skus.find((row) => row.id === skuId)
  if (sku) {
    item.skuId = sku.id
  }
}

function stockHint(item: ItemDraft): string {
  const sku = item.skus.find((row) => row.id === item.skuId)
  if (!sku) {
    return '选择规格后这里显示库存'
  }
  return `原价 ¥${sku.price} · 实际库存 ${sku.stock} · 已锁定 ${sku.lockedStock} · 秒杀已冻结 ${sku.frozenStock} · 普通可售 ${sku.availableStock}`
}

function removeItem(index: number): void {
  itemDrafts.value.splice(index, 1)
}

async function onSubmit(): Promise<void> {
  await formRef.value?.validate()
  if (!timeRange.value) {
    message.warning('请选择活动时间')
    return
  }
  if (itemDrafts.value.length === 0) {
    message.warning('至少添加一个秒杀商品')
    return
  }
  for (const item of itemDrafts.value) {
    if (!item.skuId || item.seckillPrice == null) {
      message.warning('每个商品都要选规格并填写秒杀价')
      return
    }
  }
  const payload: SeckillSaveRequest = {
    activityName: form.activityName,
    startTime: toLocalDateTime(timeRange.value[0]),
    endTime: toLocalDateTime(timeRange.value[1]),
    remark: form.remark,
    sortOrder: form.sortOrder,
    items: itemDrafts.value.map((item, index) => ({
      skuId: item.skuId as Id,
      seckillPrice: item.seckillPrice as number,
      seckillStock: item.seckillStock,
      perCustomerLimit: item.perCustomerLimit,
      sortOrder: index,
    })),
  }
  submitting.value = true
  try {
    if (editingId.value == null) {
      await createSeckill(payload)
    } else {
      await updateSeckill(editingId.value, payload)
    }
    message.success('已保存为草稿,确认无误后点「发布」冻结库存')
    drawerVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

// ---------------------------------------------------------------- 商品选择

const goodsPickerVisible = ref(false)
const goodsKeyword = ref('')
const goodsLoading = ref(false)
const goodsRows = ref<GoodsView[]>([])

const goodsColumns: DataTableColumns<GoodsView> = [
  { title: '商品', key: 'goodsName', minWidth: 200 },
  { title: '价格', key: 'salePriceMin', width: 110, render: (row) => `¥${row.salePriceMin}` },
  { title: '总库存', key: 'totalStock', width: 100 },
  { title: '状态', key: 'status', width: 90, render: (row) => (row.status === 1 ? '在售' : '下架') },
  {
    title: '',
    key: 'pick',
    width: 80,
    render: (row) =>
      h(NButton, { size: 'tiny', onClick: () => void pickGoods(row) }, { default: () => '选择' }),
  },
]

function openGoodsPicker(): void {
  goodsPickerVisible.value = true
  goodsKeyword.value = ''
  void loadGoods()
}

async function loadGoods(): Promise<void> {
  goodsLoading.value = true
  try {
    const result = await pageGoods({ goodsName: goodsKeyword.value, status: 1, pageNo: 1, pageSize: 50 })
    goodsRows.value = result.list
  } finally {
    goodsLoading.value = false
  }
}

async function pickGoods(row: GoodsView): Promise<void> {
  const goods = await getGoods(row.id)
  itemDrafts.value.push({
    goodsId: goods.id,
    goodsName: goods.goodsName,
    mainImage: goods.mainImage,
    skus: goods.skus,
    skuId: goods.skus[0]?.id ?? null,
    seckillPrice: null,
    seckillStock: 10,
    perCustomerLimit: 1,
  })
  goodsPickerVisible.value = false
}

// ---------------------------------------------------------------- 行操作

async function onPublish(row: SeckillActivityView): Promise<void> {
  const frozen = await publishSeckill(row.id)
  message.success(`已发布,从可售库存冻结 ${frozen} 件`)
  await load()
}

async function onCancel(row: SeckillActivityView): Promise<void> {
  await cancelSeckill(row.id)
  message.success('已取消,未售出的配额已解冻')
  await load()
}

async function onDelete(row: SeckillActivityView): Promise<void> {
  await deleteSeckill(row.id)
  message.success('已删除')
  await load()
}

onMounted(load)
</script>
