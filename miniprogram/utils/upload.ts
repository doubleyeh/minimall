/**
 * 图片上传(评价晒图、售后凭证共用)。
 *
 * 业务表里存 **url** 而不是 key —— 与后台端一致(见 `web/src/api/file.ts`):
 * `key` 是服务端定位存储用的,只有 `url` 能直接放进 `<image src>` 展示。
 *
 * 必须走 `wx.uploadFile` 而不是 `utils/request`:multipart 的分隔边界由基础库生成,
 * 手拼 Content-Type 只会拼错。代价是它的 `data` 是字符串,得自己 JSON.parse。
 */
import { API_BASE_URL, REQUEST_TIMEOUT, TENANT_CODE } from './env'
import { getToken, silentLogin } from './auth'
import { ApiError } from './request'

/** 上传成功后的返回体,与后端 `FileUploadView` 对应。 */
interface UploadedFile {
  key: string
  url: string
  size: number
}

interface Envelope<T> {
  code: number
  message: string
  data: T
}

/**
 * 选图并逐张上传,返回可直接存进业务表的访问地址。
 *
 * <p>用户取消选择时返回空数组 —— 取消不是错误,不该弹提示。
 */
export async function chooseAndUploadImages(max: number): Promise<string[]> {
  if (max <= 0) {
    return []
  }
  const paths = await chooseImages(max)
  const urls: string[] = []
  for (const path of paths) {
    const uploaded = await uploadImage(path)
    urls.push(uploaded.url)
  }
  return urls
}

function chooseImages(max: number): Promise<string[]> {
  return new Promise((resolve) => {
    wx.chooseMedia({
      count: max,
      mediaType: ['image'],
      sourceType: ['album', 'camera'],
      sizeType: ['compressed'],
      success: (res) => resolve(res.tempFiles.map((file) => file.tempFilePath)),
      fail: () => resolve([]),
    })
  })
}

async function uploadImage(filePath: string): Promise<UploadedFile> {
  let response = await doUpload(filePath)
  if (response.statusCode === 401) {
    // 与 request 同一套:令牌过期时静默重登再传一次
    await silentLogin()
    response = await doUpload(filePath)
  }
  return unwrap(response)
}

function doUpload(filePath: string): Promise<{ statusCode: number; body: Envelope<UploadedFile> | null }> {
  return new Promise((resolve, reject) => {
    wx.uploadFile({
      url: `${API_BASE_URL}/mall/api/file-uploads`,
      filePath,
      name: 'file',
      header: buildUploadHeader(),
      timeout: REQUEST_TIMEOUT,
      success: (res) => resolve({ statusCode: res.statusCode, body: parse(res.data) }),
      fail: (err) => reject(new ApiError(-1, `图片上传失败:${err.errMsg}`)),
    })
  })
}

function buildUploadHeader(): Record<string, string> {
  const header: Record<string, string> = { 'X-Tenant-Code': TENANT_CODE }
  const token = getToken()
  if (token) {
    header.Authorization = `Bearer ${token}`
  }
  return header
}

function parse(raw: string): Envelope<UploadedFile> | null {
  try {
    return JSON.parse(raw) as Envelope<UploadedFile>
  } catch {
    return null
  }
}

function unwrap(response: { statusCode: number; body: Envelope<UploadedFile> | null }): UploadedFile {
  if (response.statusCode >= 400) {
    throw new ApiError(response.statusCode, `图片上传失败(${response.statusCode})`)
  }
  const body = response.body
  if (body === null || typeof body !== 'object') {
    throw new ApiError(-1, '上传响应格式异常')
  }
  if (body.code !== 0) {
    throw new ApiError(body.code, body.message)
  }
  return body.data
}
