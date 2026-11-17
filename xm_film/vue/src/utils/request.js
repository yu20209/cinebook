import axios from 'axios'
import { ElMessage } from 'element-plus'
import { getStoredUser, clearStoredUser, LOGIN_NOTICE_KEY } from '@/utils/authStorage'
import { API_BASE_URL } from '@/constants'
import { isPathWithin, loginPath } from '@/utils/navigation'

/** axios 实例：统一 baseURL 与 30s 超时；所有业务模块共用这一份 */
const request = axios.create({
  baseURL: API_BASE_URL,
  timeout: 30000,
})

// 防止多个并发 401 同时触发整页跳转（跳转期间其余请求仍会失败）
let redirectingToLogin = false

/**
 * 登录态失效统一跳登录页，并带上来源路径供登录后回跳。
 * 只有 AuthInterceptor 直写 setStatus(401) 的响应才会走到这里；
 * 业务异常（含登录失败）经 GlobalExceptionHandler 返回的是 HTTP 200 + body code=401，
 * 不会命中该分支，所以不存在"登录页自己 401 再跳登录页"的死循环。
 */
function redirectToLogin() {
  if (redirectingToLogin) return
  const { pathname, search } = window.location
  if (isPathWithin(pathname, '/login')) return
  redirectingToLogin = true
  // 整页跳转会丢弃 ElMessage，提示落到登录页再弹（见 Login.vue）
  sessionStorage.setItem(LOGIN_NOTICE_KEY, '登录状态已过期，请重新登录')
  window.location.href = loginPath(pathname + search)
}

/**
 * 请求拦截器：
 * 1. 统一 JSON Content-Type；
 * 2. 有 token 时注入 Authorization: Bearer，后端 AuthInterceptor 据此鉴权。
 *    这里只读本地存储，不做有效性判断 —— 失效由响应 401 统一处理。
 */
request.interceptors.request.use(
  config => {
    config.headers['Content-Type'] = 'application/json;charset=utf-8'

    const user = getStoredUser()
    if (user?.token) {
      config.headers.Authorization = `Bearer ${user.token}`
    }

    return config
  },
  error => Promise.reject(error)
)

/**
 * 响应拦截器：
 * - 成功分支：后端统一返回 Result，个别接口会以字符串返回，这里兼容解析；
 * - 失败分支：网络错误/超时给专门提示；HTTP 401 清登录态并整页跳登录；
 *   其余状态码优先展示后端 msg，没有再按状态码兜底。
 *
 * 注意：业务失败是 HTTP 200 + body.code，不会进入这里的 error 分支，
 * 由各调用方用 isSuccess(res) 判断。
 */
request.interceptors.response.use(
  response => {
    const res = response.data
    if (typeof res === 'string' && res) {
      return JSON.parse(res)
    }
    return res
  },
  error => {
    if (error.code === 'ERR_NETWORK') {
      ElMessage.error('无法连接到服务器，请检查网络或后端是否启动')
      return Promise.reject(error)
    }

    if (error.code === 'ECONNABORTED') {
      ElMessage.error('请求超时，请检查网络后重试')
      return Promise.reject(error)
    }

    const status = error.response?.status
    const backendMessage = error.response?.data?.msg

    if (status === 401) {
      clearStoredUser()
      redirectToLogin()
    }

    if (backendMessage) {
      ElMessage.error(backendMessage)
      return Promise.reject(error)
    }

    switch (status) {
      case 401:
        ElMessage.warning('登录状态已过期，请重新登录')
        break
      case 403:
        ElMessage.error('权限不足，无法访问')
        break
      case 404:
        ElMessage.error('未找到请求接口')
        break
      case 500:
        ElMessage.error('系统异常，请检查后端控制台报错')
        break
      default:
        if (status) {
          ElMessage.error(`请求失败 (${status})`)
        } else {
          ElMessage.error(`请求失败: ${error.message}`)
        }
    }

    return Promise.reject(error)
  }
)

export default request
