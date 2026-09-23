<template>
  <n-space vertical :size="16">
    <n-alert type="info">
      字典是平台级配置,全局共用、不分租户 —— 维护权限只挂在平台菜单下,租户侧拿不到。
      删除字典类型时会连同它下面的全部字典项一起删除,不可恢复。
    </n-alert>

    <n-grid :cols="2" :x-gap="16">
      <!-- 左:字典类型 -->
      <n-grid-item>
        <n-card>
          <template #header>
            <n-space justify="space-between" align="center">
              <span>字典类型</span>
              <n-button v-perm="'system:dict:create'" type="primary" @click="openCreateType">新增类型</n-button>
            </n-space>
          </template>

          <n-form inline :model="query" label-placement="left" class="dict-query">
            <n-form-item label="编码">
              <n-input v-model:value="query.dictType" clearable placeholder="模糊匹配" style="width: 132px" />
            </n-form-item>
            <n-form-item label="名称">
              <n-input v-model:value="query.dictName" clearable placeholder="模糊匹配" style="width: 132px" />
            </n-form-item>
            <n-form-item>
              <n-space>
                <n-button type="primary" @click="loadTypes(1)">查询</n-button>
                <n-button @click="onResetQuery">重置</n-button>
              </n-space>
            </n-form-item>
          </n-form>

          <n-data-table
        max-height="var(--mm-table-max-h)"
            :columns="typeColumns"
            :data="typeRows"
            :loading="typeLoading"
            :pagination="pagination"
            :row-key="(row: DictTypeView) => row.id"
            :row-props="typeRowProps"
            :row-class-name="typeRowClass"
            remote
            size="small"
            @update:page="loadTypes"
          />
        </n-card>
      </n-grid-item>

      <!-- 右:选中类型下的字典项 -->
      <n-grid-item>
        <n-card>
          <template #header>
            <n-space justify="space-between" align="center">
              <span>字典项{{ selectedType ? ` · ${selectedType.dictName}` : '' }}</span>
              <n-button
                v-perm="'system:dict:create'"
                type="primary"
                :disabled="!selectedType"
                @click="openCreateData"
              >
                新增字典项
              </n-button>
            </n-space>
          </template>

          <n-empty
            v-if="!selectedType"
            class="dict-empty"
            description="先在左侧选择一个字典类型"
          />
          <template v-else>
            <n-text depth="3" class="dict-tip">类型编码:{{ selectedType.dictType }}</n-text>
            <n-data-table
        max-height="var(--mm-table-max-h)"
              :columns="dataColumns"
              :data="dataRows"
              :loading="dataLoading"
              :row-key="(row: DictDataView) => row.id"
              size="small"
            />
          </template>
        </n-card>
      </n-grid-item>
    </n-grid>

    <!-- 类型表单 -->
    <n-modal
      v-model:show="typeFormVisible"
      preset="card"
      :title="typeEditing ? '编辑字典类型' : '新增字典类型'"
      style="width: 480px"
    >
      <n-form ref="typeFormRef" :model="typeForm" :rules="typeRules" label-placement="top">
        <n-form-item label="类型编码" path="dictType">
          <n-input
            v-model:value="typeForm.dictType"
            :disabled="typeEditing"
            placeholder="如 order_pay_timeout_minutes"
          />
        </n-form-item>
        <n-form-item label="类型名称" path="dictName">
          <n-input v-model:value="typeForm.dictName" placeholder="如 订单支付超时分钟数" />
        </n-form-item>
        <n-text v-if="typeEditing" depth="3">
          编码不可修改:已有字典项是按编码挂靠的,改了会让它们全部悬空。要换编码请新建类型再迁移数据。
        </n-text>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button @click="typeFormVisible = false">取消</n-button>
          <n-button type="primary" :loading="typeSubmitting" @click="onSubmitType">保存</n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- 字典项表单 -->
    <n-modal
      v-model:show="dataFormVisible"
      preset="card"
      :title="dataEditing ? '编辑字典项' : '新增字典项'"
      style="width: 480px"
    >
      <n-form ref="dataFormRef" :model="dataForm" :rules="dataRules" label-placement="top">
        <n-form-item label="所属类型">
          <n-input :value="selectedType?.dictName ?? ''" disabled />
        </n-form-item>
        <n-form-item label="标签" path="dictLabel">
          <n-input v-model:value="dataForm.dictLabel" placeholder="展示给用户的文案,如 30 分钟" />
        </n-form-item>
        <n-form-item label="值" path="dictValue">
          <n-input v-model:value="dataForm.dictValue" placeholder="程序里使用的取值,如 30" />
        </n-form-item>
        <n-form-item label="排序" path="sortOrder">
          <n-input-number v-model:value="dataForm.sortOrder" :min="0" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button @click="dataFormVisible = false">取消</n-button>
          <n-button type="primary" :loading="dataSubmitting" @click="onSubmitData">保存</n-button>
        </n-space>
      </template>
    </n-modal>
  </n-space>
</template>

