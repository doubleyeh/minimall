<template>
  <div class="goods-page">
    <!-- 左:分类树。选中即筛选右侧列表 -->
    <n-card class="goods-page__side" size="small">
      <template #header>
        <div class="goods-page__side-head">
          <span>商品分类</span>
          <n-space :size="4">
            <n-button quaternary size="tiny" title="刷新" @click="loadCategories">
              <template #icon><n-icon :component="RefreshOutline" /></template>
            </n-button>
            <n-button
              v-perm="'mall:category:create'"
              quaternary
              size="tiny"
              title="新增顶级分类"
              @click="openCreate(null)"
            >
              <template #icon><n-icon :component="AddOutline" /></template>
            </n-button>
          </n-space>
        </div>
      </template>

      <n-spin :show="categoryLoading">
        <n-tree
          block-line
          selectable
          expand-on-click
          :data="treeData"
          :selected-keys="[selectedKey]"
          :expanded-keys="expandedKeys"
          :render-suffix="renderCategoryActions"
          @update:selected-keys="onSelectCategory"
          @update:expanded-keys="onExpand"
        />
      </n-spin>
    </n-card>

    <!-- 右:该分类下的商品 -->
    <n-card class="goods-page__main" size="small">
      <template #header>
        <div class="goods-page__main-head">
          <n-space align="center" :size="8">
            <span>商品列表</span>
            <n-tag v-if="selectedCategory" size="small" closable :bordered="false" @close="clearCategory">
              {{ selectedCategory.categoryName }}
            </n-tag>
            <n-text v-else depth="3">全部分类</n-text>
          </n-space>
          <span class="goods-page__count">共 {{ total }} 个</span>
        </div>
      </template>

      <n-form inline :model="query" label-placement="left">
        <n-form-item label="商品名称">
          <n-input
            v-model:value="query.goodsName"
            clearable
            placeholder="支持模糊搜索"
            style="width: 180px"
            @keyup.enter="search"
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
            <n-button v-perm="'mall:goods:create'" type="primary" ghost @click="openCreateGoods">
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
        max-height="var(--mm-table-max-h)"
        :pagination="pagination"
        @update:page="onPageChange"
      />
    </n-card>

    <!-- 分类表单:与原来的分类页是同一套 -->
    <n-modal
      v-model:show="formVisible"
      preset="card"
      :title="editing ? '编辑分类' : '新增分类'"
      style="width: 520px"
    >
      <n-form ref="formRef" :model="categoryForm" :rules="formRules" label-placement="top">
        <n-form-item label="上级分类" path="parentId">
          <n-tree-select
            v-model:value="categoryForm.parentId"
            :options="parentOptions"
            placeholder="顶级分类请选择「顶级分类」"
          />
        </n-form-item>
        <n-form-item label="分类名称" path="categoryName">
          <n-input v-model:value="categoryForm.categoryName" />
        </n-form-item>
        <n-form-item label="图标地址" path="icon">
          <n-input v-model:value="categoryForm.icon" placeholder="可空" />
        </n-form-item>
        <n-form-item label="排序" path="sortOrder">
          <n-input-number v-model:value="categoryForm.sortOrder" :min="0" />
        </n-form-item>
        <n-form-item label="状态">
          <n-radio-group v-model:value="categoryForm.status">
            <n-radio :value="1">启用</n-radio>
            <n-radio :value="0">停用</n-radio>
          </n-radio-group>
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button @click="formVisible = false">取消</n-button>
          <n-button type="primary" :loading="submitting" @click="onSubmitCategory">保存</n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- 商品表单:未改动 -->
    <n-modal
      v-model:show="goodsVisible"
      preset="card"
      :title="editingId ? '编辑商品' : '新增商品'"
      style="width: 940px"
    >
      <n-form ref="goodsFormRef" :model="form" :rules="goodsRules" label-placement="top">
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
          <n-button @click="goodsVisible = false">取消</n-button>
          <n-button type="primary" :loading="submitting" @click="onSubmitGoods">保存</n-button>
        </n-space>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { AddOutline, CreateOutline, RefreshOutline, TrashOutline } from '@vicons/ionicons5'
import { NButton, NIcon, NImage, NTag, useDialog, useMessage } from 'naive-ui'
import { computed, h, onMounted, reactive, ref } from 'vue'

