import axios from 'axios'
import router from '../router'
import { clearAuth, getToken } from '../stores/auth'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || ''

const api = axios.create({
  baseURL: API_BASE_URL || undefined,
  timeout: 30000,
})

api.interceptors.request.use(
  function onFulfilled(config) {
    const token = getToken()
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  function onRejected(error) {
    return Promise.reject(error)
  }
)

api.interceptors.response.use(
  function onFulfilled(response) {
    if (response.config.responseType === 'blob') {
      return response.data
    }

    const payload = response.data
    if (!payload || typeof payload.code !== 'number') {
      return Promise.reject(new Error('服务器返回格式不符合 ApiResponse'))
    }
    if (payload.code !== 200) {
      return Promise.reject(new Error(payload.message || '请求处理失败'))
    }
    return payload.data
  },
  function onRejected(error) {
    if (error.response && error.response.status === 401) {
      clearAuth()
      if (router.currentRoute.value.path !== '/login') {
        router.replace({
          path: '/login',
          query: { redirect: router.currentRoute.value.fullPath }
        })
      }
    }

    const message =
      error.response?.data?.message
      || error.message
      || '请求失败'
    return Promise.reject(new Error(message))
  }
)

export async function request(path, options = {}) {
  return api({
    method: options.method || 'GET',
    url: path,
    data: options.body,
    headers: options.headers,
    responseType: options.responseType,
  })
}

export default api
