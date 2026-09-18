import type { Id } from '@/types/api'
import type { DeptSaveRequest, DeptTreeNode } from '@/types/system'
import { request } from '@/utils/request'

/** 部门管理(后端 SysDeptController,/system/depts) */

export function deptTree(status?: number | null): Promise<DeptTreeNode[]> {
  return request.get<DeptTreeNode[]>('/system/depts/tree', { params: { status } })
}

export function createDept(data: DeptSaveRequest): Promise<Id> {
  return request.post<Id>('/system/depts', data)
}

export function updateDept(deptId: Id, data: DeptSaveRequest): Promise<void> {
  return request.put<void>(`/system/depts/${deptId}`, data)
}

export function deleteDept(deptId: Id): Promise<void> {
  return request.delete<void>(`/system/depts/${deptId}`)
}
