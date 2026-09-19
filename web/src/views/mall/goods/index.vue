<template>
  <n-space vertical :size="16">
    <n-card>
      <n-form inline :model="query" label-placement="left">
        <n-form-item label="商品名称">
          <n-input v-model:value="query.goodsName" clearable placeholder="支持模糊搜索" style="width: 180px" />
        </n-form-item>
        <n-form-item label="分类">
          <n-tree-select
            v-model:value="query.categoryId"
            :options="categoryOptions"
            clearable
            placeholder="全部"
            style="width: 180px"
          />
        </n-form-item>
        <n-form-item label="状态">
          <n-select
            v-model:value="query.status"
            :options="statusOptions"
            clearable
            placeholder="全部"
            style="width: 120px"
          />
        </n-form-item>
        <n-form-item>
          <n-space>
            <n-button type="primary" @click="search">查询</n-button>
            <n-button @click="resetQuery">重置</n-button>
            <n-button v-perm="'mall:goods:create'" type="primary" ghost @click="openCreate">
              新增商品
            </n-button>
          </n-space>
        </n-form-item>
      </n-form>

      <n-data-table
        :columns="columns"
        :data="rows"
        :loading="loading"
        :row-key="(row: GoodsView) => row.id"
        remote
        :pagination="pagination"
        @update:page="onPageChange"
      />
    </n-card>

    <n-modal
      v-model:show="formVisible"
      preset="card"
      :title="editingId ? '编辑商品' : '新增商品'"
      style="width: 940px"
    >
      <n-form ref="formRef" :model="form" :rules="formRules" label-placement="top">
        <n-grid :cols="2" :x-gap="16">
          <n-form-item-gi label="商品名称" path="goodsName">
            <n-input v-model:value="form.goodsName" />
          </n-form-item-gi>
          <n-form-item-gi label="商品分类" path="categoryId">
            <n-tree-select v-model:value="form.categoryId" :options="categoryOptions" />
          </n-form-item-gi>
          <n-form-item-gi label="副标题">
            <n-input v-model:value="form.goodsSubtitle" />
          </n-form-item-gi>
          <n-form-item-gi label="运费模板">
            <n-select
              v-model:value="form.freightTemplateId"
              :options="freightOptions"
              clearable
              placeholder="不选表示包邮"
            />
          </n-form-item-gi>
          <n-form-item-gi label="主图地址" path="mainImage">
            <n-input v-model:value="form.mainImage" placeholder="列表页展示的主图 URL" />
          </n-form-item-gi>
          <n-form-item-gi label="排序">
            <n-input-number v-model:value="form.sortOrder" :min="0" />
          </n-form-item-gi>
        </n-grid>

        <n-form-item label="轮播图(每行一个 URL)">
          <n-input
            :value="imagesText"
            type="textarea"
            :rows="2"
            @update:value="(value: string) => (imagesText = value)"
          />
        </n-form-item>
        <n-form-item label="商品详情(HTML 片段)">
          <n-input
            :value="form.detailContent ?? ''"
            type="textarea"
            :rows="3"
            @update:value="(value: string) => (form.detailContent = value)"
          />
        </n-form-item>

        <n-divider>规格</n-divider>
        <n-space vertical :size="8">
          <n-space v-for="(spec, index) in specs" :key="index" align="center">
            <n-input v-model:value="spec.specName" placeholder="规格名,如 颜色" style="width: 160px" />
            <n-input
              :value="spec.values.join(',')"
              placeholder="规格值,用逗号分隔,如 红色,蓝色"
              style="width: 380px"
              @update:value="(value: string) => (spec.values = splitValues(value))"
            />
            <n-button size="small" type="error" @click="specs.splice(index, 1)">删除</n-button>
          </n-space>
          <n-button size="small" @click="specs.push({ specName: '', values: [] })">添加规格</n-button>
        </n-space>

        <n-divider>SKU</n-divider>
        <n-alert type="info" :bordered="false" style="margin-bottom: 8px">
          每个 SKU 的「规格值」要从上面填写的规格值里选。未提交的旧 SKU 会被置为停售(不会删除),
          因为历史订单与售后要能回查到它。
        </n-alert>
        <n-space vertical :size="8">
          <n-card v-for="(sku, index) in skus" :key="index" size="small">
            <n-grid :cols="4" :x-gap="12">
              <n-form-item-gi label="SKU 编码">
                <n-input v-model:value="sku.skuCode" />
              </n-form-item-gi>
              <n-form-item-gi label="SKU 名称">
                <n-input v-model:value="sku.skuName" placeholder="如 红色/XL" />
              </n-form-item-gi>
              <n-form-item-gi label="售价">
                <n-input-number v-model:value="sku.price" :min="0" :precision="2" />
              </n-form-item-gi>
              <n-form-item-gi label="成本价">
                <n-input-number v-model:value="sku.costPrice" :min="0" :precision="2" />
              </n-form-item-gi>
              <n-form-item-gi label="库存">
                <n-input-number v-model:value="sku.stock" :min="0" />
              </n-form-item-gi>
              <n-form-item-gi label="重量(kg)">
                <n-input-number v-model:value="sku.weight" :min="0" :precision="3" />
              </n-form-item-gi>
              <n-form-item-gi label="规格值" :span="2">
                <n-select
                  v-model:value="sku.specValues"
                  multiple
                  :options="specValueOptions"
                  placeholder="选择该组合的规格值"
                />
              </n-form-item-gi>
            </n-grid>
            <template #footer>
              <n-space justify="end">
                <n-button size="small" type="error" @click="skus.splice(index, 1)">删除该 SKU</n-button>
              </n-space>
            </template>
          </n-card>
          <n-button size="small" @click="addSku">添加 SKU</n-button>
        </n-space>
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
import { NButton, NImage, NTag, useDialog, useMessage } from 'naive-ui'
import { computed, h, onMounted, reactive, ref } from 'vue'