import {
  categoryTree,
  changeGoodsStatus,
  createCategory,
  createGoods,
  deleteCategory,
  deleteGoods,
  getGoods,
  listFreightTemplates,
  pageGoods,
  updateCategory,
  updateGoods,
} from '@/api/mall'
import { usePermissionStore } from '@/stores/permission'
import type { Id, PageResult } from '@/types/api'
import type {
  CategorySaveRequest,
  CategoryTreeNode,
  GoodsSaveRequest,
  GoodsView,
  SkuSaveRequest,
  SpecSaveRequest,
} from '@/types/mall'
import type {
  DataTableColumns,
  FormInst,
  FormRules,
  SelectOption,
  TreeOption,
  TreeSelectOption,
} from 'naive-ui'

/** 商品管理:左分类树(选中即筛选)+ 右商品列表。分类的增删改挂在树节点后缀上。 */

const message = useMessage()
const dialog = useDialog()
const permission = usePermissionStore()

/** 虚拟根节点,选中等于不加分类条件 */
const ALL_KEY = 'all'
const ROOT_KEY = '0'

// ---------------------------------------------------------------- 分类树

const categoryLoading = ref(false)
const categories = ref<CategoryTreeNode[]>([])
const categoryById = ref(new Map<string, CategoryTreeNode>())
const selectedKey = ref<string>(ALL_KEY)
const expandedKeys = ref<string[]>([ALL_KEY])
const parentOptions = ref<TreeSelectOption[]>([])

const selectedCategory = computed(() =>
  selectedKey.value === ALL_KEY ? null : (categoryById.value.get(selectedKey.value) ?? null),
)

const treeData = computed<TreeOption[]>(() => [
  {
    key: ALL_KEY,
    label: '全部分类',
    children: categories.value.map(toTreeOption),
  },
])

/** 新增下级只在一级节点上出现(分类最多两级) */
function renderCategoryActions(info: { option: TreeOption }): ReturnType<typeof h> {
  const key = String(info.option.key)
  const row = categoryById.value.get(key)
  if (!row) {
    // 根节点只提供"新增顶级分类"
    return h(
      'span',
      { class: ['cat-actions', { 'cat-actions--active': selectedKey.value === key }] },
      [
        permission.hasPerm('mall:category:create')
          ? h(
              NButton,
              {
                size: 'tiny',
                quaternary: true,
                title: '新增顶级分类',
                onClick: (e: MouseEvent) => {
                  e.stopPropagation()
                  openCreate(null)
                },
              },
              { icon: () => h(NIcon, { component: AddOutline }) },
            )
          : null,
      ],
    )
  }
  const isTopLevel = Number(row.parentId) === 0
  return h(
    'span',
    { class: ['cat-actions', { 'cat-actions--active': selectedKey.value === key }] },
    [
      isTopLevel && permission.hasPerm('mall:category:create')
        ? h(
            NButton,
            {
              size: 'tiny',
              quaternary: true,
              title: '新增下级',
              onClick: (e: MouseEvent) => {
                e.stopPropagation()
                openCreate(row.id)
              },
            },
            { icon: () => h(NIcon, { component: AddOutline }) },
          )
        : null,
      permission.hasPerm('mall:category:update')
        ? h(
            NButton,
            {
              size: 'tiny',
              quaternary: true,
              title: '编辑',
              onClick: (e: MouseEvent) => {
                e.stopPropagation()
                openEditCategory(row)
              },
            },
            { icon: () => h(NIcon, { component: CreateOutline }) },
          )
        : null,
      permission.hasPerm('mall:category:delete')
        ? h(
            NButton,
            {
              size: 'tiny',
              quaternary: true,
              title: '删除',
              onClick: (e: MouseEvent) => {
                e.stopPropagation()
                confirmDeleteCategory(row)
              },
            },
            { icon: () => h(NIcon, { component: TrashOutline }) },
          )
        : null,
    ],
  )
}

function toTreeOption(node: CategoryTreeNode): TreeOption {
  return {
    key: node.id,
    label: node.categoryName,
    children: node.children?.length ? node.children.map(toTreeOption) : undefined,
  }
}

function toCategoryOptions(nodes: CategoryTreeNode[]): TreeSelectOption[] {
  return nodes.map((node) => ({
    key: node.id,
    label: node.categoryName,
    children: node.children?.length ? toCategoryOptions(node.children) : undefined,
  }))
}

