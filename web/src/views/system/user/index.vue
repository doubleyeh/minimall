<template>
  <div class="user-page">
    <!-- 左:部门树,选中即筛选右侧用户 -->
    <n-card class="user-page__side" size="small">
      <template #header>
        <div class="user-page__side-head">
          <span>部门</span>
          <n-space :size="4">
            <n-button quaternary size="tiny" title="刷新" @click="loadDepts">
              <template #icon><n-icon :component="RefreshOutline" /></template>
            </n-button>
            <n-button
              v-perm="'system:dept:create'"
              quaternary
              size="tiny"
              title="新增顶级部门"
              @click="openCreateDept(null)"
            >
              <template #icon><n-icon :component="AddOutline" /></template>
            </n-button>
          </n-space>
        </div>
      </template>

      <n-spin :show="deptLoading">
        <n-tree
          block-line
          selectable
          expand-on-click
          :data="treeData"
          :selected-keys="[selectedKey]"
          :expanded-keys="expandedKeys"
          :render-suffix="renderDeptActions"
          @update:selected-keys="onSelectDept"
          @update:expanded-keys="onExpand"
        />
      </n-spin>
    </n-card>

    <!-- 右:该部门下的用户 -->
    <n-card class="user-page__main" size="small">
      <template #header>
        <div class="user-page__main-head">
          <n-space align="center" :size="8">
            <span>用户列表</span>
            <n-tag v-if="selectedDept" size="small" closable :bordered="false" @close="clearDept">
              {{ selectedDept.deptName }}
            </n-tag>
            <n-text v-else depth="3">全部部门</n-text>
          </n-space>
          <n-button v-perm="'system:user:create'" type="primary" size="small" @click="openCreateUser">
            新增用户
          </n-button>
        </div>
      </template>

      <n-form inline :model="query" label-placement="left">
        <n-form-item label="用户名">
          <n-input
            v-model:value="query.username"
            clearable
            placeholder="模糊匹配"
            style="width: 160px"
            @keyup.enter="load(1)"
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
            <n-button type="primary" @click="load(1)">查询</n-button>
            <n-button @click="onResetQuery">重置</n-button>
          </n-space>
        </n-form-item>
      </n-form>

      <n-data-table
        max-height="var(--mm-table-max-h)"
        :columns="columns"
        :data="rows"
        :loading="loading"
        :pagination="pagination"
        :row-key="(row: UserView) => row.id"
        remote
        @update:page="load"
      />
    </n-card>

    <!-- 部门表单 -->
    <n-modal
      v-model:show="deptFormVisible"
      preset="card"
      :title="editingDept ? '编辑部门' : '新增部门'"
      style="width: 480px"
    >
      <n-form ref="deptFormRef" :model="deptForm" :rules="deptRules" label-placement="top">
        <n-form-item label="上级部门" path="parentId">
          <n-tree-select
            v-model:value="deptForm.parentId"
            :options="parentOptions"
            placeholder="顶级部门请选择「根部门」"
          />
        </n-form-item>
        <n-form-item label="部门名称" path="deptName">
          <n-input v-model:value="deptForm.deptName" />
        </n-form-item>
        <n-form-item label="排序">
          <n-input-number v-model:value="deptForm.sortOrder" :min="0" />
        </n-form-item>
        <n-form-item label="状态">
          <n-radio-group v-model:value="deptForm.status">
            <n-radio :value="1">启用</n-radio>
            <n-radio :value="0">停用</n-radio>
          </n-radio-group>
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button @click="deptFormVisible = false">取消</n-button>
          <n-button type="primary" :loading="submitting" @click="onSubmitDept">保存</n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- 用户表单 -->
    <n-modal
      v-model:show="formVisible"
      preset="card"
      :title="editing ? '编辑用户' : '新增用户'"
      style="width: 560px"
    >
      <n-form ref="formRef" :model="form" :rules="formRules" label-placement="top">
        <n-form-item label="用户名" path="username">
          <n-input v-model:value="form.username" :disabled="editing" placeholder="3-64 位字母/数字/下划线,字母开头" />
        </n-form-item>
        <n-form-item v-if="!editing" label="初始密码" path="password">
          <n-input v-model:value="form.password" placeholder="留空则由系统随机生成并返回一次" />
        </n-form-item>
        <n-form-item label="昵称"><n-input v-model:value="form.nickname" /></n-form-item>
        <n-form-item label="手机号"><n-input v-model:value="form.phone" /></n-form-item>
        <n-form-item label="部门">
          <n-tree-select v-model:value="form.deptId" :options="deptOptions" clearable placeholder="可留空" />
        </n-form-item>
        <n-form-item label="角色">
          <n-select v-model:value="form.roleIds" :options="roleOptions" multiple clearable placeholder="可多选" />
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
          <n-button type="primary" :loading="submitting" @click="onSubmitUser">保存</n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- 一次性明文密码,只在本次响应里出现 -->
    <n-modal v-model:show="secretVisible" preset="card" title="初始密码" style="width: 460px">
      <n-alert type="warning" :show-icon="true">
        这个密码只显示这一次,关闭后无法再查看。请立即复制并交给使用者,对方首次登录会被要求改密。
      </n-alert>
      <n-space align="center" :size="8" style="margin-top: 12px">
        <n-input :value="secret" readonly />
        <n-button @click="copySecret">复制</n-button>
      </n-space>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { AddOutline, CreateOutline, RefreshOutline, TrashOutline } from '@vicons/ionicons5'