import {
  categoryTree,
  changeGoodsStatus,
  createGoods,
  deleteGoods,
  getGoods,
  listFreightTemplates,
  pageGoods,
  updateGoods,
} from '@/api/mall'
import { usePermissionStore } from '@/stores/permission'
import type { Id, PageResult } from '@/types/api'
import type {
  CategoryTreeNode,
  GoodsSaveRequest,
  GoodsView,
  SkuSaveRequest,
  SpecSaveRequest,
} from '@/types/mall'
import type { DataTableColumns, FormInst, FormRules, SelectOption, TreeSelectOption } from 'naive-ui'

const message = useMessage()
const dialog = useDialog()
const permission = usePermissionStore()

const loading = ref(false)
const submitting = ref(false)
const rows = ref<GoodsView[]>([])
const total = ref(0)
const categoryOptions = ref<TreeSelectOption[]>([])
const freightOptions = ref<SelectOption[]>([])

const query = reactive<{ goodsName?: string; categoryId?: Id | null; status?: number | null; pageNo: number; pageSize: number }>(
  { goodsName: '', categoryId: null, status: null, pageNo: 1, pageSize: 10 },
)

const statusOptions: SelectOption[] = [
  { label: '上架', value: 1 },
  { label: '下架', value: 0 },
]

const pagination = computed(() => ({
  page: query.pageNo,
  pageSize: query.pageSize,
  itemCount: total.value,
  showSizePicker: false,
}))

const formVisible = ref(false)
const editingId = ref<Id | null>(null)
const formRef = ref<FormInst | null>(null)
const specValueOptions = ref<SelectOption[]>([])
const imagesText = ref('')

