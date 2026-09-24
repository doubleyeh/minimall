import type { FileUploadView } from '@/types/api'
import { request } from '@/utils/request'

/**
 * 上传文件(后端 POST /file-uploads)。
 *
 * 用 FormData 提交,**不要手动设 Content-Type**:multipart 的分隔边界由浏览器生成,
 * 手写会漏掉 boundary,后端直接解析失败。
 *
 * 上传接口只要求登录态,不单独要权限码;访问 /files/{key} 是公开的(图片要能在小程序里直接显示)。
 */
export function uploadFile(file: File): Promise<FileUploadView> {
  const data = new FormData()
  data.append('file', file)
  return request.post<FileUploadView>('/file-uploads', data)
}
