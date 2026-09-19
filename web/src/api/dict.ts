import type { Id, PageResult } from '@/types/api'
import type {
  DictDataSaveRequest,
  DictDataView,
  DictItemView,
  DictTypeSaveRequest,
  DictTypeView,
} from '@/types/system'
import { request } from '@/utils/request'

/**
 * 字典管理(后端 SysDictController,路径 /system/dicts)。
 *
 * 一件容易被误解的事:字典是**平台级配置**(架构文档 4.6.1),全局共用、不分租户。
 * 维护接口的权限码 `system:dict:*` 挂在 `is_platform = 1` 的菜单下,而租户角色的授权会被
 * "不得超出套餐范围"(5.2.1)拦住 —— 平台菜单不在任何套餐里,所以租户**永远拿不到**这些权限码。
 * 不需要在前端额外写"是不是租户"的判断,拿不到权限码时按钮自然不显示。
 */

export interface DictTypePageQuery {
  dictType?: string
  dictName?: string
  pageNo?: number
  pageSize?: number
}

export function pageDictTypes(query: DictTypePageQuery): Promise<PageResult<DictTypeView>> {
  return request.get<PageResult<DictTypeView>>('/system/dicts/types', { params: query })
}

export function createDictType(data: DictTypeSaveRequest): Promise<Id> {
  return request.post<Id>('/system/dicts/types', data)
}

export function updateDictType(typeId: Id, data: DictTypeSaveRequest): Promise<void> {
  return request.put<void>(`/system/dicts/types/${typeId}`, data)
}

/**
 * 删除字典类型。
 *
 * 后端会在同一事务里连带清理该类型下的全部字典项 —— 否则会留下永远查不到的孤儿行,
 * 下次建同名类型时它们会"复活"。确认弹窗里必须把这件事说清楚。
 */
export function deleteDictType(typeId: Id): Promise<void> {
  return request.delete<void>(`/system/dicts/types/${typeId}`)
}

/** 某类型下的字典项列表。按类型编码查,不是按主键。 */
export function listDictData(dictType: string): Promise<DictDataView[]> {
  return request.get<DictDataView[]>(`/system/dicts/types/${dictType}/data`)
}

export function createDictData(data: DictDataSaveRequest): Promise<Id> {
  return request.post<Id>('/system/dicts/data', data)
}

export function updateDictData(dataId: Id, data: DictDataSaveRequest): Promise<void> {
  return request.put<void>(`/system/dicts/data/${dataId}`, data)
}

export function deleteDictData(dataId: Id): Promise<void> {
  return request.delete<void>(`/system/dicts/data/${dataId}`)
}

/**
 * 取某个类型的下拉项(只读)。
 *
 * 这个接口**刻意不需要** `system:dict:*` 权限码(仅要求登录):它就是给各业务页面渲染下拉框、
 * 翻译字典值用的。加了权限码的话,租户必须被授予"字典管理"这个平台菜单才能显示下拉框 ——
 * 而业务上没人会把平台配置的维护权限授给租户,结果是租户侧所有下拉框都空掉。
 */
export function dictValues(dictType: string): Promise<DictItemView[]> {
  return request.get<DictItemView[]>(`/system/dicts/values/${dictType}`)
}