const form = reactive<GoodsSaveRequest>({
  // Id 是字符串(雪花 ID 超出 JS 安全整数,前端统一按字符串传),
  // 所以初始值是 null 而不是 0;reactive 的初始对象必须给全必填字段,否则类型检查直接报错
  categoryId: null,
  goodsName: '',
  goodsSubtitle: '',
  mainImage: '',
  detailContent: '',
  freightTemplateId: null,
  sortOrder: 0,
  status: 0,
  images: [],
  specs: [],
  skus: [],
})
const specs = ref<SpecSaveRequest[]>([])
const skus = ref<SkuSaveRequest[]>([])

const formRules: FormRules = {
  goodsName: { required: true, message: '请输入商品名称', trigger: ['blur', 'input'] },
  categoryId: { required: true, type: 'number', message: '请选择分类', trigger: ['change', 'blur'] },
  mainImage: { required: true, message: '请填写主图地址', trigger: ['blur', 'input'] },
}

const columns: DataTableColumns<GoodsView> = [
  {
    title: '主图',
    key: 'mainImage',
    width: 80,
    render: (row) => h(NImage, { src: row.mainImage, width: 48, height: 48, objectFit: 'cover' }),
  },
  { title: '商品名称', key: 'goodsName' },
  { title: '分类', key: 'categoryName', width: 120, render: (row) => row.categoryName ?? '-' },
  {
    title: '价格',
    key: 'price',
    width: 140,
    render: (row) =>
      row.salePriceMin === row.salePriceMax
        ? `¥${row.salePriceMin}`
        : `¥${row.salePriceMin} ~ ¥${row.salePriceMax}`,
  },
  { title: '总库存', key: 'totalStock', width: 90 },
  { title: '销量', key: 'saleCount', width: 80 },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render: (row) =>
      h(
        NTag,
        { size: 'small', type: row.status === 1 ? 'success' : 'default', bordered: false },
        { default: () => (row.status === 1 ? '上架' : '下架') },
      ),
  },
  {
    title: '操作',
    key: 'actions',
    width: 220,
    render: (row) =>
      h('div', { style: 'display:flex;gap:8px' }, [
        permission.hasPerm('mall:goods:update')
          ? h(NButton, { size: 'tiny', onClick: () => openEdit(row.id) }, { default: () => '编辑' })
          : null,
        permission.hasPerm('mall:goods:status')
          ? h(
              NButton,
              { size: 'tiny', onClick: () => toggleStatus(row) },
              { default: () => (row.status === 1 ? '下架' : '上架') },
            )
          : null,
        permission.hasPerm('mall:goods:delete')
          ? h(
              NButton,
              { size: 'tiny', type: 'error', onClick: () => confirmDelete(row) },
              { default: () => '删除' },
            )
          : null,
      ]),
  },
]