<script setup lang="ts">
import { NButton, useDialog, useMessage } from 'naive-ui'
import { h, onMounted, reactive, ref } from 'vue'

import {
  createDictData,
  createDictType,
  deleteDictData,
  deleteDictType,
  listDictData,
  pageDictTypes,
  updateDictData,
  updateDictType,
} from '@/api/dict'
import { usePermissionStore } from '@/stores/permission'
import type { Id } from '@/types/api'
import type { DataTableColumns, FormInst, FormRules } from 'naive-ui'
import type { DictDataSaveRequest, DictDataView, DictTypeSaveRequest, DictTypeView } from '@/types/system'

const message = useMessage()
const dialog = useDialog()
const permission = usePermissionStore()

// ——— 字典类型 ———

const typeLoading = ref(false)
const typeRows = ref<DictTypeView[]>([])
const query = reactive<{ dictType: string; dictName: string; pageSize: number }>({
  dictType: '',
  dictName: '',
  pageSize: 10,
})
const pagination = reactive({ page: 1, pageSize: 10, itemCount: 0 })

async function loadTypes(page = pagination.page): Promise<void> {
  typeLoading.value = true
  try {
    const result = await pageDictTypes({
      dictType: query.dictType || undefined,
      dictName: query.dictName || undefined,
      pageNo: page,
      pageSize: query.pageSize,
    })
    typeRows.value = result.list
    pagination.page = page
    pagination.itemCount = result.total
    // 选中项可能因为翻页/查询已经不在当前列表里:清掉选中,避免右侧还显示着一个"列表里看不到"的类型
    if (selectedType.value && !result.list.some((item) => item.id === selectedType.value?.id)) {
      clearSelection()
    }
  } finally {
    typeLoading.value = false
  }
}

function onResetQuery(): void {
  query.dictType = ''
  query.dictName = ''
  void loadTypes(1)
}

const selectedType = ref<DictTypeView | null>(null)
const dataLoading = ref(false)
const dataRows = ref<DictDataView[]>([])

function clearSelection(): void {
  selectedType.value = null
  dataRows.value = []
}

function typeRowProps(row: DictTypeView): Record<string, unknown> {
  return { style: 'cursor: pointer', onClick: () => void selectType(row) }
}

/** 选中行加底色(下划线到底层的 tr,scoped 样式必须走 :deep) */
function typeRowClass(row: DictTypeView): string {
  return selectedType.value?.id === row.id ? 'dict-row-active' : ''
}

async function selectType(row: DictTypeView): Promise<void> {
  selectedType.value = row
  await loadData()
}

async function loadData(): Promise<void> {
  if (!selectedType.value) {
    return
  }
  dataLoading.value = true
  try {
    dataRows.value = await listDictData(selectedType.value.dictType)
  } finally {
    dataLoading.value = false
  }
}

const typeColumns: DataTableColumns<DictTypeView> = [
  { title: '编码', key: 'dictType', ellipsis: { tooltip: true } },
  { title: '名称', key: 'dictName', width: 120, ellipsis: { tooltip: true } },
  { title: '字典项', key: 'dataCount', width: 84, render: (row) => `${row.dataCount} 项` },
  {
    title: '操作',
    key: 'actions',
    width: 128,
    render: (row) =>
      h('div', { style: 'display:flex;gap:8px' }, [
        permission.hasPerm('system:dict:update')
          ? h(NButton, { size: 'tiny', onClick: () => openEditType(row) }, { default: () => '编辑' })
          : null,
        permission.hasPerm('system:dict:delete')
          ? h(
              NButton,
              { size: 'tiny', type: 'error', ghost: true, onClick: () => onDeleteType(row) },
              { default: () => '删除' },
            )
          : null,
      ]),
  },
]

const dataColumns: DataTableColumns<DictDataView> = [
  { title: '标签', key: 'dictLabel', ellipsis: { tooltip: true } },
  { title: '值', key: 'dictValue', ellipsis: { tooltip: true } },
  { title: '排序', key: 'sortOrder', width: 72 },
  {
    title: '操作',
    key: 'actions',
    width: 128,
    render: (row) =>
      h('div', { style: 'display:flex;gap:8px' }, [
        permission.hasPerm('system:dict:update')
          ? h(NButton, { size: 'tiny', onClick: () => openEditData(row) }, { default: () => '编辑' })
          : null,
        permission.hasPerm('system:dict:delete')
          ? h(
              NButton,
              { size: 'tiny', type: 'error', ghost: true, onClick: () => onDeleteData(row) },
              { default: () => '删除' },
            )
          : null,
      ]),
  },
]

// ——— 类型表单 ———

const typeFormVisible = ref(false)
const typeSubmitting = ref(false)
const typeEditing = ref(false)
const typeEditingId = ref<Id | null>(null)
const typeFormRef = ref<FormInst | null>(null)
const typeForm = reactive<{ dictType: string; dictName: string }>({ dictType: '', dictName: '' })