import { NButton, NIcon, NTag, useDialog, useMessage } from 'naive-ui'
import { computed, h, onMounted, reactive, ref } from 'vue'

import { createDept, deleteDept, deptTree, updateDept } from '@/api/dept'
import { pageRoles } from '@/api/role'
import {
  changeUserStatus,
  createUser,
  deleteUser,
  pageUsers,
  resetUserPassword,
  unlockUser,
  updateUser,
} from '@/api/user'
import { usePermissionStore } from '@/stores/permission'
import type { Id } from '@/types/api'
import type {
  DataTableColumns,
  FormInst,
  FormRules,
  SelectOption,
  TreeOption,
  TreeSelectOption,
} from 'naive-ui'
import type {
  DeptSaveRequest,
  DeptTreeNode,
  RoleView,
  UserSaveRequest,
  UserView,
} from '@/types/system'

/** 部门 + 用户合并为一页:左部门树(选中即筛选)+ 右用户列表。部门增删改挂在树节点后缀上。 */

const message = useMessage()
const dialog = useDialog()
const permission = usePermissionStore()

const ALL_KEY = 'all'
const ROOT_KEY = '0'

// ——— 部门树 ———

const deptLoading = ref(false)
const depts = ref<DeptTreeNode[]>([])
const deptById = ref(new Map<string, DeptTreeNode>())
const selectedKey = ref<string>(ALL_KEY)
const expandedKeys = ref<string[]>([ALL_KEY])
const parentOptions = ref<TreeSelectOption[]>([])

const selectedDept = computed(() =>
  selectedKey.value === ALL_KEY ? null : (deptById.value.get(selectedKey.value) ?? null),
)

const treeData = computed<TreeOption[]>(() => [
  { key: ALL_KEY, label: '全部部门', children: depts.value.map(toTreeOption) },
])

/** 部门树是无限层级的,所以每个节点都能"新增下级" */
function renderDeptActions(info: { option: TreeOption }): ReturnType<typeof h> {
  const key = String(info.option.key)
  const row = deptById.value.get(key)
  const active = selectedKey.value === key
  const actions = []
  if (permission.hasPerm('system:dept:create')) {
    actions.push(
      h(
        NButton,
        {
          size: 'tiny',
          quaternary: true,
          title: row ? '新增下级' : '新增顶级部门',
          onClick: (e: MouseEvent) => {
            e.stopPropagation()
            openCreateDept(row?.id ?? null)
          },
        },
        { icon: () => h(NIcon, { component: AddOutline }) },
      ),
    )
  }
  if (row && permission.hasPerm('system:dept:update')) {
    actions.push(
      h(
        NButton,
        {
          size: 'tiny',
          quaternary: true,
          title: '编辑',
          onClick: (e: MouseEvent) => {
            e.stopPropagation()
            openEditDept(row)
          },
        },
        { icon: () => h(NIcon, { component: CreateOutline }) },
      ),
    )
  }
  if (row && permission.hasPerm('system:dept:delete')) {
    actions.push(
      h(
        NButton,
        {
          size: 'tiny',
          quaternary: true,
          title: '删除',
          onClick: (e: MouseEvent) => {
            e.stopPropagation()
            confirmDeleteDept(row)
          },
        },
        { icon: () => h(NIcon, { component: TrashOutline }) },
      ),
    )
  }
  return h('span', { class: ['dept-actions', { 'dept-actions--active': active }] }, actions)
}