async function load(): Promise<void> {
  loading.value = true
  try {
    const result: PageResult<GoodsView> = await pageGoods({ ...query })
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
  query.goodsName = ''
  query.categoryId = null
  query.status = null
  search()
}

function onPageChange(page: number): void {
  query.pageNo = page
  void load()
}

function toCategoryOptions(nodes: CategoryTreeNode[]): TreeSelectOption[] {
  return nodes.map((node) => ({
    key: node.id,
    label: node.categoryName,
    children: node.children?.length ? toCategoryOptions(node.children) : undefined,
  }))
}

function splitValues(value: string): string[] {
  return value
    .split(/[,，]/)
    .map((item) => item.trim())
    .filter((item) => item.length > 0)
}

/** 规格值选项 = 所有规格下已填值的并集(用户填完规格再来选 SKU 的规格值) */
function refreshSpecValueOptions(): void {
  const options = new Set<string>()
  for (const spec of specs.value) {
    for (const value of spec.values) {
      options.add(value)
    }
  }
  specValueOptions.value = [...options].map((value) => ({ label: value, value }))
}

function addSku(): void {
  refreshSpecValueOptions()
  skus.value.push({
    skuCode: '',
    skuName: '',
    price: 0,
    costPrice: null,
    stock: 0,
    weight: null,
    status: 1,
    specValues: [],
  })
}

function openCreate(): void {
  editingId.value = null
  // 逐字段赋值而不是 Object.assign:后者在可选字段上会被推断成"缺字段"的对象类型,
  // 与表单模型的可空字段对不上时会报一堆无意义的类型错误
  form.categoryId = null
  form.goodsName = ''
  form.goodsSubtitle = ''
  form.mainImage = ''
  form.detailContent = ''
  form.freightTemplateId = null
  form.sortOrder = 0
  form.status = 0
  imagesText.value = ''
  specs.value = []
  skus.value = [{ skuCode: '', skuName: '默认规格', price: 0, costPrice: null, stock: 0, weight: null, status: 1, specValues: [] }]
  refreshSpecValueOptions()
  formVisible.value = true
}

async function openEdit(goodsId: Id): Promise<void> {
  const detail = await getGoods(goodsId)
  editingId.value = goodsId
  form.categoryId = detail.categoryId
  form.goodsName = detail.goodsName
  form.goodsSubtitle = detail.goodsSubtitle ?? ''
  form.mainImage = detail.mainImage
  form.detailContent = detail.detailContent ?? ''
  form.freightTemplateId = detail.freightTemplateId ?? null
  form.sortOrder = detail.sortOrder
  form.status = detail.status
  imagesText.value = (detail.images ?? []).join('\n')
  specs.value = (detail.specs ?? []).map((spec) => ({
    specName: spec.specName,
    values: spec.values.map((value) => value.specValue),
  }))
  skus.value = (detail.skus ?? []).map((sku) => ({
    id: sku.id,
    skuCode: sku.skuCode,
    skuName: sku.skuName,
    skuImage: sku.skuImage ?? null,
    price: sku.price,
    costPrice: sku.costPrice ?? null,
    stock: sku.stock,
    weight: sku.weight ?? null,
    status: sku.status,
    specValues: sku.specValues ?? [],
  }))
  refreshSpecValueOptions()
  formVisible.value = true
}

async function onSubmit(): Promise<void> {
  await formRef.value?.validate()
  if (skus.value.length === 0) {
    message.warning('至少需要一个 SKU')
    return
  }
  refreshSpecValueOptions()
  submitting.value = true
  try {
    const payload: GoodsSaveRequest = {
      categoryId: form.categoryId,
      goodsName: form.goodsName,
      goodsSubtitle: form.goodsSubtitle || null,
      mainImage: form.mainImage,
      detailContent: form.detailContent || null,
      freightTemplateId: form.freightTemplateId ?? null,
      sortOrder: form.sortOrder,
      status: form.status,
      images: imagesText.value
        .split('\n')
        .map((line) => line.trim())
        .filter((line) => line.length > 0),
      specs: specs.value.filter((spec) => spec.specName.trim().length > 0),
      skus: skus.value,
    }
    if (editingId.value == null) {
      await createGoods(payload)
    } else {
      await updateGoods(editingId.value, payload)
    }
    message.success('保存成功')
    formVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

async function toggleStatus(row: GoodsView): Promise<void> {
  const next = row.status === 1 ? 0 : 1
  await changeGoodsStatus(row.id, next)
  message.success(next === 1 ? '已上架' : '已下架')
  await load()
}

function confirmDelete(row: GoodsView): void {
  dialog.warning({
    title: '确认删除',
    content: `确定删除商品「${row.goodsName}」吗?有订单记录的商品只能下架,后端会拒绝删除。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      await deleteGoods(row.id)
      message.success('已删除')
      await load()
    },
  })
}

onMounted(async () => {
  const [categories, templates] = await Promise.all([categoryTree(), listFreightTemplates()])
  categoryOptions.value = toCategoryOptions(categories)
  freightOptions.value = templates.map((template) => ({
    label: template.templateName,
    value: template.id,
  }))
  await load()
})
</script>
