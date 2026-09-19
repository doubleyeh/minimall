<template>
  <n-space vertical :size="16">
    <n-card>
      <template #header>
        <n-space justify="space-between" align="center">
          <span>商品分类</span>
          <n-space>
            <n-button @click="load">刷新</n-button>
            <n-button v-perm="'mall:category:create'" type="primary" @click="openCreate(null)">
              新增分类
            </n-button>
          </n-space>
        </n-space>
      </template>

      <n-data-table
        :columns="columns"
        :data="rows"
        :loading="loading"
        :row-key="(row: CategoryTreeNode) => row.id"
        :default-expand-all="true"
      />
    </n-card>

    <n-modal
      v-model:show="formVisible"
      preset="card"
      :title="editing ? '编辑分类' : '新增分类'"
      style="width: 520px"
    >
      <n-form ref="formRef" :model="form" :rules="formRules" label-placement="top">
        <n-form-item label="上级分类" path="parentId">
          <n-tree-select
            v-model:value="form.parentId"
            :options="parentOptions"
            placeholder="顶级分类请选择「顶级分类」"
          />
        </n-form-item>
        <n-form-item label="分类名称" path="categoryName">
          <n-input v-model:value="form.categoryName" />
        </n-form-item>
        <n-form-item label="图标地址" path="icon">
          <n-input v-model:value="form.icon" placeholder="可空" />
        </n-form-item>
        <n-form-item label="排序" path="sortOrder">
          <n-input-number v-model:value="form.sortOrder" :min="0" />
        </n-form-item>
        <n-form-item label="状态">
          <n-radio-group v-model:value="form.status">
            <n-radio :value="1">启用</n-radio>
            <n-radio :value="0">停用</n-radio>
          </n-radio-group>
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
import { NButton, NTag, useDialog, useMessage } from 'naive-ui'
import { h, onMounted, reactive, ref } from 'vue'

import { categoryTree, createCategory, deleteCategory, updateCategory } from '@/api/mall'
import { usePermissionStore } from '@/stores/permission'
import type { Id } from '@/types/api'
import type { CategorySaveRequest, CategoryTreeNode } from '@/types/mall'
import type { DataTableColumns, FormInst, FormRules, TreeSelectOption } from 'naive-ui'

const message = useMessage()
const dialog = useDialog()
const permission = usePermissionStore()

const loading = ref(false)
const submitting = ref(false)
const rows = ref<CategoryTreeNode[]>([])
const parentOptions = ref<TreeSelectOption[]>([])

/** 「顶级分类」用 0 表示(后端约定 parentId = 0) */
const ROOT_KEY = '0'

const formVisible = ref(false)
const editing = ref<Id | null>(null)
const formRef = ref<FormInst | null>(null)
const form = reactive<CategorySaveRequest>({
  parentId: ROOT_KEY,
  categoryName: '',
  icon: '',
  sortOrder: 0,
  status: 1,
})

const formRules: FormRules = {
  categoryName: { required: true, message: '请输入分类名称', trigger: ['blur', 'input'] },
}

function toParentOptions(nodes: CategoryTreeNode[]): TreeSelectOption[] {
  return nodes.map((node) => ({
    key: node.id,
    label: node.categoryName,
    // 分类最多两级:已经有下级的节点不再作为"可选的上级"(后端也会拒绝)
    children: undefined,
  }))
}

async function load(): Promise<void> {
  loading.value = true
  try {
    rows.value = await categoryTree()
    const flat: CategoryTreeNode[] = []
    const walk = (nodes: CategoryTreeNode[]): void => {
      for (const node of nodes) {
        flat.push(node)
        if (node.children?.length) {
          walk(node.children)
        }
      }
    }
    walk(rows.value)
    // 一级分类才能作为上级:把有 parentId = 0 的节点列出来
    parentOptions.value = [
      { key: ROOT_KEY, label: '顶级分类' },
      ...toParentOptions(flat.filter((node) => Number(node.parentId) === 0)),
    ]
  } finally {
    loading.value = false
  }
}

const columns: DataTableColumns<CategoryTreeNode> = [
  { title: '分类名称', key: 'categoryName' },
  { title: '排序', key: 'sortOrder', width: 90 },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render: (row) =>
      h(
        NTag,
        { size: 'small', type: row.status === 1 ? 'success' : 'default', bordered: false },
        { default: () => (row.status === 1 ? '启用' : '停用') },
      ),
  },
  {
    title: '操作',
    key: 'actions',
    width: 260,
    render: (row) =>
      h('div', { style: 'display:flex;gap:8px' }, [
        permission.hasPerm('mall:category:create') && Number(row.parentId) === 0
          ? h(NButton, { size: 'tiny', onClick: () => openCreate(row.id) }, { default: () => '新增下级' })
          : null,
        permission.hasPerm('mall:category:update')
          ? h(NButton, { size: 'tiny', onClick: () => openEdit(row) }, { default: () => '编辑' })
          : null,
        permission.hasPerm('mall:category:delete')
          ? h(
              NButton,
              { size: 'tiny', type: 'error', onClick: () => confirmDelete(row) },
              { default: () => '删除' },
            )
          : null,
      ]),
  },
]

function openCreate(parentId: Id | null): void {
  editing.value = null
  form.parentId = parentId == null ? ROOT_KEY : parentId
  form.categoryName = ''
  form.icon = ''
  form.sortOrder = 0
  form.status = 1
  formVisible.value = true
}

function openEdit(row: CategoryTreeNode): void {
  editing.value = row.id
  form.parentId = Number(row.parentId) === 0 ? ROOT_KEY : row.parentId
  form.categoryName = row.categoryName
  form.icon = row.icon ?? ''
  form.sortOrder = row.sortOrder
  form.status = row.status
  formVisible.value = true
}

async function onSubmit(): Promise<void> {
  await formRef.value?.validate()
  submitting.value = true
  try {
    const payload: CategorySaveRequest = {
      // Id 在前端统一是字符串(雪花 ID 超出 JS 安全整数),TreeSelect 的值也按字符串处理,
      // 顶层分类固定用 '0'(后端约定 parentId = 0)
      parentId: form.parentId == null || String(form.parentId) === '0' ? '0' : String(form.parentId),
      categoryName: form.categoryName,
      icon: form.icon || null,
      sortOrder: form.sortOrder,
      status: form.status,
    }
    if (editing.value == null) {
      await createCategory(payload)
    } else {
      await updateCategory(editing.value, payload)
    }
    message.success('保存成功')
    formVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

function confirmDelete(row: CategoryTreeNode): void {
  dialog.warning({
    title: '确认删除',
    content: `确定删除分类「${row.categoryName}」吗?有下级分类或已被商品引用时后端会拒绝。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      await deleteCategory(row.id)
      message.success('已删除')
      await load()
    },
  })
}

onMounted(load)
</script>