function toTreeOption(node: DeptTreeNode): TreeOption {
  return {
    key: node.id,
    label: node.deptName,
    children: node.children?.length ? node.children.map(toTreeOption) : undefined,
  }
}

function toTreeSelectOptions(nodes: DeptTreeNode[]): TreeSelectOption[] {
  return nodes.map((node) => ({
    key: node.id,
    label: node.deptName,
    children: node.children?.length ? toTreeSelectOptions(node.children) : undefined,
  }))
}

async function loadDepts(): Promise<void> {
  deptLoading.value = true
  try {
    const tree = await deptTree()
    depts.value = tree
    const map = new Map<string, DeptTreeNode>()
    const walk = (nodes: DeptTreeNode[]): void => {
      for (const node of nodes) {
        map.set(String(node.id), node)
        if (node.children?.length) {
          walk(node.children)
        }
      }
    }
    walk(tree)
    deptById.value = map
    deptOptions.value = toTreeSelectOptions(tree)
    parentOptions.value = [{ key: ROOT_KEY, label: '根部门' }, ...toTreeSelectOptions(tree)]
    expandedKeys.value = [ALL_KEY, ...Array.from(map.keys())]
    if (selectedKey.value !== ALL_KEY && !map.has(selectedKey.value)) {
      selectedKey.value = ALL_KEY
      query.deptId = null
    }
  } finally {
    deptLoading.value = false
  }
}

function onSelectDept(keys: Array<string | number>): void {
  const key = keys.length ? String(keys[0]) : ALL_KEY
  selectedKey.value = key
  query.deptId = key === ALL_KEY ? null : key
  void load(1)
}

function clearDept(): void {
  onSelectDept([ALL_KEY])
}

function onExpand(keys: Array<string | number>): void {
  expandedKeys.value = keys.map(String)
}

// ——— 部门增删改 ———

const deptFormVisible = ref(false)
const editingDept = ref<Id | null>(null)
const deptFormRef = ref<FormInst | null>(null)
const deptForm = reactive<DeptSaveRequest>({
  parentId: ROOT_KEY,
  deptName: '',
  sortOrder: 1,
  status: 1,
})

const deptRules: FormRules = {
  deptName: { required: true, message: '请输入部门名称', trigger: ['blur', 'input'] },
}

function openCreateDept(parentId: Id | null): void {
  editingDept.value = null
  Object.assign(deptForm, { parentId: parentId ?? ROOT_KEY, deptName: '', sortOrder: 1, status: 1 })
  deptFormVisible.value = true
}

function openEditDept(row: DeptTreeNode): void {
  editingDept.value = row.id
  Object.assign(deptForm, {
    parentId: row.parentId === '0' ? ROOT_KEY : row.parentId,
    deptName: row.deptName,
    sortOrder: row.sortOrder,
    status: row.status,
  })
  deptFormVisible.value = true
}

async function onSubmitDept(): Promise<void> {
  try {
    await deptFormRef.value?.validate()
  } catch {
    return
  }
  submitting.value = true
  try {
    const payload: DeptSaveRequest = {
      parentId: String(deptForm.parentId) === ROOT_KEY ? ROOT_KEY : String(deptForm.parentId),
      deptName: deptForm.deptName,
      sortOrder: deptForm.sortOrder,
      status: deptForm.status,
    }
    if (editingDept.value == null) {
      await createDept(payload)
    } else {
      await updateDept(editingDept.value, payload)
    }
    message.success('已保存')
    deptFormVisible.value = false
    await loadDepts()
  } finally {
    submitting.value = false
  }
}

