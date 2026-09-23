<template>
  <n-space vertical :size="16">
    <n-card>
      <n-form inline :model="query" label-placement="left">
        <n-form-item label="角色名称">
          <n-input v-model:value="query.roleName" clearable placeholder="模糊匹配" style="width: 180px" />
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
          <span>角色列表</span>
          <n-button v-perm="'system:role:create'" type="primary" @click="openCreate">新增角色</n-button>
        </n-space>
      </template>

      <n-data-table
        max-height="var(--mm-table-max-h)"
        :columns="columns"
        :data="rows"
        :loading="loading"
        :pagination="pagination"
        :row-key="(row: RoleView) => row.id"
        remote
        @update:page="load"
      />
    </n-card>

    <!-- 新增 / 编辑 -->
    <n-modal v-model:show="formVisible" preset="card" :title="editing ? '编辑角色' : '新增角色'" style="width: 560px">
      <n-form ref="formRef" :model="form" :rules="formRules" label-placement="top">
        <n-form-item label="角色标识" path="roleKey">
          <n-input v-model:value="form.roleKey" :disabled="editing" placeholder="2-64 位字母/数字/下划线,字母开头" />
        </n-form-item>
        <n-form-item label="角色名称" path="roleName"><n-input v-model:value="form.roleName" /></n-form-item>
        <n-form-item label="数据权限" path="dataScope">
          <n-select v-model:value="form.dataScope" :options="dataScopeOptions" @update:value="onDataScopeChange" />
        </n-form-item>
        <!-- 只有"自定义部门"档才显示部门树(前端文档 6.2) -->
        <n-form-item v-if="form.dataScope === 4" label="可见部门" path="deptIds">
          <n-tree-select
            v-model:value="form.deptIds"
            :options="deptOptions"
            multiple
            clearable
            placeholder="请选择可见部门"
          />
        </n-form-item>
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

    <!-- 菜单授权 -->
    <n-drawer v-model:show="grantVisible" :width="480">
      <n-drawer-content :title="`菜单授权 - ${grantRoleName}`" closable>
        <n-alert v-if="grantRoleIsDefault" type="info" class="mb-12">
          这是该租户的默认管理员角色:它的菜单由套餐同步维护,不支持人工增删。需要定制权限请新建自定义角色。
        </n-alert>
        <n-alert v-else type="warning" class="mb-12">
          候选菜单已按本租户套餐过滤。提交时会自动带上全部祖先节点,这是后端的硬校验(不做静默过滤)。
        </n-alert>

        <n-spin :show="grantLoading">
          <n-tree
            v-model:checked-keys="checkedKeys"
            v-model:indeterminate-keys="indeterminateKeys"
            :data="grantTree"
            checkable
            key-field="key"
            label-field="label"
            block-line
            :default-expand-all="false"
            :disabled="grantRoleIsDefault"
          />
        </n-spin>

        <template #footer>
          <n-space justify="end">
            <n-button @click="grantVisible = false">取消</n-button>
            <n-button
              type="primary"
              :disabled="grantRoleIsDefault"
              :loading="grantSubmitting"
              @click="onSubmitGrant"
            >
              保存授权
            </n-button>
          </n-space>
        </template>
      </n-drawer-content>
    </n-drawer>
  </n-space>
</template>

<script setup lang="ts">
import { NButton, NTag, useDialog, useMessage } from 'naive-ui'
import { h, onMounted, reactive, ref } from 'vue'

import { deptTree } from '@/api/dept'
import {
  changeRoleStatus,
  createRole,
  deleteRole,
  grantableMenus,
  grantedMenus,
  grantMenus,
  pageRoles,
  updateRole,
} from '@/api/role'
import { usePermissionStore } from '@/stores/permission'
import type { Id } from '@/types/api'
import type { DataTableColumns, FormInst, FormRules, SelectOption, TreeSelectOption } from 'naive-ui'
import type { DeptTreeNode, MenuTreeNode, RoleCreateRequest, RoleView } from '@/types/system'

const message = useMessage()
const dialog = useDialog()
const permission = usePermissionStore()

const loading = ref(false)
const submitting = ref(false)
const rows = ref<RoleView[]>([])
const deptOptions = ref<TreeSelectOption[]>([])

const query = reactive<{ roleName: string; status: number | null; pageNo: number; pageSize: number }>({
  roleName: '',
  status: null,
  pageNo: 1,
  pageSize: 10,
})

