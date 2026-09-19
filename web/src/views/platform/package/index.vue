<template>
  <n-space vertical :size="16">
    <n-alert type="info">
      套餐决定租户能用哪些菜单。保存套餐菜单会立即对所有绑定该套餐的租户生效,
      租户较多时这个请求会比较慢;有租户在用的套餐只能禁用,不能删除。
    </n-alert>

    <n-card>
      <n-form inline :model="query" label-placement="left">
        <n-form-item label="套餐名称">
          <n-input v-model:value="query.packageName" clearable placeholder="模糊匹配" style="width: 180px" />
        </n-form-item>
        <n-form-item label="状态">
          <n-select v-model:value="query.status" :options="statusOptions" clearable placeholder="全部" style="width: 120px" />
        </n-form-item>
        <n-form-item>
          <n-space>
            <n-button type="primary" @click="load(1)">查询</n-button>
            <n-button @click="onResetQuery">重置</n-button>
          </n-space>
        </n-form-item>
      </n-form>
    </n-card>

    <n-card>
      <template #header>
        <n-space justify="space-between" align="center">
          <span>套餐列表</span>
          <n-button v-perm="'system:package:create'" type="primary" @click="openCreate">新增套餐</n-button>
        </n-space>
      </template>

      <n-data-table
        :columns="columns"
        :data="rows"
        :loading="loading"
        :pagination="pagination"
        :row-key="(row: PackageView) => row.id"
        remote
        @update:page="load"
      />
    </n-card>

    <n-modal v-model:show="formVisible" preset="card" :title="editing ? '编辑套餐' : '新增套餐'" style="width: 520px">
      <n-form ref="formRef" :model="form" :rules="formRules" label-placement="top">
        <n-form-item label="套餐名称" path="packageName"><n-input v-model:value="form.packageName" /></n-form-item>
        <n-form-item label="备注"><n-input v-model:value="form.remark" type="textarea" :rows="3" /></n-form-item>
        <n-form-item label="状态">
          <n-radio-group v-model:value="form.status">
            <n-radio :value="1">启用</n-radio>
            <n-radio :value="0">禁用</n-radio>
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

    <n-drawer v-model:show="menuVisible" :width="460">
      <n-drawer-content :title="`套餐菜单 - ${menuPackageName}`" closable>
        <n-alert type="warning" class="mb-12">
          保存后立即同步到所有绑定该套餐的租户:新增只补默认管理员角色,收回对该租户全部角色生效。
        </n-alert>
        <n-spin :show="menuLoading">
          <n-tree
            v-model:checked-keys="checkedKeys"
            v-model:indeterminate-keys="indeterminateKeys"
            :data="menuTreeData"
            checkable
            key-field="key"
            label-field="label"
            block-line
            :default-expand-all="false"
          />
        </n-spin>
        <template #footer>
          <n-space justify="end">
            <n-button @click="menuVisible = false">取消</n-button>
            <n-button type="primary" :loading="submitting" @click="onSubmitMenus">保存</n-button>
          </n-space>
        </template>
      </n-drawer-content>
    </n-drawer>
  </n-space>
</template>

<script setup lang="ts">
import { NButton, NTag, useDialog, useMessage } from 'naive-ui'
import { h, onMounted, reactive, ref } from 'vue'

import {
  createPackage,
  disablePackage,
  packageGrantableMenus,
  packageMenus,
  pagePackages,
  resyncPackage,
  savePackageMenus,
  updatePackage,
} from '@/api/package'
import { usePermissionStore } from '@/stores/permission'
import type { Id } from '@/types/api'
import type { DataTableColumns, FormInst, FormRules, SelectOption } from 'naive-ui'
import type { MenuTreeNode, PackageSaveRequest, PackageView } from '@/types/system'

const message = useMessage()
const dialog = useDialog()
const permission = usePermissionStore()

const loading = ref(false)
const submitting = ref(false)
const rows = ref<PackageView[]>([])

const query = reactive<{ packageName: string; status: number | null; pageNo: number; pageSize: number }>({
  packageName: '',
  status: null,
  pageNo: 1,
  pageSize: 10,
})

const statusOptions: SelectOption[] = [
  { label: '启用', value: 1 },
  { label: '禁用', value: 0 },
]

const pagination = reactive({ page: 1, pageSize: 10, itemCount: 0, showSizePicker: false })

async function load(page = query.pageNo): Promise<void> {
  loading.value = true
  try {
    query.pageNo = page
    const result = await pagePackages({
      packageName: query.packageName || undefined,
      status: query.status,
      pageNo: page,
      pageSize: query.pageSize,
    })
    rows.value = result.list
    pagination.page = page
    pagination.itemCount = result.total
  } finally {
    loading.value = false
  }
}

function onResetQuery(): void {
  query.packageName = ''
  query.status = null
  void load(1)
}