function confirmDeleteDept(row: DeptTreeNode): void {
  dialog.warning({
    title: '确认删除',
    content: `确定删除部门「${row.deptName}」吗?有下级部门或部门下还有用户时后端会拒绝。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      await deleteDept(row.id)
      message.success('已删除')
      await loadDepts()
      await load()
    },
  })
}

// ——— 用户列表 ———

const loading = ref(false)
const submitting = ref(false)
const rows = ref<UserView[]>([])
const deptOptions = ref<TreeSelectOption[]>([])
const roleOptions = ref<SelectOption[]>([])

const query = reactive<{
  username: string
  deptId: Id | null
  status: number | null
  pageNo: number
  pageSize: number
}>({ username: '', deptId: null, status: null, pageNo: 1, pageSize: 10 })

const statusOptions: SelectOption[] = [
  { label: '启用', value: 1 },
  { label: '禁用', value: 0 },
]

const pagination = reactive({
  page: 1,
  pageSize: 10,
  itemCount: 0,
  showSizePicker: false,
  prefix: (info: { itemCount?: number }) => `共 ${info.itemCount ?? 0} 条`,
})

async function load(page = query.pageNo): Promise<void> {
  loading.value = true
  try {
    query.pageNo = page
    const result = await pageUsers({
      username: query.username || undefined,
      deptId: query.deptId,
      status: query.status,
      pageNo: query.pageNo,
      pageSize: query.pageSize,
    })
    rows.value = result.list
    pagination.page = query.pageNo
    pagination.itemCount = result.total
  } finally {
    loading.value = false
  }
}

function onResetQuery(): void {
  query.username = ''
  query.status = null
  // 重置回到全部部门,树与列表一起回
  selectedKey.value = ALL_KEY
  query.deptId = null
  void load(1)
}

const columns: DataTableColumns<UserView> = [
  { title: '用户名', key: 'username', width: 160 },
  { title: '昵称', key: 'nickname', width: 140 },
  { title: '部门', key: 'deptName', width: 140, render: (row) => row.deptName ?? '-' },
  { title: '手机号', key: 'phone', width: 140, render: (row) => row.phone ?? '-' },
  {
    title: '状态',
    key: 'status',
    width: 120,
    render: (row) =>
      h(
        NTag,
        {
          size: 'small',
          type: row.lockTime ? 'error' : row.status === 1 ? 'success' : 'default',
          bordered: false,
        },
        { default: () => (row.lockTime ? '已锁定' : row.status === 1 ? '启用' : '禁用') },
      ),
  },
  {
    title: '操作',
    key: 'actions',
    width: 320,
    render: (row) =>
      h('div', { style: 'display:flex;gap:8px;flex-wrap:wrap' }, [
        permission.hasPerm('system:user:update')
          ? h(NButton, { size: 'tiny', onClick: () => openEditUser(row) }, { default: () => '编辑' })
          : null,
        permission.hasPerm('system:user:status')
          ? h(
              NButton,
              { size: 'tiny', onClick: () => onToggleStatus(row) },
              { default: () => (row.status === 1 ? '禁用' : '启用') },
            )
          : null,
        row.lockTime && permission.hasPerm('system:user:unlock')
          ? h(NButton, { size: 'tiny', onClick: () => onUnlock(row) }, { default: () => '解锁' })
          : null,
        permission.hasPerm('system:user:reset-password')
          ? h(NButton, { size: 'tiny', onClick: () => onResetPassword(row) }, { default: () => '重置密码' })
          : null,
        permission.hasPerm('system:user:delete')
          ? h(
              NButton,
              { size: 'tiny', type: 'error', ghost: true, onClick: () => onDelete(row) },
              { default: () => '删除' },
            )
          : null,
      ]),
  },
]

// ——— 用户增改 ———

const formVisible = ref(false)
const editing = ref(false)
const editingId = ref<Id | null>(null)
const formRef = ref<FormInst | null>(null)
const form = reactive<UserSaveRequest & { roleIds: Id[]; status: number }>({
  username: '',
  password: '',
  nickname: '',
  phone: '',
  deptId: null,
  roleIds: [],
  status: 1,
})

const formRules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: ['input', 'blur'] },
    {
      pattern: /^[a-zA-Z][a-zA-Z0-9_]{2,63}$/,
      message: '3-64 位字母/数字/下划线,且以字母开头',
      trigger: ['input', 'blur'],
    },
  ],
}

function openCreateUser(): void {
  editing.value = false
  editingId.value = null
  // 新增用户默认落在左侧选中的部门下
  Object.assign(form, {
    username: '',
    password: '',
    nickname: '',
    phone: '',
    deptId: selectedDept.value?.id ?? null,
    roleIds: [],
    status: 1,
  })
  formVisible.value = true
}

function openEditUser(row: UserView): void {
  editing.value = true
  editingId.value = row.id
  Object.assign(form, {
    username: row.username,
    password: '',
    nickname: row.nickname,
    phone: row.phone,
    deptId: row.deptId,
    roleIds: [],
    status: row.status,
  })
  formVisible.value = true
  // 列表不返回 roleIds,留空提交会清空角色,所以提示重新选择
  message.info('如需调整角色,请重新选择;不改动角色时请保持为空并保存其它字段')
}

async function onSubmitUser(): Promise<void> {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  submitting.value = true
  try {
    const payload: UserSaveRequest = {
      username: form.username,
      nickname: form.nickname,
      phone: form.phone,
      deptId: form.deptId,
      status: form.status,
      roleIds: form.roleIds.length ? form.roleIds : undefined,
    }
    if (editing.value && editingId.value) {
      await updateUser(editingId.value, payload)
      message.success('已保存')
    } else {
      const created = await createUser({ ...payload, password: form.password || undefined })
      if (created.initialPassword) {
        showSecret(created.initialPassword)
      } else {
        message.success('已创建')
      }
    }
    formVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

const secretVisible = ref(false)
const secret = ref('')

function showSecret(value: string): void {
  secret.value = value
  secretVisible.value = true
}

async function copySecret(): Promise<void> {
  await navigator.clipboard.writeText(secret.value)
  message.success('已复制')
}

async function onToggleStatus(row: UserView): Promise<void> {
  const next = row.status === 1 ? 0 : 1
  if (next === 0) {
    dialog.warning({
      title: '禁用用户',
      content: '禁用后该用户的在线会话会在下一次请求失效,确认继续?',
      positiveText: '确认禁用',
      negativeText: '取消',
      onPositiveClick: async () => {
        await changeUserStatus(row.id, 0)
        message.success('已禁用')
        await load()
      },
    })
    return
  }
  await changeUserStatus(row.id, 1)
  message.success('已启用')
  await load()
}

async function onUnlock(row: UserView): Promise<void> {
  await unlockUser(row.id)
  message.success('已解除锁定')
  await load()
}

async function onResetPassword(row: UserView): Promise<void> {
  const result = await resetUserPassword(row.id)
  if (result.initialPassword) {
    showSecret(result.initialPassword)
  }
}

function onDelete(row: UserView): void {
  dialog.error({
    title: '删除用户',
    content: `确认删除用户「${row.username}」?该操作不可撤销。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      await deleteUser(row.id)
      message.success('已删除')
      await load()
    },
  })
}

onMounted(async () => {
  const roles = await pageRoles({ pageNo: 1, pageSize: 100 })
  roleOptions.value = roles.list.map((role: RoleView) => ({ label: role.roleName, value: role.id }))
  await loadDepts()
  await load(1)
})
</script>

<style scoped>
.user-page {
  display: grid;
  grid-template-columns: 272px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.user-page__side :deep(.n-card__content) {
  max-height: calc(100vh - 220px);
  overflow: auto;
}

.user-page__side-head,
.user-page__main-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

/* 节点后缀的操作按钮:默认隐藏,悬停或选中时显示 */
.dept-actions {
  display: inline-flex;
  gap: 2px;
  opacity: 0;
  transition: opacity 0.15s ease;
}

.dept-actions--active {
  opacity: 1;
}

.user-page :deep(.n-tree-node:hover) .dept-actions {
  opacity: 1;
}

@media (max-width: 1100px) {
  .user-page {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
