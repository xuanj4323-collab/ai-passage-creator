import request from '@/request'

/**
 * 个人中心的三个接口（后端 UserController 手写，未走 openapi2ts 生成）
 */

interface BaseResponse<T> {
  code?: number
  data?: T
  message?: string
}

export interface UserUpdateMyPayload {
  userName?: string
  userAvatar?: string
  userProfile?: string
}

export interface UpdatePasswordPayload {
  oldPassword: string
  newPassword: string
  checkPassword: string
}

/** 上传当前用户头像 POST /user/avatar */
export async function uploadAvatar(file: File, options?: { [key: string]: any }) {
  const formData = new FormData()
  formData.append('file', file)
  return request<BaseResponse<string>>('/user/avatar', {
    method: 'POST',
    data: formData,
    ...(options || {}),
  })
}

/** 修改当前用户资料 POST /user/update/my */
export async function updateMyUser(
  body: UserUpdateMyPayload,
  options?: { [key: string]: any },
) {
  return request<BaseResponse<API.LoginUserVO>>('/user/update/my', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 修改当前用户密码 POST /user/update/password */
export async function updatePassword(
  body: UpdatePasswordPayload,
  options?: { [key: string]: any },
) {
  return request<BaseResponse<boolean>>('/user/update/password', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}
