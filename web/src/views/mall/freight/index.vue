<template>
  <n-space vertical :size="16">
    <n-card>
      <template #header>
        <n-space justify="space-between" align="center">
          <span>运费模板</span>
          <n-space>
            <n-button @click="load">刷新</n-button>
            <n-button v-perm="'mall:freight:create'" type="primary" @click="openCreate">新增模板</n-button>
          </n-space>
        </n-space>
      </template>

      <n-alert type="info" :bordered="false" style="margin-bottom: 12px">
        必须配置一条不限区域(ALL)的兜底规则,否则未列出的省份算不出运费。商品未关联模板时视为包邮。
      </n-alert>

      <n-data-table
        max-height="var(--mm-table-max-h)" :columns="columns" :data="rows" :loading="loading" :row-key="(row: FreightTemplateView) => row.id" />
    </n-card>

    <n-modal
      v-model:show="formVisible"
      preset="card"
      :title="editingId ? '编辑运费模板' : '新增运费模板'"
      style="width: 940px"
    >
      <n-form ref="formRef" :model="form" :rules="formRules" label-placement="top">
        <n-grid :cols="2" :x-gap="16">
          <n-form-item-gi label="模板名称" path="templateName">
            <n-input v-model:value="form.templateName" />
          </n-form-item-gi>
          <n-form-item-gi label="计费方式" path="chargeType">
            <n-select v-model:value="form.chargeType" :options="chargeTypeOptions" />
          </n-form-item-gi>
        </n-grid>

        <n-divider>区域规则</n-divider>
        <n-space vertical :size="8">
          <n-card v-for="(rule, index) in form.rules" :key="index" size="small">
            <n-grid :cols="3" :x-gap="12">
              <n-form-item-gi label="适用区域(ALL 或省份逗号分隔)">
                <n-input v-model:value="rule.region" placeholder="ALL" />
              </n-form-item-gi>
              <n-form-item-gi :label="unitLabel('首件/首重')">
                <n-input-number v-model:value="rule.firstUnit" :min="0" :precision="3" />
              </n-form-item-gi>
              <n-form-item-gi label="首费">
                <n-input-number v-model:value="rule.firstFee" :min="0" :precision="2" />
              </n-form-item-gi>
              <n-form-item-gi :label="unitLabel('续件/续重步长')">
                <n-input-number v-model:value="rule.additionalUnit" :min="0.001" :precision="3" />
              </n-form-item-gi>
              <n-form-item-gi label="续费">
                <n-input-number v-model:value="rule.additionalFee" :min="0" :precision="2" />
              </n-form-item-gi>
              <n-form-item-gi label="满额包邮(可空)">
                <n-input-number v-model:value="rule.freeShippingAmount" :min="0" :precision="2" />
              </n-form-item-gi>
            </n-grid>
            <template #footer>
              <n-space justify="end">
                <n-button size="small" type="error" @click="form.rules.splice(index, 1)">删除该规则</n-button>
              </n-space>
            </template>
          </n-card>
          <n-button size="small" @click="addRule">添加区域规则</n-button>
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
import { NButton, NTag, useDialog, useMessage } from 'naive-ui'
import { h, onMounted, reactive, ref } from 'vue'

import {
  createFreightTemplate,
  deleteFreightTemplate,
  listFreightTemplates,
  updateFreightTemplate,
} from '@/api/mall'
import { usePermissionStore } from '@/stores/permission'
import type { Id } from '@/types/api'
import type { FreightRule, FreightTemplateSaveRequest, FreightTemplateView } from '@/types/mall'
import type { DataTableColumns, FormInst, FormRules, SelectOption } from 'naive-ui'

const message = useMessage()
const dialog = useDialog()
const permission = usePermissionStore()

const loading = ref(false)
const submitting = ref(false)
const rows = ref<FreightTemplateView[]>([])

const chargeTypeOptions: SelectOption[] = [
  { label: '按件数', value: 1 },
  { label: '按重量', value: 2 },
]

const columns: DataTableColumns<FreightTemplateView> = [
  { title: '模板名称', key: 'templateName' },
  {
    title: '计费方式',
    key: 'chargeType',
    width: 120,
    render: (row) =>
      h(
        NTag,
        { size: 'small', bordered: false },
        { default: () => (row.chargeType === 2 ? '按重量' : '按件数') },
      ),
  },
  { title: '规则数', key: 'rules', width: 100, render: (row) => row.rules?.length ?? 0 },
  {
    title: '区域',
    key: 'regions',
    render: (row) => (row.rules ?? []).map((rule) => rule.region).join('、') || '-',
  },
  {
    title: '操作',
    key: 'actions',
    width: 160,
    render: (row) =>
      h('div', { style: 'display:flex;gap:8px' }, [
        permission.hasPerm('mall:freight:update')
          ? h(NButton, { size: 'tiny', onClick: () => openEdit(row) }, { default: () => '编辑' })
          : null,
        permission.hasPerm('mall:freight:delete')
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
    rows.value = await listFreightTemplates()
  } finally {
    loading.value = false
  }
}

const formVisible = ref(false)
const editingId = ref<Id | null>(null)
const formRef = ref<FormInst | null>(null)
const form = reactive<FreightTemplateSaveRequest>({
  templateName: '',
  chargeType: 1,
  rules: [],
})

const formRules: FormRules = {
  templateName: { required: true, message: '请输入模板名称', trigger: ['blur', 'input'] },
}

/** 按计费方式显示单位,避免运营把"首重"填成件数 */
function unitLabel(base: string): string {
  return form.chargeType === 2 ? `${base}(kg)` : `${base}(件)`
}

function addRule(): void {
  form.rules.push({
    region: form.rules.length === 0 ? 'ALL' : '',
    firstUnit: 1,
    firstFee: 0,
    additionalUnit: 1,
    additionalFee: 0,
    freeShippingAmount: null,
  })
}

function openCreate(): void {
  editingId.value = null
  form.templateName = ''
  form.chargeType = 1
  form.rules = []
  addRule()
  formVisible.value = true
}

function openEdit(row: FreightTemplateView): void {
  editingId.value = row.id
  form.templateName = row.templateName
  form.chargeType = row.chargeType
  form.rules = (row.rules ?? []).map<FreightRule>((rule) => ({
    id: rule.id,
    region: rule.region,
    firstUnit: Number(rule.firstUnit),
    firstFee: Number(rule.firstFee),
    additionalUnit: Number(rule.additionalUnit),
    additionalFee: Number(rule.additionalFee),
    freeShippingAmount: rule.freeShippingAmount == null ? null : Number(rule.freeShippingAmount),
  }))
  formVisible.value = true
}

async function onSubmit(): Promise<void> {
  await formRef.value?.validate()
  if (form.rules.length === 0) {
    message.warning('至少需要一条区域规则')
    return
  }
  submitting.value = true
  try {
    const payload: FreightTemplateSaveRequest = {
      templateName: form.templateName,
      chargeType: form.chargeType,
      rules: form.rules.map((rule) => ({ ...rule, region: rule.region?.trim() || 'ALL' })),
    }
    if (editingId.value == null) {
      await createFreightTemplate(payload)
    } else {
      await updateFreightTemplate(editingId.value, payload)
    }
    message.success('保存成功')
    formVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

function confirmDelete(row: FreightTemplateView): void {
  dialog.warning({
    title: '确认删除',
    content: `确定删除模板「${row.templateName}」吗?仍有商品使用它时后端会拒绝(避免这些商品突然变成包邮)。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      await deleteFreightTemplate(row.id)
      message.success('已删除')
      await load()
    },
  })
}

onMounted(load)
</script>