async function loadCategories(): Promise<void> {
  categoryLoading.value = true
  try {
    const tree = await categoryTree()
    categories.value = tree
    const map = new Map<string, CategoryTreeNode>()
    const flat: CategoryTreeNode[] = []
    const walk = (nodes: CategoryTreeNode[]): void => {
      for (const node of nodes) {
        map.set(String(node.id), node)
        flat.push(node)
        if (node.children?.length) {
          walk(node.children)
        }
      }
    }
    walk(tree)
    categoryById.value = map
    categoryOptions.value = toCategoryOptions(tree)
    parentOptions.value = [
      { key: ROOT_KEY, label: '顶级分类' },
      ...flat
        .filter((node) => Number(node.parentId) === 0)
        .map((node) => ({ key: node.id, label: node.categoryName })),
    ]
    expandedKeys.value = [ALL_KEY, ...Array.from(map.keys())]
    // 选中的分类被删掉了就退回"全部分类"
    if (selectedKey.value !== ALL_KEY && !map.has(selectedKey.value)) {
      selectedKey.value = ALL_KEY
      query.categoryId = null
    }
  } finally {
    categoryLoading.value = false
  }
}

function onSelectCategory(keys: Array<string | number>): void {
  const key = keys.length ? String(keys[0]) : ALL_KEY
  selectedKey.value = key
  query.categoryId = key === ALL_KEY ? null : key
  query.pageNo = 1
  void loadGoods()
}

/** 展开键受控:分类异步取回,default-expanded-keys 只在首次渲染生效,展不开后到的节点 */
function onExpand(keys: Array<string | number>): void {
  expandedKeys.value = keys.map(String)
}

function clearCategory(): void {
  onSelectCategory([ALL_KEY])
}

// ---------------------------------------------------------------- 分类表单

const formVisible = ref(false)
const editing = ref<Id | null>(null)
const formRef = ref<FormInst | null>(null)
const submitting = ref(false)
const categoryForm = reactive<CategorySaveRequest>({
  parentId: ROOT_KEY,
  categoryName: '',
  icon: '',
  sortOrder: 0,
  status: 1,
})

const formRules: FormRules = {
  categoryName: { required: true, message: '请输入分类名称', trigger: ['blur', 'input'] },
}

function openCreate(parentId: Id | null): void {
  editing.value = null
  categoryForm.parentId = parentId == null ? ROOT_KEY : parentId
  categoryForm.categoryName = ''
  categoryForm.icon = ''
  categoryForm.sortOrder = 0
  categoryForm.status = 1
  formVisible.value = true
}

function openEditCategory(row: CategoryTreeNode): void {
  editing.value = row.id
  categoryForm.parentId = Number(row.parentId) === 0 ? ROOT_KEY : row.parentId
  categoryForm.categoryName = row.categoryName
  categoryForm.icon = row.icon ?? ''
  categoryForm.sortOrder = row.sortOrder
  categoryForm.status = row.status
  formVisible.value = true
}

async function onSubmitCategory(): Promise<void> {
  await formRef.value?.validate()
  submitting.value = true
  try {
    const payload: CategorySaveRequest = {
      // Id 在前端统一是字符串(雪花 ID 超出 JS 安全整数),顶层分类固定用 '0'
      parentId:
        categoryForm.parentId == null || String(categoryForm.parentId) === ROOT_KEY
          ? ROOT_KEY
          : String(categoryForm.parentId),
      categoryName: categoryForm.categoryName,
      icon: categoryForm.icon || null,
      sortOrder: categoryForm.sortOrder,
      status: categoryForm.status,
    }
    if (editing.value == null) {
      await createCategory(payload)
    } else {
      await updateCategory(editing.value, payload)
    }
    message.success('保存成功')
    formVisible.value = false
    await loadCategories()
  } finally {
    submitting.value = false
  }
}

