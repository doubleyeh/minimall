<template>
  <div class="page">
    <t-navbar title="收货地址" left-arrow :fixed="false" @go-back="router.back()" />

    <div v-if="addresses.length === 0" class="empty-tip">还没有收货地址,点下方按钮新增</div>

    <div v-for="address in addresses" :key="address.id" class="card">
      <div class="row">
        <span class="name">{{ address.receiverName }}</span>
        <span class="phone">{{ address.receiverPhone }}</span>
        <span v-if="address.isDefault === 1" class="badge">默认</span>
      </div>
      <div class="detail">
        {{ address.province }}{{ address.city }}{{ address.district }}{{ address.detailAddress }}
      </div>
      <div class="ops">
        <t-button v-if="address.isDefault !== 1" size="extra-small" variant="text" @click="onSetDefault(address.id)">
          设为默认
        </t-button>
        <t-button size="extra-small" variant="text" @click="openEdit(address)">编辑</t-button>
        <t-button size="extra-small" theme="danger" variant="text" @click="onDelete(address.id)">删除</t-button>
      </div>
    </div>

    <div class="page-gap"></div>
    <div class="footer">
      <t-button block theme="primary" @click="openCreate">新增收货地址</t-button>
    </div>

    <t-popup v-model="formVisible" placement="bottom">
      <div class="popup">
        <div class="popup-title">{{ editingId ? '编辑地址' : '新增地址' }}</div>
        <t-input v-model="form.receiverName" label="收货人" placeholder="请输入姓名" />
        <div class="gap"></div>
        <t-input v-model="form.receiverPhone" label="手机号" placeholder="请输入手机号" />
        <div class="gap"></div>
        <t-input v-model="form.province" label="省份" placeholder="如 广东省" />
        <div class="gap"></div>
        <t-input v-model="form.city" label="城市" placeholder="如 深圳市" />
        <div class="gap"></div>
        <t-input v-model="form.district" label="区县" placeholder="如 南山区" />
        <div class="gap"></div>
        <t-input v-model="form.detailAddress" label="详细地址" placeholder="街道门牌" />
        <div class="gap"></div>
        <t-cell title="设为默认地址">
          <template #note>
            <t-switch v-model="form.defaultAddress" />
          </template>
        </t-cell>
        <div class="gap"></div>
        <t-button block theme="primary" :loading="submitting" @click="onSubmit">保存</t-button>
      </div>
    </t-popup>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import { addressCreate, addressDelete, addressList, addressSetDefault, addressUpdate } from '@/api/client'
import type { AddressSaveRequest, AddressView, Id } from '@/types/client'
import { ApiError } from '@/utils/request'

/**
 * 收货地址管理。
 *
 * 三条规则都由**服务端**保证,端上只是照着做界面:
 * 1. "同一客户至多一条默认地址":设为默认时后端会先清掉其他记录的默认标记(同一事务);
 * 2. 第一条地址自动成为默认 —— 否则用户下单时会看到"没有默认地址",还得回列表手动设一次;
 * 3. 删除默认地址后后端会把默认标记挪给另一条,不会留下"没有默认地址"的状态。
 */
const router = useRouter()

const addresses = ref<AddressView[]>([])
const formVisible = ref(false)
const submitting = ref(false)
const editingId = ref<Id | null>(null)

const form = reactive<AddressSaveRequest>({
  receiverName: '',
  receiverPhone: '',
  province: '',
  city: '',
  district: '',
  detailAddress: '',
  defaultAddress: false,
})

async function load(): Promise<void> {
  addresses.value = await addressList()
}

function resetForm(): void {
  form.receiverName = ''
  form.receiverPhone = ''
  form.province = ''
  form.city = ''
  form.district = ''
  form.detailAddress = ''
  form.defaultAddress = false
}

function openCreate(): void {
  editingId.value = null
  resetForm()
  formVisible.value = true
}

function openEdit(address: AddressView): void {
  editingId.value = address.id
  form.receiverName = address.receiverName
  form.receiverPhone = address.receiverPhone
  form.province = address.province
  form.city = address.city
  form.district = address.district
  form.detailAddress = address.detailAddress
  form.defaultAddress = address.isDefault === 1
  formVisible.value = true
}

async function onSubmit(): Promise<void> {
  if (!form.receiverName.trim() || !form.receiverPhone.trim() || !form.detailAddress.trim()) {
    window.alert('请填写收货人、手机号与详细地址')
    return
  }
  submitting.value = true
  try {
    if (editingId.value == null) {
      await addressCreate({ ...form })
    } else {
      await addressUpdate(editingId.value, { ...form })
    }
    formVisible.value = false
    await load()
  } catch (error) {
    window.alert(error instanceof ApiError ? error.message : '保存失败')
  } finally {
    submitting.value = false
  }
}

async function onSetDefault(addressId: Id): Promise<void> {
  await addressSetDefault(addressId)
  await load()
}

async function onDelete(addressId: Id): Promise<void> {
  if (!window.confirm('确定删除这条地址吗?')) {
    return
  }
  await addressDelete(addressId)
  await load()
}

onMounted(load)
</script>

<style scoped>
.row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
}

.name {
  font-weight: 600;
}

.phone {
  color: #666;
}

.badge {
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--mall-price-color);
  color: #fff;
  font-size: 11px;
}

.detail {
  margin-top: 6px;
  color: #666;
  font-size: 13px;
  line-height: 1.5;
}

.ops {
  display: flex;
  justify-content: flex-end;
  gap: 4px;
  margin-top: 8px;
}

.page-gap {
  height: 72px;
}

.footer {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  padding: 8px 12px;
  padding-bottom: calc(8px + env(safe-area-inset-bottom));
  background: #fff;
  border-top: 1px solid #f0f0f0;
}

.popup {
  padding: 16px;
  background: #fff;
  max-height: 75vh;
  overflow-y: auto;
}

.popup-title {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 12px;
}

.gap {
  height: 12px;
}
</style>