const pagination = reactive({
  page: 1,
  pageSize: 10,
  itemCount: 0,
  showSizePicker: false,
})

const statusOptions: SelectOption[] = [
  { label: '启用', value: 1 },
  { label: '禁用', value: 0 },
]

const dataScopeOptions: SelectOption[] = [
  { label: '仅本人', value: 1 },
  { label: '本部门', value: 2 },
  { label: '本部门及以下', value: 3 },
  { label: '自定义部门', value: 4 },
  { label: '全部', value: 5 },
]

function toDeptOptions(nodes: DeptTreeNode[]): TreeSelectOption[] {
  return nodes.map((node) => ({
    key: node.id,
    label: node.deptName,
    children: node.children?.length ? toDeptOptions(node.children) : undefined,
  }))
}

async function load(page = query.pageNo): Promise<void> {
  loading.value = true
  try {
    query.pageNo = page
    const result = await pageRoles({
      roleName: query.roleName || undefined,
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
  query.roleName = ''
  query.status = null
  void load(1)
}

const columns: DataTableColumns<RoleView> = [
  { title: '角色标识', key: 'roleKey', width: 160 },
  {
    title: '角色名称',
    key: 'roleName',
    width: 180,
    render: (row) =>
      h('span', {}, [
        row.roleName,
        row.isDefault === 1
          ? h(NTag, { size: 'tiny', type: 'info', bordered: false, style: 'margin-left:6px' }, { default: () => '默认' })
          : null,
      ]),
  },
  {
    title: '数据权限',
    key: 'dataScope',
    width: 140,
    // SelectOption 的 label 允许是渲染函数,这里要的是文案,用 String 收口避免类型外溢
    render: (row) => String(dataScopeOptions.find((item) => item.value === row.dataScope)?.label ?? '-'),
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
    width: 300,
    render: (row) =>
      h('div', { style: 'display:flex;gap:8px;flex-wrap:wrap' }, [
        permission.hasPerm('system:role:grant')
          ? h(NButton, { size: 'tiny', onClick: () => openGrant(row) }, { default: () => '菜单授权' })
          : null,
        permission.hasPerm('system:role:update')
          ? h(NButton, { size: 'tiny', onClick: () => openEdit(row) }, { default: () => '编辑' })
          : null,
        permission.hasPerm('system:role:status')
          ? h(
              NButton,
              { size: 'tiny', onClick: () => onToggleStatus(row) },
              { default: () => (row.status === 1 ? '禁用' : '启用') },
            )
          : null,
        permission.hasPerm('system:role:delete') && row.isDefault !== 1
          ? h(
              NButton,
              { size: 'tiny', type: 'error', ghost: true, onClick: () => onDelete(row) },
              { default: () => '删除' },
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
const form = reactive<{ roleKey: string; roleName: string; dataScope: number; deptIds: Id[]; status: number }>({
  roleKey: '',
  roleName: '',
  dataScope: 1,
  deptIds: [],
  status: 1,
})

const formRules: FormRules = {
  roleKey: [
    { required: true, message: '请输入角色标识', trigger: ['input', 'blur'] },
    {
      pattern: /^[a-zA-Z][a-zA-Z0-9_]{1,63}$/,
      message: '2-64 位字母/数字/下划线,字母开头',
      trigger: ['input', 'blur'],
    },
  ],
  roleName: [{ required: true, message: '请输入角色名称', trigger: ['input', 'blur'] }],
  dataScope: [{ required: true, message: '请选择数据权限', trigger: ['change'] }],
}

function openCreate(): void {
  editing.value = false
  editingId.value = null
  Object.assign(form, { roleKey: '', roleName: '', dataScope: 1, deptIds: [], status: 1 })
  formVisible.value = true
}

function openEdit(row: RoleView): void {
  editing.value = true
  editingId.value = row.id
  Object.assign(form, {
    roleKey: row.roleKey,
    roleName: row.roleName,
    dataScope: row.dataScope,
    deptIds: [],
    status: row.status,
  })
  formVisible.value = true
  message.info('自定义部门的可见范围需重新选择;不改动时保持为空并保存其它字段')
}

/** 切换档位时清空已选部门并提示(前端文档 6.2) */
function onDataScopeChange(value: number): void {
  if (value !== 4 && form.deptIds.length > 0) {
    form.deptIds = []
    message.info('已切换数据权限档位,之前选择的部门已清空')
  }
}

async function onSubmit(): Promise<void> {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  if (form.dataScope === 4 && form.deptIds.length === 0) {
    message.warning('数据权限为"自定义部门"时必须选择部门')
    return
  }
  submitting.value = true
  try {
    const payload: RoleCreateRequest = {
      roleKey: form.roleKey,
      roleName: form.roleName,
      dataScope: form.dataScope,
      deptIds: form.dataScope === 4 ? form.deptIds : undefined,
      status: form.status,
    }
    if (editing.value && editingId.value) {
      await updateRole(editingId.value, payload)
    } else {
      await createRole(payload)
    }
    message.success('已保存')
    formVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

// ——— 菜单授权 ———

const grantVisible = ref(false)
const grantLoading = ref(false)
const grantSubmitting = ref(false)
const grantRoleId = ref<Id | null>(null)
const grantRoleName = ref('')
const grantRoleIsDefault = ref(false)
/**
 * 授权树节点。
 *
 * 用自引用类型而不是 `children?: unknown[]`:后者与 Naive UI 的 TreeOption 不兼容
 * (TreeOption 要求 children 是 TreeOption[]),会在 n-tree 的 :data 绑定处报类型错误。
 */
interface GrantNode {
  key: Id
  label: string
  children?: GrantNode[]
}

const grantTree = ref<GrantNode[]>([])
const checkedKeys = ref<Id[]>([])
const indeterminateKeys = ref<Id[]>([])

function toGrantTree(nodes: MenuTreeNode[]): GrantNode[] {
  return nodes.map((node) => ({
    key: node.id,
    label: node.permCode ? `${node.menuName}(${node.permCode})` : node.menuName,
    children: node.children?.length ? toGrantTree(node.children) : undefined,
  }))
}

async function openGrant(row: RoleView): Promise<void> {
  grantVisible.value = true
  grantRoleId.value = row.id
  grantRoleName.value = row.roleName
  grantRoleIsDefault.value = row.isDefault === 1
  grantLoading.value = true
  checkedKeys.value = []
  indeterminateKeys.value = []
  try {
    // 候选树必须来自后端授权接口,不能用登录响应的 menus(前端文档 6.1 第 1 条)
    const [tree, granted] = await Promise.all([grantableMenus(row.id), grantedMenus(row.id)])
    grantTree.value = toGrantTree(tree)
    checkedKeys.value = granted
  } finally {
    grantLoading.value = false
  }
}

/**
 * 提交授权。
 *
 * 父节点不必由前端"补算":n-tree 的半选父节点会出现在 `indeterminate-keys` 里,
 * 合并 checked 与 indeterminate 就得到了含全部祖先的集合(前端文档 6.1 第 2 条的二选一方案)。
 */
async function onSubmitGrant(): Promise<void> {
  if (!grantRoleId.value) {
    return
  }
  const menuIds = Array.from(new Set([...checkedKeys.value, ...indeterminateKeys.value]))
  grantSubmitting.value = true
  try {
    await grantMenus(grantRoleId.value, menuIds)
    message.success('授权已保存,新权限在用户下一次请求生效')
    grantVisible.value = false
  } finally {
    grantSubmitting.value = false
  }
}

// ——— 行内操作 ———

async function onToggleStatus(row: RoleView): Promise<void> {
  const next = row.status === 1 ? 0 : 1
  if (next === 0) {
    dialog.warning({
      title: '禁用角色',
      content: '禁用后该角色的权限立即不再参与计算,持有该角色的用户会失去对应权限。',
      positiveText: '确认禁用',
      negativeText: '取消',
      onPositiveClick: async () => {
        await changeRoleStatus(row.id, 0)
        message.success('已禁用')
        await load()
      },
    })
    return
  }
  await changeRoleStatus(row.id, 1)
  message.success('已启用')
  await load()
}

function onDelete(row: RoleView): void {
  dialog.error({
    title: '删除角色',
    content: `确认删除角色「${row.roleName}」?仍有用户持有时后端会拒绝。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      await deleteRole(row.id)
      message.success('已删除')
      await load()
    },
  })
}

onMounted(async () => {
  deptOptions.value = toDeptOptions(await deptTree())
  await load(1)
})
</script>

<style scoped>
.mb-12 {
  margin-bottom: 12px;
}
</style>