function confirmDeleteCategory(row: CategoryTreeNode): void {
  dialog.warning({
    title: '确认删除',
    content: `确定删除分类「${row.categoryName}」吗?有下级分类或已被商品引用时后端会拒绝。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      await deleteCategory(row.id)
      message.success('已删除')
      await loadCategories()
    },
  })
}

// ---------------------------------------------------------------- 商品列表

const loading = ref(false)
const rows = ref<GoodsView[]>([])
const total = ref(0)
const categoryOptions = ref<TreeSelectOption[]>([])
const freightOptions = ref<SelectOption[]>([])

const query = reactive<{
  goodsName?: string
  categoryId: Id | null
  status?: number | null
  pageNo: number
  pageSize: number
}>({ goodsName: '', categoryId: null, status: null, pageNo: 1, pageSize: 10 })

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
          ? h(NButton, { size: 'tiny', onClick: () => openEditGoods(row.id) }, { default: () => '编辑' })
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
              { size: 'tiny', type: 'error', onClick: () => confirmDeleteGoods(row) },
              { default: () => '删除' },
            )
          : null,
      ]),
  },
]

async function loadGoods(): Promise<void> {
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
  void loadGoods()
}

function resetQuery(): void {
  query.goodsName = ''
  query.status = null
  // 重置回到全部分类,树与列表一起回
  selectedKey.value = ALL_KEY
  query.categoryId = null
  search()
}

function onPageChange(page: number): void {
  query.pageNo = page
  void loadGoods()
}

// ---------------------------------------------------------------- 商品表单

const goodsVisible = ref(false)
const editingId = ref<Id | null>(null)
const goodsFormRef = ref<FormInst | null>(null)
const specValueOptions = ref<SelectOption[]>([])
const imagesText = ref('')

const form = reactive<GoodsSaveRequest>({
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

const goodsRules: FormRules = {
  goodsName: { required: true, message: '请输入商品名称', trigger: ['blur', 'input'] },
  categoryId: { required: true, type: 'number', message: '请选择分类', trigger: ['change', 'blur'] },
  mainImage: { required: true, message: '请填写主图地址', trigger: ['blur', 'input'] },
}

function splitValues(value: string): string[] {
  return value
    .split(/[,，]/)
    .map((item) => item.trim())
    .filter((item) => item.length > 0)
}

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

function openCreateGoods(): void {
  editingId.value = null
  // 新增商品默认落在左侧选中的分类下
  form.categoryId = selectedCategory.value?.id ?? null
  form.goodsName = ''
  form.goodsSubtitle = ''
  form.mainImage = ''
  form.detailContent = ''
  form.freightTemplateId = null
  form.sortOrder = 0
  form.status = 0
  imagesText.value = ''
  specs.value = []
  skus.value = [
    { skuCode: '', skuName: '默认规格', price: 0, costPrice: null, stock: 0, weight: null, status: 1, specValues: [] },
  ]
  refreshSpecValueOptions()
  goodsVisible.value = true
}

async function openEditGoods(goodsId: Id): Promise<void> {
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
  goodsVisible.value = true
}

async function onSubmitGoods(): Promise<void> {
  await goodsFormRef.value?.validate()
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
    goodsVisible.value = false
    await loadGoods()
  } finally {
    submitting.value = false
  }
}

async function toggleStatus(row: GoodsView): Promise<void> {
  const next = row.status === 1 ? 0 : 1
  await changeGoodsStatus(row.id, next)
  message.success(next === 1 ? '已上架' : '已下架')
  await loadGoods()
}

function confirmDeleteGoods(row: GoodsView): void {
  dialog.warning({
    title: '确认删除',
    content: `确定删除商品「${row.goodsName}」吗?有订单记录的商品只能下架,后端会拒绝删除。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      await deleteGoods(row.id)
      message.success('已删除')
      await loadGoods()
    },
  })
}

onMounted(async () => {
  const templates = await listFreightTemplates()
  freightOptions.value = templates.map((template) => ({
    label: template.templateName,
    value: template.id,
  }))
  await loadCategories()
  await loadGoods()
})
</script>

<style scoped>
/* 左栏 */
.goods-page {
  display: grid;
  grid-template-columns: 272px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

/* 分类多的时候树自己滚,不把卡片撑得比表格还高 */
.goods-page__side :deep(.n-card-content) {
  max-height: calc(100vh - 220px);
  overflow: auto;
}

.goods-page__side-head,
.goods-page__main-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.goods-page__count {
  font-size: 12px;
  color: var(--mm-text-3);
  font-variant-numeric: tabular-nums;
}

/* 节点后缀的操作按钮:默认隐藏,悬停或选中时显示 */
.cat-actions {
  display: inline-flex;
  gap: 2px;
  opacity: 0;
  transition: opacity 0.15s ease;
}

.cat-actions--active {
  opacity: 1;
}

.goods-page :deep(.n-tree-node:hover) .cat-actions {
  opacity: 1;
}

@media (max-width: 1100px) {
  .goods-page {
    grid-template-columns: minmax(0, 1fr);
  }

  .goods-page__side {
    position: static;
  }
}
</style>