const columns: DataTableColumns<PackageView> = [
  { title: '套餐名称', key: 'packageName', width: 200 },
  { title: '备注', key: 'remark', render: (row) => row.remark ?? '-' },
  { title: '菜单数', key: 'menuCount', width: 100 },
  {
    title: '使用租户',
    key: 'tenantCount',
    width: 110,
    render: (row) => `${row.tenantCount} 个`,
  },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render: (row) =>
      h(
        NTag,
        { size: 'small', type: row.status === 1 ? 'success' : 'default', bordered: false },
        { default: () => (row.status === 1 ? '启用' : '禁用') },
      ),
  },
  {
    title: '操作',
    key: 'actions',
    width: 340,
    render: (row) =>
      h('div', { style: 'display:flex;gap:8px;flex-wrap:wrap' }, [
        permission.hasPerm('system:package:update')
          ? h(NButton, { size: 'tiny', onClick: () => openMenus(row) }, { default: () => '菜单配置' })
          : null,
        permission.hasPerm('system:package:update')
          ? h(NButton, { size: 'tiny', onClick: () => resync(row) }, { default: () => '重新同步' })
          : null,
        permission.hasPerm('system:package:update')
          ? h(NButton, { size: 'tiny', onClick: () => openEdit(row) }, { default: () => '编辑' })
          : null,
        permission.hasPerm('system:package:update') && row.status === 1
          ? h(
              NButton,
              { size: 'tiny', type: 'error', ghost: true, onClick: () => onDisable(row) },
              { default: () => '禁用' },
            )
          : null,
      ]),
  },
]

// ——— 新增 / 编辑 ———

const formVisible = ref(false)
const editing = ref(false)
const editingId = ref<Id | null>(null)
const formRef = ref<FormInst | null>(null)
const form = reactive<{ packageName: string; remark: string; status: number }>({
  packageName: '',
  remark: '',
  status: 1,
})

const formRules: FormRules = {
  packageName: [{ required: true, message: '请输入套餐名称', trigger: ['input', 'blur'] }],
}

function openCreate(): void {
  editing.value = false
  editingId.value = null
  Object.assign(form, { packageName: '', remark: '', status: 1 })
  formVisible.value = true
}

function openEdit(row: PackageView): void {
  editing.value = true
  editingId.value = row.id
  Object.assign(form, { packageName: row.packageName, remark: row.remark ?? '', status: row.status })
  formVisible.value = true
}

async function onSubmit(): Promise<void> {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  const payload: PackageSaveRequest = {
    packageName: form.packageName,
    remark: form.remark || null,
    status: form.status,
  }
  submitting.value = true
  try {
    if (editing.value && editingId.value) {
      await updatePackage(editingId.value, payload)
    } else {
      await createPackage(payload)
    }
    message.success('已保存')
    formVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

// ——— 菜单配置 ———

const menuVisible = ref(false)
const menuLoading = ref(false)
const menuPackageId = ref<Id | null>(null)
const menuPackageName = ref('')
/** 与 role 页面同理:children 必须是自引用的具体类型,unknown[] 与 TreeOption 不兼容 */
interface MenuNode {
  key: Id
  label: string
  children?: MenuNode[]
}

const menuTreeData = ref<MenuNode[]>([])
const checkedKeys = ref<Id[]>([])
const indeterminateKeys = ref<Id[]>([])

function toTree(nodes: MenuTreeNode[]): MenuNode[] {
  return nodes.map((node) => ({
    key: node.id,
    label: node.permCode ? `${node.menuName}(${node.permCode})` : node.menuName,
    children: node.children?.length ? toTree(node.children) : undefined,
  }))
}

async function openMenus(row: PackageView): Promise<void> {
  menuVisible.value = true
  menuPackageId.value = row.id
  menuPackageName.value = row.packageName
  menuLoading.value = true
  checkedKeys.value = []
  indeterminateKeys.value = []
  try {
    const [tree, ids] = await Promise.all([packageGrantableMenus(row.id), packageMenus(row.id)])
    menuTreeData.value = toTree(tree)
    checkedKeys.value = ids
  } finally {
    menuLoading.value = false
  }
}

async function onSubmitMenus(): Promise<void> {
  if (!menuPackageId.value) {
    return
  }
  // 半选父节点在 indeterminate 里,合并后即"含全部祖先",与后端"父链必须完整"的校验一致
  const menuIds = Array.from(new Set([...checkedKeys.value, ...indeterminateKeys.value]))
  submitting.value = true
  try {
    await savePackageMenus(menuPackageId.value, menuIds)
    message.success('已保存,并已对所有绑定该套餐的租户同步')
    menuVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

// ——— 行内操作 ———

function onDisable(row: PackageView): void {
  dialog.warning({
    title: '禁用套餐',
    content: `确认禁用「${row.packageName}」?仍有 ${row.tenantCount} 个租户在使用时请先为其更换套餐。`,
    positiveText: '确认禁用',
    negativeText: '取消',
    onPositiveClick: async () => {
      await disablePackage(row.id)
      message.success('已禁用')
      await load()
    },
  })
}

async function resync(row: PackageView): Promise<void> {
  await resyncPackage(row.id)
  message.success('已重新同步:停留在"未同步"状态的租户已收敛')
}

onMounted(() => void load(1))
</script>

<style scoped>
.mb-12 {
  margin-bottom: 12px;
}
</style>
