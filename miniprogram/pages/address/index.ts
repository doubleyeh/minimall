import { addressCreate, addressDelete, addressList, addressSetDefault, addressUpdate } from '../../api/client'
import type { AddressView, Id } from '../../types/client'
import { confirmModal, toast, toastError, toastOk } from '../../utils/ui'

interface AddressRow {
  id: Id
  receiverName: string
  receiverPhone: string
  full: string
  isDefault: boolean
}

interface AddressForm {
  receiverName: string
  receiverPhone: string
  /** [省, 市, 区],由原生 region picker 给出 */
  region: string[]
  detailAddress: string
  defaultAddress: boolean
}

const EMPTY_FORM: AddressForm = {
  receiverName: '',
  receiverPhone: '',
  region: [],
  detailAddress: '',
  defaultAddress: false,
}

Page({
  data: {
    list: [] as AddressRow[],
    showForm: false,
    editingId: '' as Id,
    form: { ...EMPTY_FORM, region: [] as string[] },
    regionText: '',
    saving: false,
  },

  onLoad() {
    void this.load()
  },

  async load() {
    try {
      const list = await addressList()
      // 原始数据也留一份:省市区在展示行里被拼成了一整串,编辑时要按字段回填
      this.rawList = list || []
      this.setData({ list: this.rawList.map(toRow) })
    } catch (err) {
      toastError(err, '地址加载失败')
    }
  },

  onToggleForm() {
    if (!this.data.showForm) {
      this.setData({ showForm: true, editingId: '', form: { ...EMPTY_FORM, region: [] }, regionText: '' })
      return
    }
    void this.save()
  },

  onCancelForm() {
    this.setData({ showForm: false, editingId: '', form: { ...EMPTY_FORM, region: [] }, regionText: '' })
  },

  onEdit(e: WechatMiniprogram.TouchEvent) {
    const id = String(e.currentTarget.dataset.id || '')
    const source = this.data.list.find((item) => item.id === id)
    if (!source) {
      return
    }
    const raw = this.rawById(id)
    if (!raw) {
      return
    }
    const region = [raw.province, raw.city, raw.district]
    this.setData({
      showForm: true,
      editingId: id,
      form: {
        receiverName: raw.receiverName,
        receiverPhone: raw.receiverPhone,
        region,
        detailAddress: raw.detailAddress,
        defaultAddress: raw.isDefault === 1,
      },
      regionText: region.join(' '),
    })
  },

  /** 表单要回填原始字段(省市区是分开的),所以保留一份原始数据 */
  rawList: [] as AddressView[],

  rawById(id: Id): AddressView | undefined {
    return this.rawList.find((item) => item.id === id)
  },

  onInput(e: WechatMiniprogram.Input) {
    const field = String(e.currentTarget.dataset.field || '')
    if (!field) {
      return
    }
    this.setData({ [`form.${field}`]: e.detail.value })
  },

  onRegionChange(e: WechatMiniprogram.PickerChange) {
    const region = (e.detail.value as string[]) || []
    this.setData({ 'form.region': region, regionText: region.join(' ') })
  },

  onDefaultChange(e: WechatMiniprogram.SwitchChange) {
    this.setData({ 'form.defaultAddress': e.detail.value })
  },

  async save() {
    if (this.data.saving) {
      return
    }
    const form = this.data.form
    const receiverName = form.receiverName.trim()
    const receiverPhone = form.receiverPhone.trim()
    const detailAddress = form.detailAddress.trim()

    if (!receiverName) {
      toast('请填写收货人')
      return
    }
    // 手机号先本地校验一次:省一次往返,也让提示更直接(后端也会校验,不能只靠这里)
    if (!/^1\d{10}$/.test(receiverPhone)) {
      toast('请填写正确的手机号')
      return
    }
    if (!form.region || form.region.length < 3) {
      toast('请选择所在地区')
      return
    }
    if (!detailAddress) {
      toast('请填写详细地址')
      return
    }

    this.setData({ saving: true })
    try {
      const payload = {
        receiverName,
        receiverPhone,
        province: form.region[0],
        city: form.region[1],
        district: form.region[2],
        detailAddress,
        defaultAddress: form.defaultAddress,
      }
      if (this.data.editingId) {
        await addressUpdate(this.data.editingId, payload)
      } else {
        await addressCreate(payload)
      }
      toastOk('已保存')
      this.onCancelForm()
      await this.load()
    } catch (err) {
      toastError(err, '保存失败')
    } finally {
      this.setData({ saving: false })
    }
  },

  async onSetDefault(e: WechatMiniprogram.TouchEvent) {
    const id = String(e.currentTarget.dataset.id || '')
    try {
      await addressSetDefault(id)
      await this.load()
    } catch (err) {
      toastError(err)
    }
  },

  async onDelete(e: WechatMiniprogram.TouchEvent) {
    const id = String(e.currentTarget.dataset.id || '')
    if (!(await confirmModal('确定删除这条地址吗?'))) {
      return
    }
    try {
      await addressDelete(id)
      await this.load()
    } catch (err) {
      toastError(err)
    }
  },
})

/** 详情行:省市区与详细地址拼成一整串展示,编辑时走 rawList 里的分字段数据 */
function toRow(item: AddressView): AddressRow {
  return {
    id: item.id,
    receiverName: item.receiverName,
    receiverPhone: item.receiverPhone,
    full: `${item.province}${item.city}${item.district}${item.detailAddress}`,
    isDefault: item.isDefault === 1,
  }
}