/** 只做必填校验:编码格式由后端决定,前端自作主张加正则会把合法编码拦在门外 */
const typeRules: FormRules = {
  dictType: [{ required: true, message: '请输入类型编码', trigger: ['input', 'blur'] }],
  dictName: [{ required: true, message: '请输入类型名称', trigger: ['input', 'blur'] }],
}

function openCreateType(): void {
  typeEditing.value = false
  typeEditingId.value = null
  Object.assign(typeForm, { dictType: '', dictName: '' })
  typeFormVisible.value = true
}

function openEditType(row: DictTypeView): void {
  typeEditing.value = true
  typeEditingId.value = row.id
  Object.assign(typeForm, { dictType: row.dictType, dictName: row.dictName })
  typeFormVisible.value = true
}

async function onSubmitType(): Promise<void> {
  try {
    await typeFormRef.value?.validate()
  } catch {
    return
  }
  const payload: DictTypeSaveRequest = {
    dictType: typeForm.dictType.trim(),
    dictName: typeForm.dictName.trim(),
  }
  const editingId = typeEditing.value ? typeEditingId.value : null
  typeSubmitting.value = true
  try {
    if (editingId) {
      await updateDictType(editingId, payload)
    } else {
      await createDictType(payload)
    }
    message.success('已保存')
    typeFormVisible.value = false
    await loadTypes()
    // 刚编辑的就是当前选中项时,把它换成列表里的新对象 —— 否则右侧标题还显示旧名称
    if (editingId && selectedType.value?.id === editingId) {
      selectedType.value = typeRows.value.find((item) => item.id === editingId) ?? null
    }
  } finally {
    typeSubmitting.value = false
  }
}

function onDeleteType(row: DictTypeView): void {
  dialog.error({
    title: '删除字典类型',
    content: `确认删除「${row.dictName}」(${row.dictType})?它下面的 ${row.dataCount} 个字典项会被一起删除,且不可恢复。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      await deleteDictType(row.id)
      message.success('已删除')
      if (selectedType.value?.id === row.id) {
        clearSelection()
      }
      await loadTypes()
    },
  })
}

// ——— 字典项表单 ———

const dataFormVisible = ref(false)
const dataSubmitting = ref(false)
const dataEditing = ref(false)
const dataEditingId = ref<Id | null>(null)
const dataFormRef = ref<FormInst | null>(null)
const dataForm = reactive<{ dictLabel: string; dictValue: string; sortOrder: number }>({
  dictLabel: '',
  dictValue: '',
  sortOrder: 1,
})

const dataRules: FormRules = {
  dictLabel: [{ required: true, message: '请输入标签', trigger: ['input', 'blur'] }],
  dictValue: [{ required: true, message: '请输入值', trigger: ['input', 'blur'] }],
}

function openCreateData(): void {
  dataEditing.value = false
  dataEditingId.value = null
  Object.assign(dataForm, { dictLabel: '', dictValue: '', sortOrder: 1 })
  dataFormVisible.value = true
}

function openEditData(row: DictDataView): void {
  dataEditing.value = true
  dataEditingId.value = row.id
  Object.assign(dataForm, { dictLabel: row.dictLabel, dictValue: row.dictValue, sortOrder: row.sortOrder })
  dataFormVisible.value = true
}

async function onSubmitData(): Promise<void> {
  if (!selectedType.value) {
    return
  }
  try {
    await dataFormRef.value?.validate()
  } catch {
    return
  }
  const payload: DictDataSaveRequest = {
    // 所属类型不可改:把字典项挪到另一个类型等价于"删掉再新建",不提供这个入口
    dictType: selectedType.value.dictType,
    dictLabel: dataForm.dictLabel.trim(),
    dictValue: dataForm.dictValue.trim(),
    sortOrder: dataForm.sortOrder,
  }
  dataSubmitting.value = true
  try {
    if (dataEditing.value && dataEditingId.value) {
      await updateDictData(dataEditingId.value, payload)
    } else {
      await createDictData(payload)
    }
    message.success('已保存')
    dataFormVisible.value = false
    // 类型列表也要刷新:里面的"字典项数"是后端聚合出来的,不刷新会一直显示旧数字
    await Promise.all([loadData(), loadTypes()])
  } finally {
    dataSubmitting.value = false
  }
}

function onDeleteData(row: DictDataView): void {
  dialog.warning({
    title: '删除字典项',
    content: `确认删除「${row.dictLabel}」(${row.dictValue})?`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      await deleteDictData(row.id)
      message.success('已删除')
      await Promise.all([loadData(), loadTypes()])
    },
  })
}

onMounted(() => void loadTypes(1))
</script>

<style scoped>
.dict-query {
  margin-bottom: 12px;
}

.dict-tip {
  display: block;
  margin-bottom: 8px;
}

.dict-empty {
  padding: 48px 0;
}

/* 选中行底色:class 落到底层 tr 上,scoped 样式必须用 :deep 才能命中 */
:deep(.dict-row-active td) {
  background-color: rgba(24, 160, 88, 0.08);
}
</style>
